/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.test.TestTags
import kotlin.math.roundToInt

/**
 * Tellomi（tellomi/tellomi#1115）：附件 Sheet 的外壳与底部 dock，照 Telegram 的附件菜单（Android `ChatAttachAlert`、
 * iOS `AttachmentContainer` / `AttachmentPanel`，只读机制，一行没搬）：
 *
 * - 从底部滑上来，停在「收起」：Sheet 高度是全屏的 73%（同 iOS 端；Telegram iOS 顶边低 0.2488），上面露出聊天、
 *   压暗 20%（Telegram Android 的 dim）。
 * - 顶栏往上拖、或者网格滚到顶再往上滑 → 展开到全屏；往下拖过收起位置 0.8 cm 或往下甩 → 关闭（Telegram Android
 *   下拉关闭的距离）；点压暗的聊天也关闭。选了东西时不直接关：弹回收起位置，交给 onDismissRequest（上游「放弃所选媒体？」）。
 * - dock（Telegram Android：底部 70dp 一条、胶囊内缩 7dp、圆角是高度的一半；格子图标 24dp、字 11sp）：当前页（相册）那格
 *   垫一块淡色胶囊；置灰的格子读屏报提示语，点了只提示。
 */
@Stable
internal class AttachmentSheetState {

  /** Sheet 顶边离容器顶部的距离（px）。量出容器大小之前是 NaN，这时 Sheet 在屏幕外。 */
  var top by mutableFloatStateOf(Float.NaN)
    private set

  var containerHeight by mutableFloatStateOf(0f)
    private set

  /** 全屏时的顶边：状态栏下面。 */
  var expandedTop by mutableFloatStateOf(0f)
    private set

  private val mutex = MutatorMutex()

  val collapsedTop: Float
    get() = expandedTop + (containerHeight - expandedTop) * (1f - COLLAPSED_FRACTION)

  val isExpanded: Boolean
    get() = !top.isNaN() && top <= expandedTop + 0.5f

  /** 拖到一半（不在全屏、也不在收起）：松手时由 Sheet 收尾。 */
  val isBetweenAnchors: Boolean
    get() = !top.isNaN() && top > expandedTop + 0.5f && kotlin.math.abs(top - collapsedTop) > 0.5f

  /** 跟手：返回 Sheet 实际挪动的距离（到了全屏 / 屏幕底就不再挪）。 */
  fun dragBy(delta: Float): Float {
    if (top.isNaN()) return 0f
    val old = top
    top = (top + delta).coerceIn(expandedTop, containerHeight)
    return top - old
  }

  suspend fun expand() = animateTo(expandedTop)

  suspend fun collapse() = animateTo(collapsedTop)

  /** 手指按下时停掉还在跑的动画，免得动画和跟手抢着写 [top]。 */
  suspend fun stopAnimation() = mutex.mutate(MutatePriority.UserInput) { }

  internal suspend fun onContainerMeasured(height: Float, statusBarTop: Float) {
    val first = top.isNaN()
    val wasExpanded = isExpanded
    containerHeight = height
    expandedTop = statusBarTop
    when {
      // 第一次：从屏幕底下滑上来
      first -> {
        snapTo(height)
        animateTo(collapsedTop, EnterSpec)
      }
      wasExpanded -> snapTo(expandedTop)
      else -> snapTo(collapsedTop)
    }
  }

  private suspend fun snapTo(value: Float) = mutex.mutate { top = value }

  private suspend fun animateTo(target: Float, spec: AnimationSpec<Float> = SettleSpec) {
    mutex.mutate {
      animate(initialValue = top, targetValue = target, animationSpec = spec) { value, _ -> top = value }
    }
  }

  companion object {
    /** 收起时 Sheet 占全屏高度的比例（同 iOS 端）。 */
    const val COLLAPSED_FRACTION = 0.73f
    const val SCRIM_ALPHA = 0.2f
    private val EnterSpec: AnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    private val SettleSpec: AnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
  }
}

/** 从「+」打开时由流程提供；整屏的选图流程（上游原样）没有。 */
internal val LocalAttachmentSheetState = staticCompositionLocalOf<AttachmentSheetState?> { null }

private val DismissDragDistance = 50.dp // Telegram Android：下拉 0.8 cm 松手就关
private val FlingVelocity = 1000.dp // 每秒
private val SheetCornerRadius = 12.dp

/**
 * @param canDismissDirectly 没选东西：下拉 / 点聊天直接关；选了东西：先弹回收起，再交给 [onDismissRequest] 去确认。
 */
@Composable
internal fun AttachmentSheet(
  state: AttachmentSheetState,
  canDismissDirectly: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val statusBarTop = WindowInsets.statusBars.getTop(density).toFloat()
  val dismissDistance = with(density) { DismissDragDistance.toPx() }
  val flingVelocity = with(density) { FlingVelocity.toPx() }
  val currentCanDismissDirectly by rememberUpdatedState(canDismissDirectly)
  val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)

  val dismiss: suspend () -> Unit = {
    if (currentCanDismissDirectly) {
      currentOnDismissRequest()
    } else {
      currentOnDismissRequest()
      state.collapse()
    }
  }

  // 松手后停在哪：往下甩（已经在收起位置或更低）/ 拖过收起位置一截 → 关闭；往上甩 / 过了中线 → 全屏；否则收起。
  val settle: suspend (Float) -> Unit = { velocity ->
    val collapsed = state.collapsedTop
    val top = state.top
    when {
      velocity > flingVelocity -> if (top >= collapsed - 1f) dismiss() else state.collapse()
      velocity < -flingVelocity -> state.expand()
      top > collapsed + dismissDistance -> dismiss()
      top < (state.expandedTop + collapsed) / 2 -> state.expand()
      else -> state.collapse()
    }
  }

  // 网格和 Sheet 接力：往上滑先把 Sheet 推到全屏再滚网格；网格到顶还往下拉就拉 Sheet。
  val nestedScrollConnection = remember(state) {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        return if (available.y < 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dragBy(available.y)) else Offset.Zero
      }

      override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        return if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dragBy(available.y)) else Offset.Zero
      }

      override suspend fun onPreFling(available: Velocity): Velocity {
        if (!state.isBetweenAnchors) return Velocity.Zero
        settle(available.y)
        return available
      }

      override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!state.isBetweenAnchors) return Velocity.Zero
        settle(available.y)
        return available
      }
    }
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val height = constraints.maxHeight.toFloat()
    LaunchedEffect(height, statusBarTop) {
      state.onContainerMeasured(height, statusBarTop)
    }

    // 压暗的聊天：跟着 Sheet 露出来的多少淡入淡出；点它关闭
    Box(
      modifier = Modifier
        .fillMaxSize()
        .drawBehind {
          val top = if (state.top.isNaN()) size.height else state.top
          val shown = ((size.height - top) / (size.height - state.collapsedTop).coerceAtLeast(1f)).coerceIn(0f, 1f)
          drawRect(Color.Black.copy(alpha = AttachmentSheetState.SCRIM_ALPHA * shown))
        }
        .pointerInput(Unit) {
          detectTapGestures { scope.launch { dismiss() } }
        }
        .testTag(TestTags.ATTACHMENT_SHEET_SCRIM)
    )

    Box(
      modifier = Modifier
        .offset { IntOffset(0, sheetTop(state, constraints.maxHeight)) }
        .layout { measurable, layoutConstraints ->
          val sheetHeight = (layoutConstraints.maxHeight - sheetTop(state, layoutConstraints.maxHeight)).coerceAtLeast(0)
          val placeable = measurable.measure(layoutConstraints.copy(minHeight = sheetHeight, maxHeight = sheetHeight))
          layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        .nestedScroll(nestedScrollConnection)
        .draggable(
          orientation = Orientation.Vertical,
          state = rememberDraggableState { delta -> state.dragBy(delta) },
          onDragStarted = { state.stopAnimation() },
          onDragStopped = { velocity -> settle(velocity) }
        )
        .clip(RoundedCornerShape(topStart = SheetCornerRadius, topEnd = SheetCornerRadius))
        .background(MaterialTheme.colorScheme.surface)
        .testTag(TestTags.ATTACHMENT_SHEET)
    ) {
      content()

      // 抓手（Telegram Android：36 × 4dp）
      Box(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = 6.dp)
          .size(width = 36.dp, height = 4.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
      )
    }
  }
}

private fun sheetTop(state: AttachmentSheetState, maxHeight: Int): Int {
  val top = state.top
  return if (top.isNaN()) maxHeight else top.roundToInt().coerceIn(0, maxHeight)
}

internal object DockMetrics {
  /** dock 连同上下的留白一共这么高；网格底部留出这么多，最后一行能滚到 dock 上面。 */
  val stripHeight = 70.dp
  val capsuleInset = 7.dp
  val sideMargin = 12.dp
  val iconSize = 24.dp
  const val DISABLED_ALPHA = 0.38f // 同附件键盘里置灰的「位置」（tellomi/tellomi#1235）
}

/**
 * 底部 dock：一排格子，当前页（相册）那格选中。置灰的格子（[MediaSendFlowActivityContract.DockEntry.comingSoonMessage]）
 * 照样能点（点了只提示），读屏把提示语当状态念出来。
 */
@Composable
internal fun AttachmentDock(
  entries: List<MediaSendFlowActivityContract.DockEntry>,
  onClick: (MediaSendFlowActivityContract.DockEntry) -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(DockMetrics.stripHeight)
      .padding(horizontal = DockMetrics.sideMargin, vertical = DockMetrics.capsuleInset)
      .shadow(elevation = 6.dp, shape = CircleShape)
      .background(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape)
      .padding(horizontal = 4.dp, vertical = 4.dp)
      .selectableGroup()
      .testTag(TestTags.ATTACHMENT_DOCK)
  ) {
    for (entry in entries) {
      AttachmentDockButton(
        entry = entry,
        onClick = { onClick(entry) },
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
      )
    }
  }
}

@Composable
private fun AttachmentDockButton(
  entry: MediaSendFlowActivityContract.DockEntry,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val selected = entry.isCurrentPage
  val comingSoon = entry.comingSoonMessage?.let { stringResource(it) }
  val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = modifier
      .clip(CircleShape)
      .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
      .selectable(selected = selected, role = Role.Tab, onClick = onClick)
      .then(if (comingSoon != null) Modifier.semantics { stateDescription = comingSoon } else Modifier)
      .alpha(if (comingSoon != null) DockMetrics.DISABLED_ALPHA else 1f)
      .testTag(TestTags.attachmentDockEntry(entry.id))
  ) {
    Icon(
      painter = painterResource(entry.icon),
      contentDescription = null,
      tint = tint,
      modifier = Modifier.size(DockMetrics.iconSize)
    )
    Text(
      text = stringResource(entry.title),
      color = tint,
      fontSize = 11.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}
