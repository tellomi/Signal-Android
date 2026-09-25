/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.ui.albumcarousel.AlbumCarouselGeometry

/**
 * Tellomi（tellomi/tellomi#1261 P-3）：「只看已选」那一行的排序与自动滚的计算。
 * 一行 400 宽、起点 16、间距 8、行高 240：三张 4:3（各 320）要滑，第 0 张 16…336、第 1 张 344…664、第 2 张 672…992。
 */
class PreviewReorderTest {

  private val layout = AlbumCarouselGeometry.layout(
    viewportWidth = 400,
    startInset = 16,
    endMargin = 16,
    spacing = 8,
    minNextPeek = 48,
    rowHeight = 240,
    aspectRatios = listOf(4f / 3f, 4f / 3f, 4f / 3f),
    alignEndWhenFits = true
  )

  @Test
  fun `indexAt finds the card under the finger and nothing in the gaps`() {
    assertEquals(listOf(16, 344, 672), layout.itemLefts.toList())
    assertEquals(0, PreviewReorder.indexAt(layout, 16f))
    assertEquals(0, PreviewReorder.indexAt(layout, 335f))
    assertNull(PreviewReorder.indexAt(layout, 340f))
    assertEquals(1, PreviewReorder.indexAt(layout, 344f))
    assertEquals(2, PreviewReorder.indexAt(layout, 991f))
    assertNull(PreviewReorder.indexAt(layout, 8f))
  }

  @Test
  fun `targetIndex counts the other cards whose centre is left of the dragged one`() {
    // 拿着第 0 张：中心还没过第 1 张的中心（504）就不动，过了排到第 1，再过第 2 张的中心（832）排到最后。
    assertEquals(0, PreviewReorder.targetIndex(layout, draggedIndex = 0, draggedCenterX = 500f))
    assertEquals(1, PreviewReorder.targetIndex(layout, draggedIndex = 0, draggedCenterX = 505f))
    assertEquals(2, PreviewReorder.targetIndex(layout, draggedIndex = 0, draggedCenterX = 833f))
    // 拿着最后一张往回拖。
    assertEquals(0, PreviewReorder.targetIndex(layout, draggedIndex = 2, draggedCenterX = 100f))
    assertEquals(1, PreviewReorder.targetIndex(layout, draggedIndex = 2, draggedCenterX = 400f))
  }

  @Test
  fun `autoScrollStep scrolls only near the ends, faster closer to the edge`() {
    assertEquals(0f, PreviewReorder.autoScrollStep(200f, 400f, zone = 56f, maxSpeed = 12f), 0.001f)
    assertEquals(12f, PreviewReorder.autoScrollStep(400f, 400f, zone = 56f, maxSpeed = 12f), 0.001f)
    assertEquals(6f, PreviewReorder.autoScrollStep(372f, 400f, zone = 56f, maxSpeed = 12f), 0.001f)
    assertEquals(-12f, PreviewReorder.autoScrollStep(0f, 400f, zone = 56f, maxSpeed = 12f), 0.001f)
    assertTrue(PreviewReorder.autoScrollStep(30f, 400f, zone = 56f, maxSpeed = 12f) < 0f)
  }
}
