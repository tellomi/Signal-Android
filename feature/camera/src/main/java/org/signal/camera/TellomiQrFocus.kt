/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.camera

import com.google.zxing.ResultPoint

/**
 * Tellomi：只放行对准画面中心、并且连续对准一段时间的二维码（tellomi/tellomi#1219 第二刀）。
 *
 * 关联设备的扫码页用它（[CameraScreenViewModel] 的构造参数，默认不开，其余扫码照上游）：
 * 扫到旁边别人屏幕上的关联码，就是把别人的电脑关联进自己的账号。
 *
 * 规则照 Telegram iOS 的扫码页：码的中心要落在画面中间 40%（横竖都在 0.3–0.7），同一个码连续对准 0.5 秒才放行；
 * 中途换成别的码，或者离开中心区超过 [maxGapMs]，都重新计时。
 * 和 Telegram 不同的一点：ZXing 逐帧解码偶尔漏一两帧，所以不超过 [maxGapMs] 的空帧不算中断。
 *
 * 只在分析线程上用，不做同步。
 */
class TellomiQrFocus(
  private val requiredStableMs: Long = 500,
  private val maxGapMs: Long = 350,
  private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
  private var candidate: String? = null
  private var firstSeenMs = 0L
  private var lastSeenMs = 0L

  /**
   * 每分析一帧调一次。[text] 是这一帧解出的码（没解出传 null）；[center] 是码中心在画面里的相对位置（0–1），拿不到传 null。
   * 返回这一帧可以放行的码；还没稳定、或者不在中心区，返回 null。
   */
  fun onFrame(text: String?, center: Pair<Float, Float>?): String? {
    val now = nowMs()
    if (text == null || center == null || !isInCenter(center.first, center.second)) {
      if (candidate != null && now - lastSeenMs > maxGapMs) {
        candidate = null
      }
      return null
    }

    if (text != candidate || now - lastSeenMs > maxGapMs) {
      candidate = text
      firstSeenMs = now
    }
    lastSeenMs = now

    return if (now - firstSeenMs >= requiredStableMs) text else null
  }

  companion object {
    private const val REGION_START = 0.3f
    private const val REGION_END = 0.7f

    fun isInCenter(x: Float, y: Float): Boolean {
      return x in REGION_START..REGION_END && y in REGION_START..REGION_END
    }

    /**
     * ZXing 给的定位点（QR 码的前三个是三个角上的定位图形）→ 码中心在画面里的相对位置。拿不到返回 null。
     *
     * 取对角的两个定位图形（三个点里相距最远的那一对）的中点：码怎么转、镜像都是码的中心，和点的顺序无关，
     * 与 iOS 用 Vision 的 boundingBox 中点是同一个点。三个点的外接框中点在码倾斜时会偏（45° 时约 0.07）。
     */
    fun centerOf(points: Array<ResultPoint?>?, width: Int, height: Int): Pair<Float, Float>? {
      val corners = points?.filterNotNull()?.take(3)
      if (corners == null || corners.size < 3 || width <= 0 || height <= 0) {
        return null
      }
      val (a, b) = listOf(corners[0] to corners[1], corners[0] to corners[2], corners[1] to corners[2])
        .maxBy { (p, q) -> ResultPoint.distance(p, q) }
      return ((a.x + b.x) / 2f / width) to ((a.y + b.y) / 2f / height)
    }
  }
}
