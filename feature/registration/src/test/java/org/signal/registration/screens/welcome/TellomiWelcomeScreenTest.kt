/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.welcome

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import org.signal.registration.test.TestTags

/**
 * Tellomi：开屏（欢迎页，owner 2026-09-27；`docs/product/BRAND.md`「开屏」）：插画下面只有字标 Tellomi，不放句子，
 * 不再放「条款与隐私政策」链接（首次打开的隐私提示和号码页的勾选里都有）；「继续」和「换了新手机？」照旧。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiWelcomeScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Before
  fun setup() {
    // 首次启动提示和跨境告知另有用例（TellomiLegalConsentTest）；这里只看欢迎页本身。
    TellomiLegalConsent.acceptFirstLaunchNotice(context)
    TellomiCrossBorderConsent.recordAgreement(context)
  }

  @Test
  fun `the headline is the wordmark only, described as Tellomi`() {
    setWelcomeScreen()

    composeTestRule.onNodeWithTag(TestTags.WELCOME_HEADLINE)
      .assertIsDisplayed()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Tellomi")))
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_take_privacy_with_you_be_yourself_in_every_message)).assertDoesNotExist()
  }

  @Test
  fun `there is no terms and privacy link, and continue and restore stay`() {
    setWelcomeScreen()

    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_terms_and_privacy)).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).assertIsDisplayed()
  }

  @Config(qualifiers = "w1280dp-h800dp-xhdpi")
  @Test
  fun `the tablet layout has no terms and privacy link and no headline sentence either`() {
    setWelcomeScreen()

    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_terms_and_privacy)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_take_privacy_with_you_be_yourself_in_every_message)).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_HEADLINE)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Tellomi")))
  }

  private fun setWelcomeScreen() {
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(showRestoreOrTransfer = true), onEvent = {})
      }
    }
  }
}
