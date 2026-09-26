/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.albumcarousel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tellomi/tellomi#1257：横滑相册几何（需求 C-2…C-6、C-9 与判据 1–3）。这里 1 px = 1 dp，数字可以直接对需求原文。
 */
class AlbumCarouselGeometryTest {

  @Test
  fun `row height is screen width times 0_6 for the three reference phones`() {
    assertEquals(241.2f, AlbumCarouselGeometry.rowHeightDp(402f, 874f, false), 0.001f)
    assertEquals(264f, AlbumCarouselGeometry.rowHeightDp(440f, 956f, false), 0.001f)
    assertEquals(225f, AlbumCarouselGeometry.rowHeightDp(375f, 667f, false), 0.001f)
  }

  @Test
  fun `row height is clamped to 220 and 300`() {
    assertEquals(220f, AlbumCarouselGeometry.rowHeightDp(320f, 640f, false), 0.001f)
    assertEquals(300f, AlbumCarouselGeometry.rowHeightDp(800f, 1280f, false), 0.001f)
  }

  @Test
  fun `landscape and tablets also stay under 40 percent of screen height`() {
    assertEquals(160.8f, AlbumCarouselGeometry.rowHeightDp(874f, 402f, true), 0.001f)
    assertEquals(300f, AlbumCarouselGeometry.rowHeightDp(1024f, 1366f, true), 0.001f)
  }

  @Test
  fun `each item keeps its aspect ratio within 9 by 16 and the peek limit`() {
    val layout = incoming402(listOf(3f / 4f, 16f / 9f, 1f / 3f, 1f))

    // 3:4 → 241 × 0.75；16:9 夹到 402 − 16 − 8 − 48 = 330；1:3 夹到 241 × 9/16；1:1 原样
    assertArrayEquals(intArrayOf(181, 330, 136, 241), layout.itemWidths)
  }

  @Test
  fun `unknown dimensions are laid out as squares`() {
    assertEquals(1f, AlbumCarouselGeometry.aspectRatio(0, 0), 0f)
    assertEquals(0.75f, AlbumCarouselGeometry.aspectRatio(3, 4), 0f)
  }

  @Test
  fun `a scrollable album starts at the start inset and ends 16 from the edge`() {
    val layout = incoming402(List(5) { 3f / 4f })

    assertTrue(layout.isScrollable)
    assertArrayEquals(intArrayOf(16, 205, 394, 583, 772), layout.itemLefts)
    assertEquals(16 + 5 * 181 + 4 * 8 + 16, layout.contentWidth)
    assertEquals(layout.contentWidth - 402, layout.maxScroll)

    val lastRightAtEnd = layout.itemLefts.last() + layout.itemWidths.last() - layout.maxScroll
    assertEquals(402 - 16, lastRightAtEnd)
  }

  @Test
  fun `snap offsets align an item's left edge with the start inset and end at max scroll`() {
    val layout = incoming402(List(5) { 3f / 4f })

    assertArrayEquals(intArrayOf(0, 189, 378, 567), layout.snapOffsets)
    assertEquals(567, layout.maxScroll)
  }

  @Test
  fun `at every snap position the next item peeks at least 48`() {
    for (count in listOf(2, 5, 12, 32)) {
      val layout = incoming402(List(count) { i -> if (i % 3 == 0) 16f / 9f else 3f / 4f })
      for (i in 0 until count - 1) {
        val offset = layout.snapOffsetForItem(i)
        val nextLeftOnScreen = layout.itemLefts[i + 1] - offset
        assertTrue("count=$count item=$i next=$nextLeftOnScreen", nextLeftOnScreen <= 402 - 48)
      }
    }
  }

  @Test
  fun `every item of a 32 item album can be scrolled fully on screen`() {
    val layout = incoming402(List(32) { 3f / 4f })
    for (i in 0 until 32) {
      val offset = layout.snapOffsetForItem(i)
      val left = layout.itemLefts[i] - offset
      assertTrue(left >= 0)
      assertTrue(left + layout.itemWidths[i] <= 402)
    }
  }

  @Test
  fun `outgoing album that fits is right aligned and not scrollable`() {
    // 两张 9:16 竖图：136 + 8 + 136 = 280，自己气泡列起点 48 + 280 + 16 ≤ 402
    val layout = AlbumCarouselGeometry.layout(402, 48, 16, 8, 48, 241, listOf(9f / 16f, 9f / 16f), alignEndWhenFits = true)

    assertFalse(layout.isScrollable)
    assertEquals(402 - 16 - 280, layout.itemLefts[0])
    assertEquals(402 - 16, layout.itemLefts[1] + layout.itemWidths[1])
    assertArrayEquals(intArrayOf(0), layout.snapOffsets)
  }

  @Test
  fun `incoming album that fits stays at the start inset`() {
    val layout = AlbumCarouselGeometry.layout(402, 16, 16, 8, 48, 241, listOf(9f / 16f, 9f / 16f), alignEndWhenFits = false)

    assertFalse(layout.isScrollable)
    assertEquals(16, layout.itemLefts[0])
  }

  @Test
  fun `outgoing album that does not fit starts at the own bubble column`() {
    val layout = AlbumCarouselGeometry.layout(402, 48, 16, 8, 48, 241, listOf(3f / 4f, 3f / 4f), alignEndWhenFits = true)

    assertTrue(layout.isScrollable)
    assertEquals(48, layout.itemLefts[0])
  }

  @Test
  fun `slow release stops at the nearest snap`() {
    val layout = incoming402(List(5) { 3f / 4f })

    assertEquals(189, layout.targetSnapOffset(currentOffset = 100, projectedOffset = 150, velocitySign = 0))
    assertEquals(0, layout.targetSnapOffset(currentOffset = 80, projectedOffset = 80, velocitySign = 0))
  }

  @Test
  fun `a fling always moves at least one snap in its direction`() {
    val layout = incoming402(List(5) { 3f / 4f })

    assertEquals(378, layout.targetSnapOffset(currentOffset = 190, projectedOffset = 195, velocitySign = 1))
    assertEquals(189, layout.targetSnapOffset(currentOffset = 200, projectedOffset = 198, velocitySign = -1))
  }

  @Test
  fun `a fast fling can pass several items`() {
    val layout = incoming402(List(5) { 3f / 4f })

    assertEquals(567, layout.targetSnapOffset(currentOffset = 10, projectedOffset = 700, velocitySign = 1))
    assertEquals(0, layout.targetSnapOffset(currentOffset = 560, projectedOffset = -300, velocitySign = -1))
  }

  @Test
  fun `closing the viewer on an off screen item scrolls to its snap`() {
    val layout = incoming402(List(5) { 3f / 4f })

    assertEquals(567, layout.offsetRevealingItem(index = 3, currentOffset = 0))
    assertEquals(0, layout.offsetRevealingItem(index = 0, currentOffset = 378))
  }

  @Test
  fun `closing the viewer on a fully visible item does not move the album`() {
    val layout = incoming402(List(5) { 3f / 4f })

    // 偏移 189 时第 2 张（下标 1）左边在 16、右边在 197：整张在屏幕里
    assertEquals(189, layout.offsetRevealingItem(index = 1, currentOffset = 189))
    assertEquals(0, layout.offsetRevealingItem(index = 1, currentOffset = 0))
  }

  @Test
  fun `item index at offset follows the snaps`() {
    val layout = incoming402(List(5) { 3f / 4f })

    assertEquals(0, layout.itemIndexAtOffset(0))
    assertEquals(1, layout.itemIndexAtOffset(189))
    assertEquals(2, layout.itemIndexAtOffset(370))
  }

  private fun incoming402(aspects: List<Float>): AlbumCarouselGeometry.Layout {
    return AlbumCarouselGeometry.layout(
      viewportWidth = 402,
      startInset = 16,
      endMargin = 16,
      spacing = 8,
      minNextPeek = 48,
      rowHeight = 241,
      aspectRatios = aspects,
      alignEndWhenFits = false
    )
  }
}
