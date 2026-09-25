/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.albumcarousel

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tellomi：多图横滑相册的几何（tellomi/tellomi#1257，需求 docs/product/specs/media-album-forward-picker.md C-2…C-6）。
 *
 * 一行横滑、整组同一个高度、每张按原比例；滑动区是整屏宽，静止时第一张的左边对齐「起点」，
 * 松手停在「某一张的左边对齐起点」，滑到底最后一张右边对齐右边距。
 *
 * 纯计算、没有 Android 依赖：坐标一律是 px，「内容坐标」的 0 是屏幕的起始边（LTR 为左边）。
 *
 * 放在 core/ui：选图面板「只看已选」的排序行（media-send，#1261 P-3）在用。聊天里的横滑已撤回、改回 Signal 原来的宫格
 * （owner 2026-09-25，#1257）；以后做微博 / Threads / X 式的动态时再用这套。
 */
object AlbumCarouselGeometry {

  /** C-2：行高 = 屏宽 × 0.6，夹在 [220, 300] dp；横屏与平板另外不超过屏高 × 0.4。 */
  const val ROW_HEIGHT_OF_SCREEN_WIDTH = 0.6f
  const val MIN_ROW_HEIGHT_DP = 220f
  const val MAX_ROW_HEIGHT_DP = 300f
  const val MAX_ROW_HEIGHT_OF_SCREEN_HEIGHT = 0.4f

  /** C-3：每张最窄 = 行高 × 9/16（更竖的图居中裁切）。 */
  const val MIN_ITEM_ASPECT_RATIO = 9f / 16f

  /** 原图没带宽高时按正方形排。 */
  const val UNKNOWN_ASPECT_RATIO = 1f

  /** C-4：卡片间距。 */
  const val ITEM_SPACING_DP = 8f

  /** C-4：卡片圆角（同 app 的 message_corner_radius；选图面板的预览在 media-send 里拿不到 app 的资源）。 */
  const val ITEM_CORNER_RADIUS_DP = 18f

  /** C-3：最宽的一张也给下一张留出的宽度。 */
  const val NEXT_ITEM_MIN_PEEK_DP = 48f

  /** C-5：滑到底时最后一张右边与屏幕边的距离。 */
  const val END_MARGIN_DP = 16f

  @JvmStatic
  fun rowHeightDp(screenWidthDp: Float, screenHeightDp: Float, capByScreenHeight: Boolean): Float {
    val byWidth = (screenWidthDp * ROW_HEIGHT_OF_SCREEN_WIDTH).coerceIn(MIN_ROW_HEIGHT_DP, MAX_ROW_HEIGHT_DP)
    return if (capByScreenHeight) min(byWidth, screenHeightDp * MAX_ROW_HEIGHT_OF_SCREEN_HEIGHT) else byWidth
  }

  /** 宽 / 高；拿不到尺寸时返回 [UNKNOWN_ASPECT_RATIO]。 */
  @JvmStatic
  fun aspectRatio(width: Int, height: Int): Float {
    return if (width > 0 && height > 0) width.toFloat() / height.toFloat() else UNKNOWN_ASPECT_RATIO
  }

  /**
   * @param viewportWidth  滑动区宽度（整屏宽）
   * @param startInset     起点：静止时第一张左边到屏幕起始边的距离
   * @param endMargin      滑到底时最后一张右边到屏幕末端的距离
   * @param spacing        卡片间距
   * @param minNextPeek    最宽的一张也要给下一张留出的宽度
   * @param rowHeight      行高
   * @param aspectRatios   每张的宽 / 高
   * @param alignEndWhenFits 放得下、不用滑时整组贴末端（自己发的相册靠右，C-5）
   */
  @JvmStatic
  fun layout(
    viewportWidth: Int,
    startInset: Int,
    endMargin: Int,
    spacing: Int,
    minNextPeek: Int,
    rowHeight: Int,
    aspectRatios: List<Float>,
    alignEndWhenFits: Boolean
  ): Layout {
    require(aspectRatios.isNotEmpty()) { "Album needs at least one item." }

    val minItemWidth = max(1, (rowHeight * MIN_ITEM_ASPECT_RATIO).roundToInt())
    val maxItemWidth = max(minItemWidth, viewportWidth - startInset - spacing - minNextPeek)

    val widths = IntArray(aspectRatios.size) { i ->
      (rowHeight * aspectRatios[i]).roundToInt().coerceIn(minItemWidth, maxItemWidth)
    }

    val itemsSpan = widths.sum() + spacing * (widths.size - 1)
    val scrollable = startInset + itemsSpan + endMargin > viewportWidth

    val firstLeft = when {
      scrollable -> startInset
      alignEndWhenFits -> viewportWidth - endMargin - itemsSpan
      else -> startInset
    }

    val lefts = IntArray(widths.size)
    var x = firstLeft
    for (i in widths.indices) {
      lefts[i] = x
      x += widths[i] + spacing
    }

    val contentWidth = if (scrollable) startInset + itemsSpan + endMargin else viewportWidth
    val maxScroll = max(0, contentWidth - viewportWidth)

    val snaps = if (scrollable) {
      lefts.map { min(it - startInset, maxScroll) }.distinct().sorted().toIntArray()
    } else {
      intArrayOf(0)
    }

    return Layout(
      viewportWidth = viewportWidth,
      startInset = startInset,
      rowHeight = rowHeight,
      itemLefts = lefts,
      itemWidths = widths,
      contentWidth = contentWidth,
      isScrollable = scrollable,
      snapOffsets = snaps
    )
  }

  class Layout(
    val viewportWidth: Int,
    val startInset: Int,
    val rowHeight: Int,
    /** 每张在内容坐标里的左边。 */
    val itemLefts: IntArray,
    val itemWidths: IntArray,
    /** 内容总宽（含起点与末端边距）；放得下时等于 [viewportWidth]。 */
    val contentWidth: Int,
    val isScrollable: Boolean,
    /** 升序的吸附位置（滚动偏移）：某一张的左边对齐起点，最后一个是滑到底。 */
    val snapOffsets: IntArray
  ) {
    val itemCount: Int get() = itemWidths.size

    val maxScroll: Int get() = max(0, contentWidth - viewportWidth)

    /** 让第 [index] 张静止时左边对齐起点的偏移（滑到底为止）。 */
    fun snapOffsetForItem(index: Int): Int {
      return if (isScrollable) min(itemLefts[index] - startInset, maxScroll) else 0
    }

    /**
     * C-9：关查看器前先把这一张滚到完整露出。已经整张在屏幕里就不动，否则停到它的吸附位
     * （吸附位上每一张都整张可见：最宽也只到「屏宽 − 起点 − 间距 − 48」）。
     */
    fun offsetRevealingItem(index: Int, currentOffset: Int): Int {
      if (!isScrollable) return 0
      val left = itemLefts[index] - currentOffset
      val right = left + itemWidths[index]
      return if (left >= 0 && right <= viewportWidth) currentOffset.coerceIn(0, maxScroll) else snapOffsetForItem(index)
    }

    /**
     * C-6：松手后停在哪。慢慢松手（[velocitySign] 为 0）停在离 [projectedOffset] 最近的吸附位；
     * 甩的时候按甩出去的落点找最近的吸附位（可以一次越过几张），但至少朝甩的方向走一格。
     */
    fun targetSnapOffset(currentOffset: Int, projectedOffset: Int, velocitySign: Int): Int {
      if (!isScrollable) return 0

      val clamped = projectedOffset.coerceIn(0, maxScroll)
      var best = snapOffsets[0]
      for (s in snapOffsets) {
        if (abs(s - clamped) < abs(best - clamped)) best = s
      }

      if (velocitySign > 0 && best <= currentOffset) {
        best = snapOffsets.firstOrNull { it > currentOffset } ?: snapOffsets.last()
      } else if (velocitySign < 0 && best >= currentOffset) {
        best = snapOffsets.lastOrNull { it < currentOffset } ?: snapOffsets.first()
      }

      return best
    }

    /** 当前偏移下左边最靠近起点的那一张（读屏「第几张」与自定义动作用）。 */
    fun itemIndexAtOffset(offset: Int): Int {
      var bestIndex = 0
      var bestDistance = Int.MAX_VALUE
      for (i in itemLefts.indices) {
        val d = abs(snapOffsetForItem(i) - offset)
        if (d < bestDistance) {
          bestDistance = d
          bestIndex = i
        }
      }
      return bestIndex
    }
  }
}
