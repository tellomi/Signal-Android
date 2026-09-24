/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.verificationcode

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.contains
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.test.TestTags

/**
 * Tests for VerificationCodeScreen that validate event emissions and UI behavior.
 * Uses Robolectric to run fast JUnit tests without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VerificationCodeScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @Test
  fun `screen displays title`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithText("Verification code").assertIsDisplayed()
  }

  @Test
  fun `screen displays all six digit fields`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_0).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_1).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_2).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_3).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_4).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_5).assertIsDisplayed()
  }

  @Test
  fun `clicking wrong number emits WrongNumber event`() {
    // Given
    var emittedEvent: VerificationCodeScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_WRONG_NUMBER_BUTTON).performClick()

    // Then
    assert(emittedEvent == VerificationCodeScreenEvents.WrongNumber)
  }

  @Test
  fun `clicking resend SMS emits ResendSms event`() {
    // Given
    var emittedEvent: VerificationCodeScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_RESEND_SMS_BUTTON).performClick()

    // Then
    assert(emittedEvent == VerificationCodeScreenEvents.ResendSms)
  }

  /**
   * Tellomi（tellomi/tellomi#1210）：香港没有语音通道（TellomiRegistration.VOICE_VERIFICATION_AVAILABLE = false），
   * 「给我打电话」不显示。上游这条用例是点它发 CallMe。
   */
  @Test
  fun `call me is not shown without a voice channel`() {
    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_CALL_ME_BUTTON).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_RESEND_SMS_BUTTON).assertIsDisplayed()
  }

  @Test
  fun `entering a digit emits DigitChanged for that field`() {
    // Given
    val emittedEvents = mutableListOf<VerificationCodeScreenEvents>()

    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(),
          onEvent = { emittedEvents.add(it) }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_0).performTextInput("1")
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_1).performTextInput("2")
    composeTestRule.waitForIdle()

    // Then
    val digitChanges = emittedEvents.filterIsInstance<VerificationCodeScreenEvents.DigitChanged>()
    assert(digitChanges.contains(VerificationCodeScreenEvents.DigitChanged(0, "1"))) {
      "Expected DigitChanged(0, 1) but got $digitChanges"
    }
    assert(digitChanges.contains(VerificationCodeScreenEvents.DigitChanged(1, "2"))) {
      "Expected DigitChanged(1, 2) but got $digitChanges"
    }
  }

  @Test
  fun `screen renders the digits from state`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(digits = listOf("1", "2", "3", "4", "5", "6")),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_0).assertTextEquals("1")
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_5).assertTextEquals("6")
  }

  @Test
  fun `pasting into a field emits DigitChanged with the raw text`() {
    // Given
    val emittedEvents = mutableListOf<VerificationCodeScreenEvents>()

    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(),
          onEvent = { emittedEvents.add(it) }
        )
      }
    }

    // When - paste the entire code, including the hyphen, into the first field
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_DIGIT_0).performTextInput("123-456")

    composeTestRule.waitForIdle()

    // Then
    val digitChanges = emittedEvents.filterIsInstance<VerificationCodeScreenEvents.DigitChanged>()
    assert(digitChanges.contains(VerificationCodeScreenEvents.DigitChanged(0, "123-456"))) {
      "Expected DigitChanged(0, 123-456) but got $digitChanges"
    }
  }

  @Test
  fun `autoFillCode emits a single DigitChanged with the full code`() {
    // Given
    val emittedEvents = mutableListOf<VerificationCodeScreenEvents>()

    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(autoFillCode = "123456"),
          onEvent = { emittedEvents.add(it) }
        )
      }
    }

    // When - the auto-fill effect populates the fields
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      emittedEvents.any { it is VerificationCodeScreenEvents.DigitChanged }
    }

    // Then - a single event carries the whole code, rather than a burst of per-digit events
    val digitChanges = emittedEvents.filterIsInstance<VerificationCodeScreenEvents.DigitChanged>()
    assert(digitChanges == listOf(VerificationCodeScreenEvents.DigitChanged(0, "123456"))) {
      "Expected a single DigitChanged(0, 123456) but got $digitChanges"
    }
  }

  @Test
  fun `screen displays all action buttons`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithText("Wrong number?").assertIsDisplayed()
    composeTestRule.onNodeWithText("Resend Code").assertIsDisplayed()
    // Tellomi（tellomi/tellomi#1210）：没有语音通道，「给我打电话」不显示
    composeTestRule.onNodeWithText("Call me instead").assertDoesNotExist()
  }

  // ==================== Tellomi（tellomi/tellomi#1214） ====================

  @Test
  fun `an incorrect code is shown inline under the digits`() {
    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(
            e164 = "+8613800138000",
            incorrectCodeAttempts = 1,
            snackbars = VerificationCodeState.Snackbars(incorrectVerificationCode = true)
          ),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_ERROR).assertIsDisplayed()
    composeTestRule.onNodeWithText("Incorrect code. Check the text message and try again.").assertIsDisplayed()
    // 上游的 Snackbar 文案不再出现。
    composeTestRule.onNodeWithText("Incorrect code").assertDoesNotExist()
  }

  @Test
  fun `no inline error before a wrong code`() {
    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(state = VerificationCodeState(e164 = "+8613800138000"), onEvent = {})
      }
    }

    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_ERROR).assertDoesNotExist()
  }

  @Config(qualifiers = "w411dp-h891dp")
  @Test
  fun `the didn't get the code sheet offers ways out`() {
    val events = mutableListOf<VerificationCodeScreenEvents>()
    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(e164 = "+8613800138000", showContactSupportSheet = true),
          onEvent = { events += it }
        )
      }
    }

    composeTestRule.onNodeWithText("• Check that the number is right: +8613800138000").assertIsDisplayed()
    composeTestRule.onNodeWithText("support@tellomi.app", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("at most 3 codes", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_HELP_CONTACT_SUPPORT).performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag(TestTags.VERIFICATION_CODE_HELP_CHANGE_NUMBER).performScrollTo().performClick()
    composeTestRule.waitForIdle()

    assertThat(events).contains(VerificationCodeScreenEvents.WrongNumber)
  }

  @Test
  fun `an expired session is explained before going back`() {
    val events = mutableListOf<VerificationCodeScreenEvents>()
    composeTestRule.setContent {
      SignalTheme {
        VerificationCodeScreen(
          state = VerificationCodeState(
            e164 = "+8613800138000",
            dialogs = VerificationCodeState.Dialogs(sessionExpired = true)
          ),
          onEvent = { events += it }
        )
      }
    }

    composeTestRule.onNodeWithText("This verification has expired. Please check your number and get a new code.").assertIsDisplayed()
    composeTestRule.onNodeWithText("OK").performClick()

    assertThat(events).contains(VerificationCodeScreenEvents.SessionExpiredDialogDismissed)
  }
}
