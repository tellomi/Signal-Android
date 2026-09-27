/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.restoreselection

import android.app.Application
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsDisplayed
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
import org.signal.registration.R
import org.signal.registration.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1216 跟进）：旧手机是 iPhone、又没有备份服务时的说明页。和 iOS「旧手机是 Android」那页对齐：
 * 只说明传不过来，给「直接注册 / 返回」，不列 iPhone 用不上的恢复方式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiNoTransferFromIphoneScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()
  private var backDispatcher: OnBackPressedDispatcher? = null

  /** 页面状态照 ViewModel 给的来：仍带着上游 forManualRestore 的恢复方式，只是这一页不画它们。 */
  private val oldIphoneState = ArchiveRestoreSelectionState(
    restoreOptions = listOf(ArchiveRestoreOption.LocalBackup, ArchiveRestoreOption.None),
    skippingSignsOutOldPhone = true,
    showsNoTransferFromIphone = true
  )

  private fun setScreen(state: ArchiveRestoreSelectionState = oldIphoneState): MutableList<ArchiveRestoreSelectionScreenEvents> {
    val events = mutableListOf<ArchiveRestoreSelectionScreenEvents>()
    composeTestRule.setContent {
      backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
      SignalTheme {
        ArchiveRestoreSelectionScreen(state = state, onEvent = { events += it })
      }
    }
    return events
  }

  @Test
  fun `the page explains that messages cannot come from an iPhone instead of listing restore options`() {
    setScreen()

    composeTestRule.onNodeWithTag(TellomiNoTransferFromIphoneTestTags.SCREEN).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiRegistration__no_transfer_from_iphone_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiRegistration__no_transfer_from_iphone_body)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiNoTransferFromIphoneTestTags.REGISTER_DIRECTLY).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiNoTransferFromIphoneTestTags.BACK).assertIsDisplayed()

    composeTestRule.onNodeWithTag(TestTags.ARCHIVE_RESTORE_SELECTION_SCREEN).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.ARCHIVE_RESTORE_SELECTION_FROM_BACKUP_FOLDER).assertDoesNotExist()
  }

  @Test
  fun `register directly goes straight on without the skip dialog`() {
    val events = setScreen()

    composeTestRule.onNodeWithTag(TellomiNoTransferFromIphoneTestTags.REGISTER_DIRECTLY).performClick()

    assert(events == listOf<ArchiveRestoreSelectionScreenEvents>(ArchiveRestoreSelectionScreenEvents.TellomiRegisterDirectly)) { "Unexpected events: $events" }
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiRegistration__skip_restore_dialog_title)).assertDoesNotExist()
  }

  @Test
  fun `the back button and the system back both go back`() {
    val events = setScreen()

    composeTestRule.onNodeWithTag(TellomiNoTransferFromIphoneTestTags.BACK).performClick()
    composeTestRule.runOnUiThread { backDispatcher!!.onBackPressed() }
    composeTestRule.waitForIdle()

    assert(events == listOf<ArchiveRestoreSelectionScreenEvents>(ArchiveRestoreSelectionScreenEvents.TellomiBack, ArchiveRestoreSelectionScreenEvents.TellomiBack)) { "Unexpected events: $events" }
  }

  @Test
  fun `the restore options page itself is unchanged when the old phone is not an iPhone`() {
    setScreen(state = oldIphoneState.copy(showsNoTransferFromIphone = false))

    composeTestRule.onNodeWithTag(TestTags.ARCHIVE_RESTORE_SELECTION_SCREEN).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiNoTransferFromIphoneTestTags.SCREEN).assertDoesNotExist()
  }
}
