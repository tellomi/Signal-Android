/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import kotlin.time.Duration.Companion.minutes

/**
 * Tellomi（tellomi/tellomi#1055 第四刀）：选路器，RegionProfile 契约第六节（ADR-0065 §6.5）。
 *
 * 只给建议，不切换：[probe] 并发探所有**开着的**区，按决策表给出建议和理由；切不切、什么时候切，由调用方拿决策去调
 * [TellomiRegionSwitcher.switchTo]。决策表和阈值与 Desktop 的 `RegionSelector`（Signal-Desktop `ts/util/tellomiRegion.std.ts`）、
 * iOS 的 `TellomiRegionSelector` 逐条一致，用例也是照 Desktop 的搬过来的。
 *
 * 和 Desktop 不同的一处：当前区和上次切区时间不在这里另存一份，用的时候现取——当前区按 [TellomiRegions.current]，
 * 上次切区时间是 `SignalStore.tellomiRegion.lastSwitchAt`（切换器切区时写）。选路器和真正生效的区不会分叉。
 */
class TellomiRegionSelector(
  private val profiles: () -> List<TellomiRegionProfile>,
  private val currentRegion: () -> TellomiRegionId,
  /** 毫秒；null = 没有记录。 */
  private val lastSwitchAt: () -> Long?,
  private val probeRegion: (TellomiRegionProfile, Long) -> ProbeResult = TellomiChatProbe::probe,
  private val now: () -> Long = System::currentTimeMillis,
  private val thresholds: Thresholds = Thresholds.DEFAULT,
  private val executor: Executor = SignalExecutors.UNBOUNDED
) {

  data class Thresholds(
    /** 上次切区之后这么久之内，不因为「别的区更快」而切（failover 不受它限制）。没有记录 = 已满足，所以冷启动时 RTT 更低的区直接胜出。 */
    val minDwellMs: Long,
    /** 当前区连续失败这么多次才允许 failover：单次超时不切。 */
    val failureThreshold: Int,
    /** 别的区要快出这么多才切。 */
    val latencyAdvantageMs: Long,
    /** 单次探测超时。 */
    val probeTimeoutMs: Long
  ) {
    companion object {
      /** 编进包里的默认值（契约第六节表）：拿 /v2/config 本身要先连上服务端，所以不能只放在远程配置里。 */
      @JvmField
      val DEFAULT = Thresholds(minDwellMs = 10.minutes.inWholeMilliseconds, failureThreshold = 3, latencyAdvantageMs = 50, probeTimeoutMs = 5_000)
    }
  }

  sealed interface ProbeResult {
    data class Ok(val rttMs: Long) : ProbeResult
    data class Failed(val error: String) : ProbeResult
  }

  enum class Reason(val id: String) {
    STAY("stay"),
    FASTER("faster"),
    DWELL("dwell"),
    FAILOVER("failover"),
    CURRENT_FAILING("current-failing"),
    NO_ALTERNATIVE("no-alternative")
  }

  data class Decision(
    val current: TellomiRegionId,
    val recommended: TellomiRegionId,
    val reason: Reason,
    val results: Map<TellomiRegionId, ProbeResult>
  )

  /** 连续失败按区计：生效区变了（切过区）就从 0 重新数。 */
  private var failureRegion: TellomiRegionId? = null
  private var failureCount = 0

  /** 当前区一次连接失败。返回 true = 连续失败到阈值了，该探测了（契约第六节的触发条件：连续 N 次连接失败）。 */
  @Synchronized
  fun reportConnectionFailure(): Boolean {
    return countFailure(currentRegion()) >= thresholds.failureThreshold
  }

  @Synchronized
  fun reportConnectionSuccess() {
    failureRegion = currentRegion()
    failureCount = 0
  }

  /**
   * 并发探所有开着的区，给出建议。关着的区在碰网络之前就跳过：不查 DNS、不建连接（#1055 判据 4）。
   * 会阻塞到最慢的那个区探完（每个区最多 [Thresholds.probeTimeoutMs]），别在主线程调。
   */
  fun probe(): Decision {
    val tasks = profiles().filter { it.enabled }.map { region ->
      region.id to FutureTask(
        Callable {
          try {
            probeRegion(region, thresholds.probeTimeoutMs)
          } catch (e: Exception) {
            ProbeResult.Failed(e.toString())
          }
        }
      )
    }
    tasks.forEach { (_, task) -> executor.execute(task) }
    val results: Map<TellomiRegionId, ProbeResult> = tasks.associate { (id, task) -> id to task.get() }

    return synchronized(this) { decide(results) }
  }

  private fun decide(results: Map<TellomiRegionId, ProbeResult>): Decision {
    val current = currentRegion()
    fun decision(recommended: TellomiRegionId, reason: Reason) = Decision(current, recommended, reason, results)

    // 别的区里握手最快的那个
    val best = results
      .filter { (id, result) -> id != current && result is ProbeResult.Ok }
      .minByOrNull { (_, result) -> (result as ProbeResult.Ok).rttMs }
      ?.let { (id, result) -> id to (result as ProbeResult.Ok).rttMs }

    val mine = results[current] as? ProbeResult.Ok
    if (mine == null) {
      val count = countFailure(current)
      if (best == null) {
        return decision(current, Reason.NO_ALTERNATIVE)
      }
      return if (count >= thresholds.failureThreshold) decision(best.first, Reason.FAILOVER) else decision(current, Reason.CURRENT_FAILING)
    }

    failureRegion = current
    failureCount = 0
    if (best == null || best.second + thresholds.latencyAdvantageMs >= mine.rttMs) {
      return decision(current, Reason.STAY)
    }
    // 驻留从上一次切区算起（持久化的），不从进程启动算起；没有记录视为已满足
    val since = lastSwitchAt()
    return if (since == null || now() - since >= thresholds.minDwellMs) decision(best.first, Reason.FASTER) else decision(current, Reason.DWELL)
  }

  private fun countFailure(region: TellomiRegionId): Int {
    if (failureRegion != region) {
      failureRegion = region
      failureCount = 0
    }
    failureCount += 1
    return failureCount
  }

  companion object {
    /** 接本进程的区域表和 `SignalStore` 里的切区记录（切换器切区时写的那一份）。 */
    @JvmStatic
    fun forCurrentProcess(): TellomiRegionSelector {
      return TellomiRegionSelector(
        profiles = { TellomiRegions.profiles() },
        currentRegion = { TellomiRegions.current().id },
        lastSwitchAt = { SignalStore.tellomiRegion.lastSwitchAt.takeIf { it > 0 } }
      )
    }
  }
}
