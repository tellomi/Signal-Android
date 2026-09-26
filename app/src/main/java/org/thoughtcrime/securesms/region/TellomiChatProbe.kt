/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.push.SignalServiceTrustStore
import org.whispersystems.signalservice.internal.util.BlacklistingTrustManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tellomi（tellomi/tellomi#1055 第四刀）：选路器的探测——对区的 chat 主机做 TCP + TLS 握手并计时
 * （和 Desktop 的 `createChatProbe`、iOS 的 `chatEndpointProbe` 同一件事）。不发请求、不要账号，注册前也能用。
 *
 * 证书按 App 自己的信任库校验（`R.raw.whisper`，和 REST 的 PushServiceSocket 同一份），再核主机名：
 * DNS 被污染的入口过不了握手，不会被当成健康。DNS 解析也算在超时里。
 */
object TellomiChatProbe {

  /** 和 REST 同一份信任根。 */
  private val trustedSocketFactory: SSLSocketFactory by lazy {
    val trustManagers = BlacklistingTrustManager.createFor(SignalServiceTrustStore(AppDependencies.application))
    SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }.socketFactory
  }

  @JvmStatic
  fun probe(region: TellomiRegionProfile, timeoutMs: Long): TellomiRegionSelector.ProbeResult {
    return probe(region, timeoutMs, trustedSocketFactory)
  }

  /** [executor]：握手在哪条线程上做（这条线程超时后还可能卡在 DNS 里一会儿，所以不占调用方的线程）。 */
  @JvmStatic
  @JvmOverloads
  fun probe(
    region: TellomiRegionProfile,
    timeoutMs: Long,
    socketFactory: SSLSocketFactory,
    executor: Executor = SignalExecutors.UNBOUNDED
  ): TellomiRegionSelector.ProbeResult {
    val url = region.chat.toHttpUrlOrNull() ?: return TellomiRegionSelector.ProbeResult.Failed("bad chat url: ${region.chat}")
    val socket = Socket()
    val handshake = FutureTask(
      Callable {
        val startedAt = System.nanoTime()
        // InetSocketAddress(host, port) 在这里解析 DNS，所以解析也算在超时里
        socket.connect(InetSocketAddress(url.host, url.port), timeoutMs.toInt())
        val ssl = socketFactory.createSocket(socket, url.host, url.port, true) as SSLSocket
        ssl.soTimeout = timeoutMs.toInt()
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, ssl.session)) {
          throw SSLPeerUnverifiedException("certificate does not match ${url.host}")
        }
        val rttMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        ssl.close()
        rttMs
      }
    )
    executor.execute(handshake)

    return try {
      TellomiRegionSelector.ProbeResult.Ok(handshake.get(timeoutMs, TimeUnit.MILLISECONDS))
    } catch (e: TimeoutException) {
      TellomiRegionSelector.ProbeResult.Failed("timeout after ${timeoutMs}ms")
    } catch (e: ExecutionException) {
      TellomiRegionSelector.ProbeResult.Failed((e.cause ?: e).toString())
    } finally {
      // 超时或失败时打断还在进行的连接 / 握手
      socket.close()
    }
  }
}
