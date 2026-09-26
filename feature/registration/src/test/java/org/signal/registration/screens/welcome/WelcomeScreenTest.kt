/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.welcome

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.TellomiRegistration
import org.signal.registration.screens.shared.TellomiLegalConsent
import org.signal.registration.test.TestTags

/**
 * Tests for WelcomeScreen that validate event emissions.
 * Uses Robolectric to run fast JUnit tests without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WelcomeScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @Before
  fun setup() {
    // Tellomi：首次启动的隐私提示另有用例（TellomiLegalConsentTest）；这里测欢迎页本身，先当作已经同意过（tellomi/tellomi#1211）。
    TellomiLegalConsent.acceptFirstLaunchNotice(ApplicationProvider.getApplicationContext())
  }

  @Test
  fun `when Get Started is clicked, Continue event is emitted`() {
    // Given
    var emittedEvent: WelcomeScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(
          state = WelcomeScreenState(),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).performClick()

    // Then
    assert(emittedEvent == WelcomeScreenEvents.Continue)
  }

  @Config(qualifiers = "w1280dp-h800dp-xhdpi")
  @Test
  fun `when Link Your Account is clicked, LinkDevice event is emitted`() {
    // Given
    var emittedEvent: WelcomeScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(
          state = WelcomeScreenState(isLinkAndSyncAvailable = true),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_LINK_DEVICE_BUTTON).performClick()

    // Then
    assert(emittedEvent == WelcomeScreenEvents.LinkDevice)
  }

  @Test
  fun `when Restore or transfer is clicked, bottom sheet is shown`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(), onEvent = {})
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()

    // Then - bottom sheet options should be visible
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_HAS_OLD_PHONE_BUTTON).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_NO_OLD_PHONE_BUTTON).assertIsDisplayed()
  }

  @Test
  fun `when I have my old phone is clicked, HasOldPhone event is emitted`() {
    // Given
    var emittedEvent: WelcomeScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(
          state = WelcomeScreenState(),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_HAS_OLD_PHONE_BUTTON).performClick()

    // Then
    assert(emittedEvent == WelcomeScreenEvents.HasOldPhone)
  }

  @Test
  fun `when I don't have my old phone is clicked, DoesNotHaveOldPhone event is emitted`() {
    // Given
    var emittedEvent: WelcomeScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(
          state = WelcomeScreenState(),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_NO_OLD_PHONE_BUTTON).performClick()

    // Then
    assert(emittedEvent == WelcomeScreenEvents.DoesNotHaveOldPhone)
  }

  @Test
  fun `during re-registration the restore or transfer option is hidden`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(
          state = WelcomeScreenState(showRestoreOrTransfer = false),
          onEvent = {}
        )
      }
    }

    // Then - the continue option remains but the restore/transfer option is not offered
    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).assertIsDisplayed()
    assert(composeTestRule.onAllNodesWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).fetchSemanticsNodes().isEmpty()) {
      "Expected no restore/transfer option during re-registration"
    }
  }

  @Test
  fun `screen displays welcome message`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(), onEvent = {})
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.WELCOME_HEADLINE).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).assertIsDisplayed()
  }

  @After
  fun resetRemoteBackups() {
    TellomiRegistration.remoteBackupsAvailableForTesting = null
  }

  // Tellomi（tellomi/tellomi#1216）：「恢复或转移」降成一行文字链，底部弹层说清每条路能带过来什么。

  @Test
  fun `restore or transfer is a one-line New phone link`() {
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(), onEvent = {})
      }
    }

    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).assertIsDisplayed()
    composeTestRule.onNodeWithText("New phone?").assertIsDisplayed()
    assert(composeTestRule.onAllNodesWithText("Restore or transfer").fetchSemanticsNodes().isEmpty()) {
      "Expected the large 'Restore or transfer' button to be gone"
    }
  }

  @Test
  fun `without a backup service the sheet explains what each path brings over`() {
    TellomiRegistration.remoteBackupsAvailableForTesting = false
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(), onEvent = {})
      }
    }

    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()

    composeTestRule.onNodeWithText("My old Android phone is here").assertIsDisplayed()
    composeTestRule.onNodeWithText("My old phone isn't here, or it's an iPhone").assertIsDisplayed()
    assert(composeTestRule.onAllNodesWithText("I have my old phone").fetchSemanticsNodes().isEmpty())
    assert(composeTestRule.onAllNodesWithText("Or you're reinstalling Tellomi on the same device").fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun `with a backup service the sheet keeps the upstream wording`() {
    TellomiRegistration.remoteBackupsAvailableForTesting = true
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(), onEvent = {})
      }
    }

    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()

    composeTestRule.onNodeWithText("I have my old phone").assertIsDisplayed()
    composeTestRule.onNodeWithText("I don't have my old phone").assertIsDisplayed()
  }
}
