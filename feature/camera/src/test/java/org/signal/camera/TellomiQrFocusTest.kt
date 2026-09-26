/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.camera

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.google.zxing.ResultPoint
import org.junit.Test

/**
 * tellomi/tellomi#1219 第二刀：只放行对准中心、连续对准 0.5 秒的码。
 */
class TellomiQrFocusTest {

  private var now = 0L
  private val focus = TellomiQrFocus(nowMs = { now })

  private val center = 0.5f to 0.5f

  /** 在 [times] 这些时刻各喂一帧，返回每一帧的结果。 */
  private fun feed(text: String?, at: Pair<Float, Float>?, vararg times: Long): List<String?> {
    return times.map {
      now = it
      focus.onFrame(text, at)
    }
  }

  @Test
  fun `a centered code is released only after half a second`() {
    assertThat(feed("A", center, 0, 100, 200, 300, 400)).isEqualTo(listOf<String?>(null, null, null, null, null))
    assertThat(feed("A", center, 500)).isEqualTo(listOf<String?>("A"))
  }

  @Test
  fun `a code outside the center is never released`() {
    val results = feed("A", 0.15f to 0.5f, 0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

    assertThat(results.all { it == null }).isTrue()
  }

  @Test
  fun `switching to another code restarts the timer`() {
    feed("A", center, 0, 100, 200, 300)

    assertThat(feed("B", center, 400, 500, 600, 700, 800)).isEqualTo(listOf<String?>(null, null, null, null, null))
    assertThat(feed("B", center, 900)).isEqualTo(listOf<String?>("B"))
  }

  @Test
  fun `a missed frame or two does not restart the timer`() {
    feed("A", center, 0, 100, 200)
    feed(null, null, 300, 400)

    assertThat(feed("A", center, 500)).isEqualTo(listOf<String?>("A"))
  }

  @Test
  fun `losing the code for longer than the gap restarts the timer`() {
    feed("A", center, 0, 100, 200)
    feed(null, null, 300, 400, 500)

    assertThat(feed("A", center, 600, 700, 800, 900, 1000)).isEqualTo(listOf<String?>(null, null, null, null, null))
    assertThat(feed("A", center, 1100)).isEqualTo(listOf<String?>("A"))
  }

  @Test
  fun `the center region is the middle forty percent on both axes`() {
    assertThat(TellomiQrFocus.isInCenter(0.3f, 0.3f)).isTrue()
    assertThat(TellomiQrFocus.isInCenter(0.7f, 0.7f)).isTrue()
    assertThat(TellomiQrFocus.isInCenter(0.5f, 0.29f)).isFalse()
    assertThat(TellomiQrFocus.isInCenter(0.71f, 0.5f)).isFalse()
  }

  @Test
  fun `center comes from the three finder patterns`() {
    // 1280 × 720 的帧，码在正中：三个定位图形在 (600, 320)、(680, 320)、(600, 400)，第四个是校正图形
    val points = arrayOf<ResultPoint?>(ResultPoint(600f, 400f), ResultPoint(600f, 320f), ResultPoint(680f, 320f), ResultPoint(670f, 390f))

    val (x, y) = TellomiQrFocus.centerOf(points, 1280, 720)!!

    assertThat(x).isEqualTo(640f / 1280f)
    assertThat(y).isEqualTo(360f / 720f)
    assertThat(TellomiQrFocus.centerOf(arrayOf(ResultPoint(1f, 1f)), 1280, 720)).isNull()
    assertThat(TellomiQrFocus.centerOf(null, 1280, 720)).isNull()
    assertThat(TellomiQrFocus.centerOf(points, 0, 720)).isNull()
    assertThat(TellomiQrFocus.centerOf(points, 1280, 720)).isNotNull()
  }

  @Test
  fun `a tilted code is still centered`() {
    // 码心在 (640, 360)、转 45°：三个定位图形在 (560, 360)、(640, 280)、(720, 360)。外接框的中点会算成 (0.5, 0.444)
    val tilted = arrayOf<ResultPoint?>(ResultPoint(560f, 360f), ResultPoint(640f, 280f), ResultPoint(720f, 360f))
    // 镜像（ZXing 交换第 0、2 个点）不影响
    val mirrored = arrayOf<ResultPoint?>(ResultPoint(720f, 360f), ResultPoint(640f, 280f), ResultPoint(560f, 360f))

    assertThat(TellomiQrFocus.centerOf(tilted, 1280, 720)).isEqualTo(0.5f to 0.5f)
    assertThat(TellomiQrFocus.centerOf(mirrored, 1280, 720)).isEqualTo(0.5f to 0.5f)
  }
}
