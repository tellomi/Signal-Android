/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import android.app.Application
import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredState.Download

/**
 * Tellomi（tellomi/tellomi#1138）：「必须更新」阻断页的界面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class UpdateRequiredScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `there are exactly two things to tap - update, or only view the chats`() {
    // owner 2026-09-24 规则 1：主按钮照旧是「立即更新」，另有「暂不更新，只看聊天记录」，不能把人锁在聊天记录外面。
    val events = mutableListOf<UpdateRequiredScreenEvent>()
    setContent(UpdateRequiredState(), events)

    composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(2)
    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.PRIMARY_BUTTON)
      .assertTextContains(context.getString(R.string.TellomiUpdateRequired__update_now))
      .performClick()
    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.VIEW_CHATS_ONLY)
      .assertTextContains(context.getString(R.string.TellomiUpdateRequired__view_chats_only))
      .performClick()

    assert(events == listOf(UpdateRequiredScreenEvent.PrimaryClicked, UpdateRequiredScreenEvent.ViewChatsOnlyClicked)) { "Expected update then view-chats-only but got $events" }
  }

  @Test
  fun `the way to the chats stays in every download state`() {
    var state by mutableStateOf(UpdateRequiredState())
    composeTestRule.setContent {
      SignalTheme {
        UpdateRequiredScreen(state = state, onEvent = {})
      }
    }

    for (download in listOf(Download.InProgress(percent = 42), Download.Failed, Download.WaitingForWifi, Download.NeedsInstallPermission, Download.ReadyToInstall, Download.NoNewerVersion)) {
      state = UpdateRequiredState(download = download)
      composeTestRule.onNodeWithTag(UpdateRequiredTestTags.VIEW_CHATS_ONLY).assertIsDisplayed()
    }
  }

  @Test
  fun `download progress is shown inside the button`() {
    setContent(UpdateRequiredState(download = Download.InProgress(percent = 42)))

    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.PRIMARY_BUTTON)
      .assertTextContains(context.getString(R.string.TellomiUpdateRequired__downloading, 42))
  }

  @Test
  fun `a failed download offers retry and the website`() {
    val events = mutableListOf<UpdateRequiredScreenEvent>()
    setContent(UpdateRequiredState(download = Download.Failed), events)

    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.PRIMARY_BUTTON)
      .assertTextContains(context.getString(R.string.TellomiUpdateRequired__retry))
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateRequired__download_failed)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.DOWNLOAD_FROM_WEBSITE).performClick()

    assert(events == listOf(UpdateRequiredScreenEvent.DownloadFromWebsiteClicked)) { "Expected DownloadFromWebsiteClicked but got $events" }
  }

  @Test
  fun `the install permission is explained before sending the user to settings`() {
    setContent(UpdateRequiredState(download = Download.NeedsInstallPermission))

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateRequired__install_permission)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.PRIMARY_BUTTON)
      .assertTextContains(context.getString(R.string.TellomiUpdateRequired__open_settings))
  }

  @Test
  fun `the reason follows why the version was blocked`() {
    setContent(UpdateRequiredState(reason = UpdateRequiredState.Reason.BUILD_EXPIRED))

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateRequired__reason_build_expired)).assertIsDisplayed()
    composeTestRule.onAllNodes(hasText(context.getString(R.string.TellomiUpdateRequired__reason_server_rejected))).assertCountEquals(0)
  }

  @Test
  fun `version and size are shown once known`() {
    setContent(UpdateRequiredState(newVersionName = "0.1.3", totalBytes = 78_000_000))

    val size = Formatter.formatShortFileSize(context, 78_000_000)
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateRequired__new_version_and_size, "0.1.3", size)).assertIsDisplayed()
  }

  @Test
  fun `offline says the update needs a connection`() {
    setContent(UpdateRequiredState(isOffline = true))

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateRequired__offline)).assertIsDisplayed()
  }

  // ==================== taishi 审查 b14（包 4）====================

  @Test
  fun `waiting for Wi-Fi says so and offers mobile data`() {
    val events = mutableListOf<UpdateRequiredScreenEvent>()
    setContent(UpdateRequiredState(download = Download.WaitingForWifi), events)

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateRequired__waiting_for_wifi)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.PRIMARY_BUTTON)
      .assertTextContains(context.getString(R.string.TellomiUpdateRequired__use_mobile_data))
      .performClick()

    assert(events == listOf(UpdateRequiredScreenEvent.PrimaryClicked)) { "Expected PrimaryClicked but got $events" }
  }

  @Test
  fun `ready to install still offers the website in case installing fails`() {
    setContent(UpdateRequiredState(download = Download.ReadyToInstall))

    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.DOWNLOAD_FROM_WEBSITE).assertIsDisplayed()
  }

  @Test
  fun `no newer version says so`() {
    setContent(UpdateRequiredState(download = Download.NoNewerVersion))

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateRequired__no_newer_version)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.DOWNLOAD_FROM_WEBSITE).assertIsDisplayed()
  }

  @Test
  fun `the title is a heading and the button reads as unavailable while downloading`() {
    setContent(UpdateRequiredState(download = Download.InProgress(percent = 42)))

    composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertTextContains(context.getString(R.string.TellomiUpdateRequired__title))
    composeTestRule.onNodeWithTag(UpdateRequiredTestTags.PRIMARY_BUTTON).assertIsNotEnabled()
  }

  private fun setContent(state: UpdateRequiredState, events: MutableList<UpdateRequiredScreenEvent> = mutableListOf()) {
    composeTestRule.setContent {
      SignalTheme {
        UpdateRequiredScreen(state = state, onEvent = { events += it })
      }
    }
  }
}
