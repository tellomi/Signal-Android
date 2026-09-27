/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.welcome

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.R
import org.signal.registration.screens.shared.TellomiCrossBorderConsent
import org.signal.registration.screens.shared.TellomiLegalConsent

/**
 * Tellomi：开屏轮播（owner 2026-09-27；`docs/brand/README.md`「开屏轮播」）：四张 Open Doodles 按顺序，每张停 3 秒自动翻、循环；
 * 能左右滑，按住暂停、松手 3 秒后继续；系统关了动画（动画时长缩放 = 0）就不自动翻，只能手动滑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiWelcomeCarouselTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Before
  fun setup() {
    TellomiLegalConsent.acceptFirstLaunchNotice(context)
    TellomiCrossBorderConsent.recordAgreement(context)
  }

  @Test
  fun `four illustrations in order - swinging, selfie, loving, float`() {
    val expected = listOf(R.drawable.tellomi_welcome_swinging, R.drawable.tellomi_welcome_selfie, R.drawable.tellomi_welcome_loving, R.drawable.tellomi_welcome_float)
    assert(TellomiWelcomeCarousel.ILLUSTRATIONS == expected) { "Unexpected illustrations: ${TellomiWelcomeCarousel.ILLUSTRATIONS}" }
  }

  @Test
  fun `each illustration stays 3 seconds and the page turn takes about 350 ms`() {
    assert(TellomiWelcomeCarousel.INTERVAL_MS == 3_000L) { "Interval is ${TellomiWelcomeCarousel.INTERVAL_MS}" }
    assert(TellomiWelcomeCarousel.SCROLL_MS == 350) { "Scroll is ${TellomiWelcomeCarousel.SCROLL_MS}" }
  }

  @Test
  fun `auto-advances every 3 seconds and loops back to the first`() {
    setWelcomeScreen()

    assertPage(0)
    for (next in listOf(1, 2, 3, 0)) {
      advanceOnePage()
      assertPage(next)
    }
  }

  @Test
  fun `touching pauses auto-advance, and it resumes 3 seconds after release`() {
    setWelcomeScreen()

    composeTestRule.onNodeWithTag(TellomiWelcomeCarousel.TEST_TAG).performTouchInput { down(center) }
    composeTestRule.mainClock.advanceTimeBy(10_000)
    assertPage(0)

    composeTestRule.onNodeWithTag(TellomiWelcomeCarousel.TEST_TAG).performTouchInput { up() }
    advanceOnePage()
    assertPage(1)
  }

  @Test
  fun `with animations turned off it does not auto-advance, but swiping still works`() {
    Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    setWelcomeScreen()

    composeTestRule.mainClock.advanceTimeBy(10_000)
    assertPage(0)

    composeTestRule.mainClock.autoAdvance = true
    composeTestRule.onNodeWithTag(TellomiWelcomeCarousel.TEST_TAG).performTouchInput { swipeLeft() }
    composeTestRule.waitForIdle()
    assertPage(1)
  }

  @Test
  fun `the earlier welcome illustrations are gone`() {
    val left = listOf("welcome", "tellomi_welcome_illustration").filter { context.resources.getIdentifier(it, "drawable", context.packageName) != 0 }
    assert(left.isEmpty()) { "Old welcome illustrations still in the resources: $left" }
  }

  private fun setWelcomeScreen() {
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(showRestoreOrTransfer = true), onEvent = {})
      }
    }
    composeTestRule.mainClock.advanceTimeByFrame()
  }

  /** 停 3 秒 + 翻页动画 350 毫秒，再多给几帧。 */
  private fun advanceOnePage() {
    composeTestRule.mainClock.advanceTimeBy(3_000 + 350 + 100)
  }

  private fun assertPage(index: Int) {
    composeTestRule.onNodeWithTag(TellomiWelcomeCarousel.pageTestTag(index)).assertIsDisplayed()
  }
}
