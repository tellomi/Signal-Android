/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.phonenumber

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.hasLength
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.R
import org.signal.registration.screens.shared.AccountIdFormat
import org.signal.registration.test.TestTags

/**
 * Tests for PhoneNumberScreen that validate user interactions and event emissions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PhoneNumberScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `Next button is disabled when fields are empty`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_NEXT_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun `Next button is enabled when nationalNumber is present in state`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(
            countryCode = "1",
            nationalNumber = "5551234567",
            formattedNumber = "(555) 123-4567",
            isNumberPossible = true
          ),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_NEXT_BUTTON).assertIsEnabled()
  }

  @Test
  fun `when Next is clicked, PhoneNumberEntered event is emitted`() {
    // Given
    var emittedEvent: PhoneNumberEntryScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(
            countryCode = "1",
            nationalNumber = "5551234567",
            formattedNumber = "(555) 123-4567",
            isNumberPossible = true
          ),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When - click Next
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_NEXT_BUTTON).performClick()

    // Then
    assert(emittedEvent is PhoneNumberEntryScreenEvents.NextClicked) {
      "Expected PhoneNumberEntered event but got $emittedEvent"
    }
  }

  @Test
  fun `pressing done does not emit NextClicked when number is not valid`() {
    // Given
    var emittedEvent: PhoneNumberEntryScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(
            countryCode = "1",
            nationalNumber = "555",
            formattedNumber = "555"
          ),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When - press the IME done action on the phone number field
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_PHONE_FIELD).performImeAction()

    // Then
    assert(emittedEvent !is PhoneNumberEntryScreenEvents.NextClicked) {
      "Expected no NextClicked event for an invalid number but got $emittedEvent"
    }
  }

  @Test
  fun `pressing done emits NextClicked when number is valid`() {
    // Given
    var emittedEvent: PhoneNumberEntryScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(
            countryCode = "1",
            nationalNumber = "5551234567",
            formattedNumber = "(555) 123-4567",
            isNumberPossible = true
          ),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When - press the IME done action on the phone number field
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_PHONE_FIELD).performImeAction()

    // Then
    assert(emittedEvent is PhoneNumberEntryScreenEvents.NextClicked) {
      "Expected NextClicked event for a valid number but got $emittedEvent"
    }
  }

  @Test
  fun `clicking country picker emits CountryPicker event`() {
    // Given
    var emittedEvent: PhoneNumberEntryScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_COUNTRY_CODE_FIELD).performClick()

    // Then
    assert(emittedEvent is PhoneNumberEntryScreenEvents.CountryPicker) {
      "Expected CountryPicker event but got $emittedEvent"
    }
  }

  @Test
  fun `account ID entry shows the account ID label and swaps the country code for a key`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = accountIdState(),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_account_id)).assertExists()
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_COUNTRY_CODE_FIELD).assertExists()
  }

  @Test
  fun `account ID entry renders the ID with the dashes an ACI is normally written with`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = accountIdState(),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithText("A6B28482-2E32-83D0-7F23-91360A4C2B91").assertExists()
  }

  @Test
  fun `Next button is enabled for a complete account ID and disabled for a partial one`() {
    // Given
    var accountId by mutableStateOf("a6b284822e3283d07f2391360a4c2b91")

    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = accountIdState(accountId),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_NEXT_BUTTON).assertIsEnabled()

    // When
    accountId = "a6b28482"

    // Then
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_NEXT_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun `account ID entry turns away anything typed past a complete ID`() {
    // Given
    var emittedEvent: PhoneNumberEntryScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = accountIdState(),
          onEvent = { emittedEvent = it }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_PHONE_FIELD).performTextInput("ff")

    // Then
    val newValue = (emittedEvent as PhoneNumberEntryScreenEvents.NationalNumberChanged).newValue
    assertThat(newValue).hasLength(AccountIdFormat.ACCOUNT_ID_LENGTH)
  }

  @Test
  fun `an account ID stays a plain phone number field when numberless registration is unavailable`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = accountIdState().copy(isPhoneNumberlessRegistrationAvailable = false),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithText("a6b284822e3283d07f2391360a4c2b91").assertExists()
    composeTestRule.onNodeWithText("A6B28482-2E32-83D0-7F23-91360A4C2B91").assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_account_id)).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_COUNTRY_CODE_FIELD).assertExists()
  }

  @Test
  fun `the country code control shows the selected calling code`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(countryCode = "44", countryName = "United Kingdom"),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithText("+44").assertExists()
  }

  @Test
  fun `the country code control does not open the picker in account ID mode`() {
    // Given
    var emittedEvent: PhoneNumberEntryScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = accountIdState(),
          onEvent = { event -> emittedEvent = event }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_COUNTRY_CODE_FIELD).performClick()

    // Then
    assert(emittedEvent == null) {
      "Expected no event when tapping the country code in account ID mode but got $emittedEvent"
    }
  }

  @Test
  fun `the numberless button offers to register without a number when the user has no existing account`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(isPhoneNumberlessRegistrationAvailable = true),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_register_without_number)).assertExists()
  }

  @Test
  fun `the numberless button offers to use an account ID when the user has an existing account`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(
            isPhoneNumberlessRegistrationAvailable = true,
            sawArchiveRestoreSelectionScreen = true
          ),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_use_account_id)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_register_without_number)).assertDoesNotExist()
  }

  private fun accountIdState(accountId: String = "a6b284822e3283d07f2391360a4c2b91") = PhoneNumberEntryState(
    accountId = accountId,
    formattedNumber = accountId,
    isPhoneNumberlessRegistrationAvailable = true
  )

  // ==================== Tellomi（tellomi/tellomi#1213）：清空 × ====================

  @Test
  fun `the clear button only shows once something is typed`() {
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(state = PhoneNumberEntryState(), onEvent = {})
      }
    }

    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_CLEAR_BUTTON).assertDoesNotExist()
  }

  @Test
  fun `the clear button empties the number`() {
    val events = mutableListOf<PhoneNumberEntryScreenEvents>()
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(
            countryCode = "86",
            nationalNumber = "13800138000",
            formattedNumber = "138 0013 8000",
            isNumberPossible = true
          ),
          onEvent = { events += it }
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_CLEAR_BUTTON).performClick()

    val change = events.filterIsInstance<PhoneNumberEntryScreenEvents.NationalNumberChanged>().last()
    assert(change.newValue.isEmpty()) { "Expected the number to be cleared but got ${change.newValue}" }
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_CLEAR_BUTTON).assertDoesNotExist()
  }

  @Test
  fun `clearing the number from an unfocused field puts the cursor back so typing can continue`() {
    // × 在框没有焦点时也显示（iOS 只在编辑中显示）；清完要能直接接着输（taishi 审查 b13 不阻塞）。
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(
          state = PhoneNumberEntryState(
            countryCode = "86",
            nationalNumber = "13800138000",
            formattedNumber = "138 0013 8000",
            isNumberPossible = true
          ),
          onEvent = {}
        )
      }
    }
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_PHONE_FIELD).assertIsNotFocused()

    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_CLEAR_BUTTON).performClick()

    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_PHONE_FIELD).assertIsFocused()
  }

  // ==================== Tellomi（tellomi/tellomi#1338）：同意之前不调 Google Play 服务 ====================

  @Test
  fun `entering the screen does not ask Google Play services for a phone number hint`() {
    // 号码页出现时用户还没同意协议和跨境告知；上游在这里（state.initialized 变 true、号码为空）自动调
    // Identity.getSignInClient(...).getPhoneNumberHintIntent(...) 弹 Google 的号码选择器。
    mockkStatic(Identity::class)
    try {
      every { Identity.getSignInClient(any<Context>()) } returns mockk<SignInClient>(relaxed = true)
      val events = mutableListOf<PhoneNumberEntryScreenEvents>()

      composeTestRule.setContent {
        SignalTheme {
          PhoneNumberScreen(state = PhoneNumberEntryState(initialized = true), onEvent = { events += it })
        }
      }
      composeTestRule.waitForIdle()

      verify(exactly = 0) { Identity.getSignInClient(any<Context>()) }
      assert(events.none { it is PhoneNumberEntryScreenEvents.FullPhoneNumberEntered }) {
        "Expected no prefilled number before consent but got $events"
      }
    } finally {
      unmockkStatic(Identity::class)
    }
  }
}
