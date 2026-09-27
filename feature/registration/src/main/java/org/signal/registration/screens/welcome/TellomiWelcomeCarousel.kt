/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.welcome

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.signal.registration.R
import org.signal.registration.screens.attachDebugLogHelper

/**
 * Tellomi：开屏轮播（owner 2026-09-27；`docs/brand/README.md`「插画」「开屏轮播」）。
 *
 * - 四张 Open Doodles（CC0，纯黑白，深色在 drawable-night）：荡秋千 → 自拍 → 捧心 → 悬浮。
 * - 每张停 [INTERVAL_MS] 自动翻到下一张（翻页动画约 [SCROLL_MS]），第 4 张之后回到第 1 张；能左右滑，两个方向都循环。
 * - 手指按住（含拖动）时暂停，松手后重新计时，[INTERVAL_MS] 之后再翻。
 * - 系统「动画时长缩放」为 0（开发者选项 / 无障碍里的「移除动画」）时不自动翻，只能手动滑。
 * - 插画是装饰，读屏跳过（contentDescription = null），小圆点也不读；整屏由下面的字标读作「Tellomi」。
 *
 * Telegram 的开屏（IntroActivity + BottomPagesView）只有手动翻页、不自动播，这里的自动播是按 owner 的要求独立写的。
 */
object TellomiWelcomeCarousel {
  const val INTERVAL_MS = 3_000L
  const val SCROLL_MS = 350

  @DrawableRes
  val ILLUSTRATIONS: List<Int> = listOf(
    R.drawable.tellomi_welcome_swinging,
    R.drawable.tellomi_welcome_selfie,
    R.drawable.tellomi_welcome_loving,
    R.drawable.tellomi_welcome_float
  )

  const val TEST_TAG = "welcome_carousel"
  fun pageTestTag(index: Int) = "welcome_carousel_page_$index"

  /** 循环：虚拟页数取一个大数，从中间（且是 4 的倍数）开始，左右都能一直滑下去；真正显示第 `page % 4` 张。 */
  internal const val VIRTUAL_PAGE_COUNT = 10_000
}

@Composable
fun TellomiWelcomeCarousel(modifier: Modifier = Modifier) {
  val count = TellomiWelcomeCarousel.ILLUSTRATIONS.size
  val startPage = (TellomiWelcomeCarousel.VIRTUAL_PAGE_COUNT / 2).let { it - it % count }
  val pagerState = rememberPagerState(initialPage = startPage) { TellomiWelcomeCarousel.VIRTUAL_PAGE_COUNT }
  val autoAdvance = rememberSystemAnimationsEnabled()
  var touching by remember { mutableStateOf(false) }

  // 每次换页、按下、松开都重新计时：停满 INTERVAL_MS 才翻下一张。
  LaunchedEffect(autoAdvance, touching, pagerState.settledPage) {
    if (!autoAdvance || touching) {
      return@LaunchedEffect
    }
    delay(TellomiWelcomeCarousel.INTERVAL_MS)
    pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = tween(TellomiWelcomeCarousel.SCROLL_MS))
  }

  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    HorizontalPager(
      state = pagerState,
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .testTag(TellomiWelcomeCarousel.TEST_TAG)
        .pointerInput(Unit) {
          // 只看手指在不在屏上，不消费事件：滑动照常交给 pager。
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touching = true
            do {
              val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            touching = false
          }
        }
    ) { page ->
      val index = page % count
      Box(
        modifier = Modifier
          .fillMaxSize()
          // 个别原图有几笔画出了 viewBox（比如悬浮那张右手），不裁的话会压到相邻一页的边上。
          .clipToBounds()
          .testTag(TellomiWelcomeCarousel.pageTestTag(index)),
        contentAlignment = Alignment.Center
      ) {
        Image(
          painter = painterResource(TellomiWelcomeCarousel.ILLUSTRATIONS[index]),
          contentDescription = null,
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxSize()
            .attachDebugLogHelper()
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    PageDots(count = count, current = pagerState.currentPage % count)
  }
}

/** 插画下面一排小圆点：当前页墨色，其余次级灰。装饰，读屏不读。 */
@Composable
private fun PageDots(count: Int, current: Int) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
      .padding(vertical = 4.dp)
      .clearAndSetSemantics {}
  ) {
    repeat(count) { index ->
      Box(
        modifier = Modifier
          .size(6.dp)
          .background(
            color = if (index == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
            shape = CircleShape
          )
      )
    }
  }
}

/** 系统「动画时长缩放」为 0 = 用户关了动画：不自动播。读一次就够（改设置会重建界面）。 */
@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
  val context = LocalContext.current
  return remember(context) {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
  }
}
