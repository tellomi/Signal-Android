/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.shared

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.R
import org.signal.registration.screens.phonenumber.PhoneNumberEntryScreenEvents
import org.signal.registration.screens.phonenumber.PhoneNumberEntryState
import org.signal.registration.screens.phonenumber.PhoneNumberScreen
import org.signal.registration.screens.welcome.WelcomeScreen
import org.signal.registration.screens.welcome.WelcomeScreenEvents
import org.signal.registration.screens.welcome.WelcomeScreenState
import org.signal.registration.test.TestTags

/**
 * Tellomi：注册同意（tellomi/tellomi#1211；ADR-0038 · ADR-0051 §E）。
 * 每条用例都从空的本机记录开始（Robolectric 每条用例一个新的 Application）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLegalConsentTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** 点了「下一步」、号码也合法：视图模型已经要出「号码是否正确」的确认框了。 */
  private val confirmingState = PhoneNumberEntryState(
    countryCode = "86",
    nationalNumber = "13800138000",
    formattedNumber = "138 0013 8000",
    isNumberPossible = true,
    dialogs = PhoneNumberEntryState.Dialogs(confirmNumber = true)
  )

  @Test
  fun `consent checkbox is unchecked by default`() {
    setPhoneNumberScreen(PhoneNumberEntryState(countryCode = "86"))

    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOff()
    assert(!TellomiLegalConsent.hasAgreedToTerms(context))
  }

  @Test
  fun `without consent, the consent dialog replaces the confirm number dialog`() {
    setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__dialog_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertDoesNotExist()
  }

  @Test
  fun `disagree cancels like edit number and records nothing`() {
    val events = setPhoneNumberScreen(confirmingState)

    // 点的必须是同意框的「不同意」——确认号码框的「修改号码」也发同一个事件
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__dialog_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_DISMISS_BUTTON).performClick()

    assert(events == listOf<PhoneNumberEntryScreenEvents>(PhoneNumberEntryScreenEvents.PhoneNumberCancelled)) { "Unexpected events: $events" }
    assert(!TellomiLegalConsent.hasAgreedToTerms(context))
    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOff()
  }

  @Test
  fun `agree checks the box, records consent, then shows the confirm number dialog`() {
    // 这条只测协议勾选；跨境告知另有用例
    TellomiCrossBorderConsent.recordAgreement(context)
    val events = setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_CONFIRM_BUTTON).performClick()

    // 先让用户看见勾打上：确认框要停一下才出来
    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOn()
    assert(TellomiLegalConsent.hasAgreedToTerms(context))
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertDoesNotExist()

    composeTestRule.mainClock.advanceTimeBy(400)

    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertIsDisplayed()
    assert(events.isEmpty()) { "Agreeing must not submit the number by itself, but got $events" }
  }

  @Test
  fun `with earlier consent, the confirm number dialog shows directly`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    TellomiCrossBorderConsent.recordAgreement(context)

    setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOn()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__dialog_title)).assertDoesNotExist()
  }

  @Test
  fun `after the terms, the cross-border notice comes before the confirm number dialog`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)

    setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertDoesNotExist()
  }

  @Test
  fun `agreeing to the cross-border notice records it and shows the confirm number dialog`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    val events = setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.AGREE_TEST_TAG).performClick()

    assert(TellomiCrossBorderConsent.hasAgreed(context))
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__title)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertIsDisplayed()
    assert(events.isEmpty()) { "Agreeing must not submit the number by itself, but got $events" }
  }

  @Test
  fun `disagreeing keeps the cross-border notice open with a hint and records nothing`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    val events = setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.DISAGREE_TEST_TAG).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__disagree_hint)).assertIsDisplayed()
    assert(!TellomiCrossBorderConsent.hasAgreed(context))
    assert(events.isEmpty()) { "Disagreeing must not send or cancel anything, but got $events" }
  }

  @Test
  fun `a new cross-border notice version asks again`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    // 模拟「同意过的是旧版本」
    TellomiLegalConsent.prefs(context).edit().putString("cross_border.version", "0.0.9").commit()
    assert(!TellomiCrossBorderConsent.hasAgreed(context))

    setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__title)).assertIsDisplayed()
  }

  @Test
  fun `unchecking the box withdraws consent`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    setPhoneNumberScreen(PhoneNumberEntryState(countryCode = "86"))

    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).performClick()

    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOff()
    assert(!TellomiLegalConsent.hasAgreedToTerms(context))
  }

  @Test
  fun `first launch shows the privacy notice, and disagree keeps it open with a hint`() {
    setWelcomeScreen()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_DISMISS_BUTTON).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_disagree_hint), substring = true).assertIsDisplayed()
    assert(!TellomiLegalConsent.hasAcceptedFirstLaunchNotice(context))
  }

  @Test
  fun `agreeing to the first launch notice closes it and is remembered`() {
    setWelcomeScreen()

    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_CONFIRM_BUTTON).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_title)).assertDoesNotExist()
    assert(TellomiLegalConsent.hasAcceptedFirstLaunchNotice(context))
  }

  @Test
  fun `the first launch notice does not come back once accepted`() {
    TellomiLegalConsent.acceptFirstLaunchNotice(context)

    setWelcomeScreen()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_title)).assertDoesNotExist()
  }

  @Test
  fun `restoring from the old phone asks for cross-border consent first`() {
    TellomiLegalConsent.acceptFirstLaunchNotice(context)
    val events = setWelcomeScreenCollecting()

    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_HAS_OLD_PHONE_BUTTON).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__title)).assertIsDisplayed()
    assert(events.isEmpty()) { "Nothing may go out before cross-border consent, but got $events" }

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.AGREE_TEST_TAG).performClick()

    assert(TellomiCrossBorderConsent.hasAgreed(context))
    assert(events == listOf<WelcomeScreenEvents>(WelcomeScreenEvents.HasOldPhone)) { "Unexpected events: $events" }
  }

  @Test
  fun `continuing to the phone number does not ask for cross-border consent yet`() {
    TellomiLegalConsent.acceptFirstLaunchNotice(context)
    val events = setWelcomeScreenCollecting()

    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__title)).assertDoesNotExist()
    assert(events == listOf<WelcomeScreenEvents>(WelcomeScreenEvents.Continue)) { "Unexpected events: $events" }
  }

  private fun setWelcomeScreenCollecting(): List<WelcomeScreenEvents> {
    val events = mutableListOf<WelcomeScreenEvents>()
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(), onEvent = { events += it })
      }
    }
    return events
  }

  private fun setPhoneNumberScreen(state: PhoneNumberEntryState): List<PhoneNumberEntryScreenEvents> {
    val events = mutableListOf<PhoneNumberEntryScreenEvents>()
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(state = state, onEvent = { events += it })
      }
    }
    return events
  }

  private fun setWelcomeScreen() {
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(), onEvent = {})
      }
    }
  }
}
