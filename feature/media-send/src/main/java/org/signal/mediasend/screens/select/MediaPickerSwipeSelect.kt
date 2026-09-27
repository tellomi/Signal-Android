/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Tellomi（tellomi/tellomi#1261 P-8）：横着滑过格子连续多选，不用长按。
 *
 * 机制照 Telegram iOS `MediaPickerGridSelectionGesture`（MediaPickerScreen.swift）：起手在左边 44dp 以内不算（留给返回手势）；
 * 竖向先动超过 5dp 就放弃、让给滚动；横向先动超过 8dp 才开始，由这一刻手指下那格决定方向（没选 → 一路选上，已选 → 一路取消），
 * 起手那格和之后经过的格子都照它。Telegram Android 是长按后拖动（`RecyclerViewItemRangeSelector`），上游 Signal 的
 * [org.signal.core.ui.compose.list.dragToSelect] 已经是这种，照旧保留：按住超过长按时长还没动，就交给它，这里不再介入。
 * 独立实现，一行没搬（GPLv2）。
 */
@Composable
internal fun Modifier.swipeToSelect(
  gridState: LazyGridState,
  isSelected: (index: Int) -> Boolean?,
  setSelected: (index: Int, selected: Boolean) -> Unit
): Modifier {
  val currentIsSelected by rememberUpdatedState(isSelected)
  val currentSetSelected by rememberUpdatedState(setSelected)

  return pointerInput(gridState) {
    val leadingDeadZone = SwipeSelectTracker.LEADING_DEAD_ZONE.toPx()
    val horizontalStart = SwipeSelectTracker.HORIZONTAL_START.toPx()
    val verticalFail = SwipeSelectTracker.VERTICAL_FAIL.toPx()

    awaitEachGesture {
      // Initial：在格子（点按 / 长按）和网格滚动之前先看到；开始横滑之后把这串触摸吃掉，它们就都不会动作。
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      if (down.position.x <= leadingDeadZone) {
        return@awaitEachGesture
      }

      val tracker = SwipeSelectTracker(
        horizontalStart = horizontalStart,
        verticalFail = verticalFail,
        indexAt = { position -> gridState.indexAt(position) },
        isSelected = { index -> currentIsSelected(index) },
        setSelected = { index, selected -> currentSetSelected(index, selected) }
      )

      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture

        if (!change.pressed) {
          if (tracker.isSwiping) {
            event.changes.forEach { it.consume() }
          }
          return@awaitEachGesture
        }

        if (!tracker.isSwiping) {
          if (event.changes.size > 1) {
            return@awaitEachGesture
          }
          if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
            return@awaitEachGesture
          }
        }

        when (tracker.track(start = down.position, position = change.position)) {
          SwipeSelectTracker.Progress.WAITING -> Unit
          SwipeSelectTracker.Progress.FAILED -> return@awaitEachGesture
          SwipeSelectTracker.Progress.SWIPING -> event.changes.forEach { it.consume() }
        }
      }
    }
  }
}

private fun LazyGridState.indexAt(position: Offset): Int? {
  return layoutInfo.visibleItemsInfo.firstOrNull { item ->
    position.x >= item.offset.x &&
      position.x < item.offset.x + item.size.width &&
      position.y >= item.offset.y &&
      position.y < item.offset.y + item.size.height
  }?.index
}

/**
 * 横滑多选的判定（不依赖 Compose，单测直接喂坐标）。一次手势一个实例。
 *
 * [isSelected] 返回 null 表示那一格不能选（比如受限访问横幅）。选中状态要等界面重组才更新，所以同一次滑动里每格只处理一次。
 */
internal class SwipeSelectTracker(
  private val horizontalStart: Float,
  private val verticalFail: Float,
  private val indexAt: (Offset) -> Int?,
  private val isSelected: (Int) -> Boolean?,
  private val setSelected: (Int, Boolean) -> Unit
) {

  enum class Progress {
    WAITING,
    FAILED,
    SWIPING
  }

  var isSwiping = false
    private set

  private var selecting = true
  private val handled = mutableSetOf<Int>()

  fun track(start: Offset, position: Offset): Progress {
    if (!isSwiping) {
      if (abs(position.y - start.y) > verticalFail) {
        return Progress.FAILED
      }
      if (abs(position.x - start.x) <= horizontalStart) {
        return Progress.WAITING
      }
      isSwiping = true
      val deciding = indexAt(position)?.takeIf { isSelected(it) != null } ?: indexAt(start)
      selecting = !(deciding?.let(isSelected) ?: false)
      indexAt(start)?.let { apply(it) }
    }
    indexAt(position)?.let { apply(it) }
    return Progress.SWIPING
  }

  private fun apply(index: Int) {
    if (index in handled) {
      return
    }
    val current = isSelected(index) ?: return
    handled += index
    if (current != selecting) {
      setSelected(index, selecting)
    }
  }

  companion object {
    val LEADING_DEAD_ZONE = 44.dp
    val HORIZONTAL_START = 8.dp
    val VERTICAL_FAIL = 5.dp
  }
}
