/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.libsignal.usernames.Username
import org.signal.registration.R
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

  /** Tellomi（tellomi/tellomi#1215）：只有一个「名字」框；号码可见性那一项换成一句说明（加了用户名框之后说明在默认屏高下要滚才看得到）。 */
  @Config(qualifiers = "w360dp-h1200dp")
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

  /** 显示名字时头像整块仍念「设置头像」：照片、相机图标两支本来就有，这一支以前读屏只念出名字（taishi 审查 2026-09-24）。 */
  @Test
  fun `avatar with initials is still announced as set avatar`() {
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(givenName = "欧阳娜娜", isLoading = false),
          onEvent = {}
        )
      }
    }

    val setAvatar = ApplicationProvider.getApplicationContext<Application>().getString(R.string.CreateProfileScreen__set_avatar_description)
    composeTestRule.onNode(hasClickAction() and hasContentDescription(setAvatar)).assertExists()
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

  /** Tellomi（tellomi/tellomi#1215 第二刀）：保留成功后说明行给出链接。 */
  @Config(qualifiers = "w360dp-h1200dp")
  @Test
  fun `a reserved username shows its tell cc link`() {
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(
            givenName = "Alice",
            isLoading = false,
            usernameEntry = TellomiUsernameEntry(text = "Kaixin", reservation = Username("Kaixin.01"))
          ),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_SUPPORTING_TEXT, useUnmergedTree = true).assertTextEquals("Your link: tell.cc/kaixin")
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_NEXT_BUTTON).assertIsEnabled()
  }

  /** Tellomi（tellomi/tellomi#1215 第二刀）：不可用时给候选，点了发事件；没检查过的用户名不能进入。 */
  @Config(qualifiers = "w360dp-h1200dp")
  @Test
  fun `an unavailable username offers candidates and tapping one emits UsernameCandidateClicked`() {
    var emittedEvent: CreateProfileScreenEvents? = null

    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(
            givenName = "Alice",
            isLoading = false,
            usernameEntry = TellomiUsernameEntry(
              text = "kaixin",
              error = TellomiUsernameEntry.Error.NOT_AVAILABLE,
              candidates = listOf("kaixin27", "kaixin726", "kaixin58")
            )
          ),
          onEvent = { emittedEvent = it }
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_SUPPORTING_TEXT, useUnmergedTree = true).assertTextEquals("This username isn't available")
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_NEXT_BUTTON).assertIsNotEnabled()
    composeTestRule.onAllNodesWithTag(TestTags.CREATE_PROFILE_USERNAME_CANDIDATE).assertCountEquals(3)
    composeTestRule.onAllNodesWithTag(TestTags.CREATE_PROFILE_USERNAME_CANDIDATE)[1].performClick()

    assert(emittedEvent == CreateProfileScreenEvents.UsernameCandidateClicked("kaixin726"))
  }

  /** Tellomi（taishi 审查包 4）：继承的改名冷却按天说；一小时以内的限流说「尝试次数过多」。两种都不能带着用户名进入。 */
  @Config(qualifiers = "w360dp-h1200dp")
  @Test
  fun `a rename cooldown says how many days are left`() {
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(
            givenName = "Alice",
            isLoading = false,
            usernameEntry = TellomiUsernameEntry(text = "kaixin", error = TellomiUsernameEntry.Error.RENAME_COOLDOWN, cooldownDays = 30)
          ),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_SUPPORTING_TEXT, useUnmergedTree = true).assertTextEquals("You can set a username in 30 days")
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_NEXT_BUTTON).assertIsNotEnabled()
  }

  @Config(qualifiers = "w360dp-h1200dp")
  @Test
  fun `a short rate limit says too many attempts`() {
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(
            givenName = "Alice",
            isLoading = false,
            usernameEntry = TellomiUsernameEntry(text = "kaixin", error = TellomiUsernameEntry.Error.TOO_MANY_ATTEMPTS)
          ),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_SUPPORTING_TEXT, useUnmergedTree = true).assertTextEquals("Too many attempts. Try again later.")
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_NEXT_BUTTON).assertIsNotEnabled()
  }

  /** Tellomi（tellomi/tellomi#1266）：重新注册时没有用户名框（查未合并的树：可点击的容器会把子节点合并掉，断言会假绿）。 */
  @Config(qualifiers = "w360dp-h1200dp")
  @Test
  fun `re-registration shows no username field`() {
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(givenName = "Alice", isLoading = false, showUsername = false),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_GIVEN_NAME_FIELD).assertExists()
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_FIELD, useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_SUPPORTING_TEXT, useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_NEXT_BUTTON).assertIsEnabled()
    // 下面没有用户名框了，名字框的键盘动作是「完成」
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_GIVEN_NAME_FIELD)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
  }

  /** Tellomi（taishi 审查包 4）：说明行变了读屏要念（liveRegion）；名字框按「下一项」跳到用户名框。 */
  @Config(qualifiers = "w360dp-h1200dp")
  @Test
  fun `the username status line is a polite live region and next moves from name to username`() {
    composeTestRule.setContent {
      SignalTheme {
        CreateProfileScreen(
          state = CreateProfileState(isLoading = false),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_SUPPORTING_TEXT, useUnmergedTree = true)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_GIVEN_NAME_FIELD)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_GIVEN_NAME_FIELD).performClick()
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_GIVEN_NAME_FIELD).performImeAction()
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_FIELD).assertIsFocused()
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
