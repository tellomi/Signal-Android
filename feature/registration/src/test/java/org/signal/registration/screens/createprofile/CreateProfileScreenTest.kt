/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.test.TestTags

/**
 * Tests for CreateProfileScreen that validate event emissions.
 * Uses Robolectric to run fast JUnit tests without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CreateProfileScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  /** Tellomi（tellomi/tellomi#1215）：只有一个「名字」框；号码可见性那一项换成一句说明。 */
  @Test
  fun `screen displays a single name field and the phone number statement`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(isLoading = false),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_GIVEN_NAME_FIELD).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_FAMILY_NAME_FIELD).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_WHO_CAN_FIND_ME_ROW).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_PHONE_NUMBER_NOT_SHOWN).assertIsDisplayed()
  }

  /**
   * Tellomi（tellomi/tellomi#1215）：没选照片时默认头像随名字变，中文名取最后两个字。
   * 头像整块可点、会合并子节点，所以找字要看未合并的树（否则「不存在」的断言永远成立）。
   */
  @Test
  fun `avatar shows the last two characters of a chinese name`() {
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(givenName = "欧阳娜娜", isLoading = false),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_AVATAR_INITIALS, useUnmergedTree = true).assertTextEquals("娜娜")
  }

  @Test
  fun `avatar shows the camera placeholder until a name is typed`() {
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(isLoading = false),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_AVATAR_INITIALS, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun `when typing in given name field, GivenNameChanged event is emitted`() {
    // Given
    var emittedEvent: CreateProfileScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(isLoading = false),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_GIVEN_NAME_FIELD).performTextInput("Alice")

    // Then
    assert(emittedEvent is CreateProfileScreenEvents.GivenNameChanged)
  }

  @Test
  fun `when given name is blank, Next button is disabled`() {
    // Given
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(isLoading = false),
          onEvent = {}
        )
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_NEXT_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun `when given name is present, Next button is enabled and emits NextClicked`() {
    // Given
    var emittedEvent: CreateProfileScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(givenName = "Alice", isLoading = false),
          onEvent = { event ->
            emittedEvent = event
          }
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_NEXT_BUTTON).assertIsEnabled().performClick()

    // Then
    assert(emittedEvent == CreateProfileScreenEvents.NextClicked)
  }
}
