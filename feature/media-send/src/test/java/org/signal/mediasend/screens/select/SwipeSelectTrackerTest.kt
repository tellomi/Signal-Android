/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#1261 P-8）：横滑多选的判定。网格是 3 列、100×100 的格子；第 0 格可以设成不能选（受限访问横幅）。
 */
class SwipeSelectTrackerTest {

  private val selected = mutableSetOf<Int>()
  private val calls = mutableListOf<Pair<Int, Boolean>>()
  private var bannerAtZero = false

  private fun tracker() = SwipeSelectTracker(
    horizontalStart = 8f,
    verticalFail = 5f,
    indexAt = { position -> if (position.x < 0 || position.y < 0 || position.x >= 300) null else (position.y / 100).toInt() * 3 + (position.x / 100).toInt() },
    isSelected = { index -> if (bannerAtZero && index == 0) null else index in selected },
    setSelected = { index, isSelected ->
      calls += index to isSelected
      // 界面要等重组才更新：这里故意不改 selected，确认同一次滑动里每格只处理一次。
    }
  )

  private fun center(index: Int) = Offset((index % 3) * 100f + 50f, (index / 3) * 100f + 50f)

  private fun swipe(points: List<Offset>): SwipeSelectTracker.Progress {
    val tracker = tracker()
    var progress = SwipeSelectTracker.Progress.WAITING
    for (point in points.drop(1)) {
      progress = tracker.track(points.first(), point)
      if (progress == SwipeSelectTracker.Progress.FAILED) {
        break
      }
    }
    return progress
  }

  @Test
  fun `Given nothing selected, when swiping right across a row, then each tile is selected once in order`() {
    val start = center(0)
    val progress = swipe(listOf(start, start + Offset(4f, 1f), start + Offset(12f, 2f), center(1), center(1) + Offset(10f, 0f), center(2)))

    assertEquals(SwipeSelectTracker.Progress.SWIPING, progress)
    assertEquals(listOf(0 to true, 1 to true, 2 to true), calls)
  }

  @Test
  fun `Given the start tile is selected, when swiping, then tiles passed over are unselected`() {
    selected += setOf(1, 2, 4)
    val start = center(2)
    swipe(listOf(start, start - Offset(12f, 0f), center(1), center(0)))

    assertEquals(listOf(2 to false, 1 to false), calls)
  }

  @Test
  fun `Given the finger first moves more than 5 vertically, then it is a scroll and nothing changes`() {
    val start = center(4)
    val progress = swipe(listOf(start, start + Offset(3f, 6f), start + Offset(60f, 6f)))

    assertEquals(SwipeSelectTracker.Progress.FAILED, progress)
    assertEquals(emptyList<Pair<Int, Boolean>>(), calls)
  }

  @Test
  fun `Given the finger has moved at most 8 horizontally, then it is still waiting`() {
    val start = center(4)
    val tracker = tracker()

    assertEquals(SwipeSelectTracker.Progress.WAITING, tracker.track(start, start + Offset(8f, 0f)))
    assertFalse(tracker.isSwiping)
    assertEquals(emptyList<Pair<Int, Boolean>>(), calls)
  }

  @Test
  fun `Given a tile that cannot be selected, when swiping over it, then it is skipped`() {
    bannerAtZero = true
    val start = center(0)
    swipe(listOf(start, start + Offset(12f, 0f), center(1), center(2)))

    assertEquals(listOf(1 to true, 2 to true), calls)
  }

  @Test
  fun `Given the finger goes back over a tile, then it is not toggled again`() {
    val start = center(3)
    swipe(listOf(start, start + Offset(12f, 0f), center(4), center(3), center(4), center(5)))

    assertEquals(listOf(3 to true, 4 to true, 5 to true), calls)
  }
}
