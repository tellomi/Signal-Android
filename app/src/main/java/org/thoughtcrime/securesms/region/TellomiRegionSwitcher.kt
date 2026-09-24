/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * Tellomi（tellomi/tellomi#1055 第三刀）：切区，RegionProfile 契约第六节的 `switchTo`。
 *
 * Android 上切区 = 记下新区，再 [AppDependencies.resetNetwork] + [AppDependencies.startNetwork]。
 * `resetNetwork()` 丢掉整个网络对象图（`SignalServiceNetworkAccess` 和它的配置、libsignal `Network`、PushServiceSocket…），
 * 下次用的时候按 [TellomiRegions.current] 重建；两个 websocket 断开，按新配置重连。上游切代理、切审查规避时走的就是这条路。
 *
 * 不跟着切的（契约第六节 Android 那条点名的几样）：
 * - `SignalServiceNetworkAccess.DNS` 的静态 IP 表：只有 global 档有静态 IP（契约第三节），CN 的主机在表里查不到，
 *   落到系统 DNS，所以不用重建。
 * - 内容代理的几个长寿命 OkHttpClient（Glide / Giphy / ExoPlayer，`RemoteConfig.gifProxyUrl` 那条写明了不热切换）：
 *   要等重启才跟。GIF 以后要移除（owner 2026-09-23）；而且服务端下发的代理地址优先，本来就绕过区（#1252，M6）。
 * - 在途的续传：同一刀的下一个提交处理（开始时的区和现在的不一样，就当规格过期，从头传）。
 *
 * 驻留时间、失败阈值这些策略归选路器（下一刀），不在这里。
 */
class TellomiRegionSwitcher(
  private val profiles: () -> List<TellomiRegionProfile>,
  private val storedId: () -> String?,
  private val record: (TellomiRegionId, Long) -> Unit,
  private val resetNetwork: () -> Unit,
  private val now: () -> Long = System::currentTimeMillis
) {

  enum class Reason {
    /** 这个进程的区域表里没有这个区。 */
    UNKNOWN,

    /** 那个区关着（契约第六节：disabled 的区直接报错）。 */
    DISABLED
  }

  class SwitchException(val reason: Reason) : Exception("Can't switch region: $reason")

  /**
   * 切到 [id]：记下区和切区时间（「驻留时间从哪算起」要持久化），再重建网络。
   *
   * 不认识的区、关着的区直接抛 [SwitchException]，**在碰网络之前**：不重建、不连接、不查 DNS。
   * 跟当前区相同就什么都不做，返回 false。
   */
  @Synchronized
  @Throws(SwitchException::class)
  fun switchTo(id: TellomiRegionId): Boolean {
    val table = profiles()
    val target = table.firstOrNull { it.id == id } ?: throw SwitchException(Reason.UNKNOWN)
    if (!target.enabled) {
      throw SwitchException(Reason.DISABLED)
    }

    val current = TellomiRegions.resolve(storedId(), table)
    if (current.id == id) {
      return false
    }

    // 先记再重建：重建时各处按 TellomiRegions.current() 取，读到的就是新区
    record(id, now())
    Log.i(TAG, "Switching region ${current.id.id} -> ${id.id}, resetting the network")
    resetNetwork()
    return true
  }

  companion object {
    private val TAG = Log.tag(TellomiRegionSwitcher::class.java)

    @JvmStatic
    val instance: TellomiRegionSwitcher by lazy {
      TellomiRegionSwitcher(
        profiles = { TellomiRegions.ALL },
        storedId = { SignalStore.tellomiRegion.currentId },
        record = { id, at ->
          SignalStore.tellomiRegion.currentId = id.id
          SignalStore.tellomiRegion.lastSwitchAt = at
        },
        resetNetwork = {
          AppDependencies.resetNetwork()
          AppDependencies.startNetwork()
        }
      )
    }
  }
}
