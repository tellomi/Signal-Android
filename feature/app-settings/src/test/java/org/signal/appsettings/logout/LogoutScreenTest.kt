/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.logout

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.signal.appsettings.R

/**
 * Tellomi（ADR-0072，tellomi/tellomi#1414）：「退出登录」替代方案页与确认框。文案逐字按需求（中文界面），这里用英文资源核对结构。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LogoutScreenTest {

  private val context: Application = RuntimeEnvironment.getApplication()

  @get:Rule
  val composeTestRule = createComposeRule()

  private val events = mutableListOf<LogoutEvent>()

  @Test
  fun `shows the intro and the three alternatives, each opening its existing screen`() {
    setContent(LogoutState())

    composeTestRule.onNodeWithTag(LogoutTestTags.INTRO).assertTextContains(context.getString(R.string.TellomiLogout__intro))

    composeTestRule.onNodeWithTag(LogoutTestTags.ROW_SCREEN_LOCK).assertTextContains(context.getString(R.string.TellomiLogout__screen_lock_description)).performClick()
    composeTestRule.onNodeWithTag(LogoutTestTags.ROW_MANAGE_STORAGE).assertTextContains(context.getString(R.string.TellomiLogout__manage_storage_description)).performClick()
    composeTestRule.onNodeWithTag(LogoutTestTags.ROW_CHANGE_PHONE_NUMBER).assertTextContains(context.getString(R.string.TellomiLogout__change_number_description)).performClick()

    assertThat(events).containsExactly(LogoutEvent.ScreenLockClicked, LogoutEvent.ManageStorageClicked, LogoutEvent.ChangePhoneNumberClicked)
  }

  @Test
  fun `the screen lock alternative is left out when screen lock is already on`() {
    setContent(LogoutState(showScreenLock = false))

    assertThat(composeTestRule.onAllNodesWithTag(LogoutTestTags.ROW_SCREEN_LOCK).fetchSemanticsNodes()).isEmpty()
    composeTestRule.onNodeWithTag(LogoutTestTags.ROW_MANAGE_STORAGE).assertIsDisplayed()
  }

  @Test
  fun `the linked devices switch only appears with linked devices and is off by default`() {
    setContent(LogoutState(hasLinkedDevices = false))
    assertThat(composeTestRule.onAllNodesWithTag(LogoutTestTags.ROW_UNLINK_DEVICES).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun `with linked devices the switch is shown, off, and toggles`() {
    setContent(LogoutState(hasLinkedDevices = true))

    scrollTo(LogoutTestTags.ROW_UNLINK_DEVICES)
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiLogout__also_log_out_linked_devices)).assertIsDisplayed()
    composeTestRule.onNode(isToggleable()).assertIsOff()
    composeTestRule.onNodeWithTag(LogoutTestTags.ROW_UNLINK_DEVICES).performClick()

    assertThat(events).containsExactly(LogoutEvent.UnlinkDevicesToggled(true))
  }

  @Test
  fun `the red log out row at the bottom asks for confirmation`() {
    setContent(LogoutState())

    scrollTo(LogoutTestTags.ROW_LOG_OUT)
    composeTestRule.onNodeWithTag(LogoutTestTags.ROW_LOG_OUT).assertTextContains(context.getString(R.string.TellomiLogout__log_out)).performClick()

    assertThat(events).containsExactly(LogoutEvent.LogoutClicked)
  }

  @Test
  fun `the confirmation offers log out, log out and delete, and cancel`() {
    setContent(LogoutState(dialog = LogoutState.Dialog.ConfirmLogout))

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiLogout__confirm_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiLogout__confirm_body)).assertIsDisplayed()

    composeTestRule.onNodeWithTag(LogoutTestTags.BUTTON_LOG_OUT).performClick()
    composeTestRule.onNodeWithTag(LogoutTestTags.BUTTON_LOG_OUT_AND_DELETE).performClick()
    composeTestRule.onNodeWithTag(LogoutTestTags.BUTTON_CANCEL).performClick()

    assertThat(events).containsExactly(LogoutEvent.LogoutConfirmed, LogoutEvent.LogoutAndDeleteClicked, LogoutEvent.DialogDismissed)
  }

  @Test
  fun `deleting local data asks a second time`() {
    setContent(LogoutState(dialog = LogoutState.Dialog.ConfirmDeleteLocalData))

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiLogout__delete_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiLogout__delete_body)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiLogout__delete_and_log_out)).performClick()

    assertThat(events).containsExactly(LogoutEvent.DeleteLocalDataConfirmed)
  }

  @Test
  fun `being offline says logging out needs a connection`() {
    setContent(LogoutState(dialog = LogoutState.Dialog.NeedsNetwork))

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiLogout__needs_network)).assertIsDisplayed()
  }

  private fun scrollTo(testTag: String) {
    composeTestRule.onNodeWithTag(LogoutTestTags.SCROLLER).performScrollToNode(hasTestTag(testTag))
  }

  private fun setContent(state: LogoutState) {
    composeTestRule.setContent {
      LogoutScreen(state = state, onEvent = { events += it })
    }
  }
}
