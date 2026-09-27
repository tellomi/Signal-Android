/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import android.app.Application
import android.content.Context
import android.widget.EditText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.R
import org.signal.registration.test.TestTags

/**
 * Tellomi（ADR-0066 §6.1b，owner 2026-09-27）：用户名一律小写。输入框下常驻一行灰色规则提示；打了（或粘贴了）大写当场转小写，
 * 光标 / 选区不跳，规则提示那一行换成「已自动转成小写」约 2 秒再恢复；别的不合规字符照旧就地红字报错、不自动删。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h1200dp")
class TellomiUsernameInputTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the hint texts`() {
    assert(context.getString(R.string.TellomiUsername__rules_hint) == "Lowercase letters, numbers and underscores only; starts with a letter; 3–20 characters.")
    assert(context.getString(R.string.TellomiUsername__lowercased_hint) == "Changed to lowercase")
  }

  @Config(qualifiers = "zh-rCN-w360dp-h1200dp")
  @Test
  fun `the hint texts in simplified chinese`() {
    assert(context.getString(R.string.TellomiUsername__rules_hint) == "只能用小写字母、数字和下划线，以字母开头，3–20 位。")
    assert(context.getString(R.string.TellomiUsername__lowercased_hint) == "已自动转成小写")
  }

  @Test
  fun `lowercasing keeps the selection and the composition`() {
    val typed = TextFieldValue("heLLo", selection = TextRange(2, 4), composition = TextRange(0, 5))

    val result = TellomiUsernameInput.lowercase(typed)

    assert(result.text == "hello") { "Unexpected text: ${result.text}" }
    assert(result.selection == TextRange(2, 4)) { "Selection moved: ${result.selection}" }
    assert(result.composition == TextRange(0, 5)) { "Composition moved: ${result.composition}" }
  }

  @Test
  fun `the rules hint is always under the registration username field`() {
    setProfileScreen()

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_RULES_HINT, useUnmergedTree = true)
      .assertIsDisplayed()
      .assertTextEquals(context.getString(R.string.TellomiUsername__rules_hint))
  }

  @Test
  fun `typing uppercase lowercases it at once and swaps the hint for about 2 seconds`() {
    val events = setProfileScreen()
    composeTestRule.mainClock.autoAdvance = false

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_FIELD).performTextInput("KaiXin")
    // 转换后的重组和提示的计时要几帧
    composeTestRule.mainClock.advanceTimeBy(100)

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_FIELD).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("kaixin")))
    assert(events.last() == CreateProfileScreenEvents.UsernameChanged("kaixin")) { "Unexpected events: $events" }
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_RULES_HINT, useUnmergedTree = true).assertTextEquals(context.getString(R.string.TellomiUsername__lowercased_hint))

    composeTestRule.mainClock.advanceTimeBy(TellomiUsernameInput.LOWERCASED_HINT_MS)
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_RULES_HINT, useUnmergedTree = true).assertTextEquals(context.getString(R.string.TellomiUsername__rules_hint))
  }

  @Test
  fun `pasting uppercase in the middle keeps the cursor after the pasted text`() {
    setProfileScreen(initial = "ab")

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_FIELD).performTextInputSelection(TextRange(1))
    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_FIELD).performTextInput("XY")

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_FIELD)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("axyb")))
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
  }

  @Test
  fun `other invalid characters keep the red error and are not removed`() {
    setProfileScreen()

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_FIELD).performTextInput("Kai Xin")

    composeTestRule.onNodeWithTag(TestTags.CREATE_PROFILE_USERNAME_FIELD).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("kai xin")))
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiRegistration__username_invalid_characters)).assertIsDisplayed()
  }

  /** 老界面（设置里的改名 / 找回，EditText）用的过滤器：键入和粘贴都当场转小写，光标跟着插入点走。 */
  @Test
  fun `the edit text filter lowercases typing and pasting in place`() {
    var lowercased = 0
    val editText = EditText(context)
    editText.filters = arrayOf(TellomiUsernameInputFilter { lowercased++ })

    editText.setText("ab")
    assert(lowercased == 0)

    editText.setSelection(1)
    editText.text.replace(1, 1, "XY")

    assert(editText.text.toString() == "axyb") { "Unexpected text: ${editText.text}" }
    assert(editText.selectionStart == 3 && editText.selectionEnd == 3) { "Cursor moved: ${editText.selectionStart}..${editText.selectionEnd}" }
    assert(lowercased == 1) { "Expected one lowercase notice, got $lowercased" }

    editText.text.append(" -Z")
    assert(editText.text.toString() == "axyb -z") { "Other characters must stay for the inline error: ${editText.text}" }
  }

  /** 按「视图模型收到什么就显示什么」模拟资料页：`UsernameChanged` 直接写回输入框，再跑一遍本地规则。 */
  private fun setProfileScreen(initial: String = ""): MutableList<CreateProfileScreenEvents> {
    val events = mutableListOf<CreateProfileScreenEvents>()
    composeTestRule.setContent {
      var state by androidx.compose.runtime.remember { mutableStateOf(CreateProfileState(isLoading = false, usernameEntry = TellomiUsernameEntry(text = initial))) }
      SignalTheme {
        CreateProfileScreen(
          state = state,
          onEvent = { event ->
            events += event
            if (event is CreateProfileScreenEvents.UsernameChanged) {
              state = state.copy(usernameEntry = TellomiUsernameEntry(text = event.value, error = TellomiUsernameEntry.check(event.value)))
            }
          }
        )
      }
    }
    return events
  }
}
