/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkdevice

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository.LinkDeviceResult

/**
 * tellomi/tellomi#1219：「已关联设备」页上的关联失败对话框。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkDeviceFailureDialogTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Application = ApplicationProvider.getApplicationContext()

  @Test
  fun `expired or foreign code says so and scan again reopens the scanner`() {
    val events = setContent(LinkDeviceResult.ExpiredOrForeignCode)

    composeTestRule.onNodeWithText(context.getString(R.string.AddLinkDeviceFragment__linking_device_failed)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.AddLinkDeviceFragment__tellomi_code_expired_or_foreign, "Signal")).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.AddLinkDeviceFragment__tellomi_scan_again)).performClick()

    assertThat(events).containsExactly("dismiss", "scanAgain")
  }

  @Test
  fun `cancel only clears the result`() {
    val events = setContent(LinkDeviceResult.NetworkError(RuntimeException()))

    composeTestRule.onNodeWithText(context.getString(R.string.AddLinkDeviceFragment__tellomi_network_error)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()

    assertThat(events).containsExactly("dismiss")
  }

  @Test
  fun `device limit only offers ok`() {
    val events = setContent(LinkDeviceResult.LimitExceeded)

    composeTestRule.onNodeWithText(context.getString(R.string.AddLinkDeviceFragment__linking_device_failed)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.AddLinkDeviceFragment__tellomi_scan_again), useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(android.R.string.ok)).performClick()

    assertThat(events).containsExactly("dismiss")
  }

  @Test
  fun `nothing is shown until linking fails`() {
    val result = mutableStateOf<LinkDeviceResult>(LinkDeviceResult.Success("token"))
    val events = setContent(result)
    val title = context.getString(R.string.AddLinkDeviceFragment__linking_device_failed)

    composeTestRule.onNodeWithText(title, useUnmergedTree = true).assertDoesNotExist()

    result.value = LinkDeviceResult.None
    composeTestRule.onNodeWithText(title, useUnmergedTree = true).assertDoesNotExist()

    // 同一个查找条件在失败时找得到，上面两处「不存在」才不是空断言
    result.value = LinkDeviceResult.ExpiredOrForeignCode
    composeTestRule.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
    assertThat(events).isEmpty()
  }

  private fun setContent(result: LinkDeviceResult): List<String> = setContent(mutableStateOf(result))

  private fun setContent(result: MutableState<LinkDeviceResult>): List<String> {
    val events = mutableListOf<String>()

    composeTestRule.setContent {
      SignalTheme {
        TellomiLinkDeviceFailureDialog(
          result = result.value,
          onScanAgain = { events += "scanAgain" },
          onDismiss = { events += "dismiss" }
        )
      }
    }

    return events
  }
}
