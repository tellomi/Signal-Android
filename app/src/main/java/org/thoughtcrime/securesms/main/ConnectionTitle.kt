/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
import org.signal.core.util.ServiceUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.messages.IncomingMessageObserver
import org.thoughtcrime.securesms.util.NetworkUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState

/**
 * Tellomi（tellomi/tellomi#1218 F-04）：首屏标题在没连上服务器时换成连接状态，连上后恢复成 App 名。
 * 大陆连香港时断时续，标题直接说「现在卡在哪一步」，比一条故障横幅有用。
 *
 * 只由三样东西决定（[from]）：系统有没有网、主 WebSocket 的状态、连上之后积压的消息收完没有
 * （`IncomingMessageObserver.decryptionDrained`：服务器发来「队列已空」时置位，断开即清）。
 *
 * 机制参照 Telegram（只读，独立实现，一行未搬）：Android `LaunchActivity.updateCurrentConnectionState`
 * （等待网络 / 连接中 / 更新中，「更新中」= 已连上但还在补拉离线期间的更新）；iOS `ChatListController`
 * 的 `NetworkStatusTitle`（同样三种 + 标题旁一个小转圈，离开「已连上」时先等 0.3 秒，见 [delayLeavingConnected]）。
 */
enum class ConnectionTitle(@StringRes val text: Int) {
  NONE(0),
  WAITING_FOR_NETWORK(R.string.MainToolbar__tellomi_waiting_for_network),
  CONNECTING(R.string.MainToolbar__tellomi_connecting),
  UPDATING(R.string.MainToolbar__tellomi_updating);

  companion object {
    private val TAG = Log.tag(ConnectionTitle::class.java)

    /** 与 Telegram iOS 同一取舍：一闪而过的断线不值得让标题晃一下。 */
    const val LEAVE_CONNECTED_DELAY_MS = 300L

    /**
     * 会不会去连：没注册、被服务器判为未授权（设备被解绑等）、版本被判为过期（构建到期或服务端 499）时根本不会去连，
     * 上游各有自己的提示（过期时首屏有「此版本已过期」横幅）。和 websocket 自己的 canConnect 同口径。
     */
    @JvmStatic
    @VisibleForTesting
    fun canConnect(isRegistered: Boolean, isUnauthorized: Boolean, isClientDeprecated: Boolean): Boolean {
      return isRegistered && !isUnauthorized && !isClientDeprecated
    }

    @JvmStatic
    fun from(canConnect: Boolean, networkAvailable: Boolean, webSocketState: WebSocketConnectionState?, decryptionDrained: Boolean): ConnectionTitle {
      return when {
        // 没注册 / 被服务器判为未授权（设备被解绑等）时根本不会去连，上游有自己的提示，标题别一直转圈
        !canConnect -> NONE
        !networkAvailable -> WAITING_FOR_NETWORK
        webSocketState == WebSocketConnectionState.CONNECTED -> if (decryptionDrained) NONE else UPDATING
        // 同理：鉴权失败、版本过旧都不是「连不上」，各有上游的提示
        webSocketState == WebSocketConnectionState.AUTHENTICATION_FAILED || webSocketState == WebSocketConnectionState.REMOTE_DEPRECATED -> NONE
        else -> CONNECTING
      }
    }

    /** 首屏在前台（STARTED）期间收集；离开前台就停，回来重新取一次当前状态。 */
    fun observe(context: Context): Flow<ConnectionTitle> {
      return snapshots(context.applicationContext)
        .delayLeavingConnected()
        // 用户说「一直显示连接中」时，调试日志里能看到标题是什么时候、按什么顺序变的
        .onEach { Log.i(TAG, "Title: $it") }
    }

    /**
     * 任何一样变了就按**当前值**整体重算一次，而不是用事件里带的值——WebSocket 回调、「收完了」回调、网络回调
     * 来自三个线程，只有「在锁里读当前值再发出」才能保证最后发出的就是最新的；否则「收完了」先发出 NONE、
     * 晚到的 CONNECTED 回调再发出 UPDATING，标题会停在「收取中…」直到下一次变化。
     */
    private fun snapshots(context: Context): Flow<ConnectionTitle> = callbackFlow {
      val lock = Any()
      lateinit var drained: DrainedListenerTracker

      val publish = {
        synchronized(lock) {
          val incomingMessageObserver = drained.currentObserver()
          trySend(
            from(
              canConnect = canConnect(
                isRegistered = SignalStore.account.isRegistered,
                isUnauthorized = TextSecurePreferences.isUnauthorizedReceived(context),
                isClientDeprecated = SignalStore.misc.isClientDeprecated
              ),
              networkAvailable = NetworkUtil.isConnected(context),
              webSocketState = AppDependencies.webSocketObserver.value,
              decryptionDrained = incomingMessageObserver.decryptionDrained
            )
          )
        }
      }
      drained = DrainedListenerTracker({ AppDependencies.incomingMessageObserver }, Runnable { publish() })

      val webSocketDisposable = AppDependencies.webSocketObserver.subscribe { publish() }
      val networkWatcher = DefaultNetworkWatcher(context) { publish() }
      networkWatcher.register()
      publish()

      awaitClose {
        webSocketDisposable.dispose()
        synchronized(lock) { drained.release() }
        networkWatcher.unregister()
      }
    }.conflate()

    /**
     * 从 [NONE] 变成别的状态时先等 [delayMs] 再发出，等待期间又回到 [NONE] 就什么都不发；别的变化立刻发出。
     * 第一次的值立刻发出（刚进首屏就是没连上，应该马上说）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @VisibleForTesting
    fun Flow<ConnectionTitle>.delayLeavingConnected(delayMs: Long = LEAVE_CONNECTED_DELAY_MS): Flow<ConnectionTitle> = flow {
      var previous: ConnectionTitle? = null
      emitAll(
        distinctUntilChanged()
          .transformLatest { title ->
            val leavingConnected = previous == NONE && title != NONE
            previous = title
            if (leavingConnected) {
              delay(delayMs)
            }
            emit(title)
          }
          // 等待期间回到 NONE 时，上面会再发一次 NONE
          .distinctUntilChanged()
      )
    }
  }
}

/**
 * 系统默认网络变了就回调一次，有没有网仍以 [NetworkUtil.isConnected] 为准。
 * 不看 `NET_CAPABILITY_VALIDATED`：它靠访问 Google 的连通性检测，大陆不少机型上一直过不去，看它会一直显示「等待网络」。
 */
private class DefaultNetworkWatcher(private val context: Context, private val onChange: () -> Unit) {
  private val connectivityManager = ServiceUtil.getConnectivityManager(context)

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = onChange()
    override fun onLost(network: Network) = onChange()
    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = onChange()
    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = onChange()
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = onChange()
  }

  fun register() {
    if (Build.VERSION.SDK_INT >= 24) {
      connectivityManager.registerDefaultNetworkCallback(callback)
    } else {
      @Suppress("DEPRECATION")
      context.registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }
  }

  fun unregister() {
    if (Build.VERSION.SDK_INT >= 24) {
      connectivityManager.unregisterNetworkCallback(callback)
    } else {
      context.unregisterReceiver(receiver)
    }
  }
}

/**
 * 「收完了」的监听挂在哪个 [IncomingMessageObserver] 上：在 [ConnectionTitle] 的锁里调用 [currentObserver]，拿到的就是这次取值用的实例。
 * AppDependencies.resetNetwork() 会换一个新实例（新连接由它接收），所以每次都取当前的，换了就把监听从旧实例挪到新实例。
 * 新实例已经收完时 add 会立刻回调一次，锁可重入，回调里再取到的就是同一个实例，不会重复挂。
 */
@VisibleForTesting
internal class DrainedListenerTracker(private val current: () -> IncomingMessageObserver, private val listener: Runnable) {
  private var observer: IncomingMessageObserver? = null

  fun currentObserver(): IncomingMessageObserver {
    val now = current()
    if (now !== observer) {
      observer?.removeDecryptionDrainedListener(listener)
      observer = now
      now.addDecryptionDrainedListener(listener)
    }
    return now
  }

  fun release() {
    observer?.removeDecryptionDrainedListener(listener)
    observer = null
  }
}
