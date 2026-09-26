/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import io.mockk.coEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.preupload.PreUploadController
import java.util.Collections

/**
 * Tellomi：相机扫到新手机上的「转移帐户」码，先弹防骗确认；点「继续」才进转移页，取消就什么都不发生。
 * 视图模型、对话框都是真的，只有「扫到的码是什么」是桩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class TellomiReRegistrationScannedDialogTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val commands: MutableList<MediaSendFlowHudCommand> = Collections.synchronizedList(mutableListOf())

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(id)

  /** 命令是视图模型在主线程上发的（`viewModelScope`），先让主 Looper 把排着的任务跑完再看。 */
  private fun commandsArrived(): Boolean {
    shadowOf(Looper.getMainLooper()).idle()
    return commands.isNotEmpty()
  }

  private fun dialogShown(): Boolean {
    return composeTestRule.onAllNodesWithText(string(R.string.TellomiReRegistrationScannedDialog__title)).fetchSemanticsNodes().isNotEmpty()
  }

  /** 扫到一个转移码，等确认框出来。 */
  private fun scanTransferCode() {
    coEvery { MediaSendDependencies.qrRepository.checkQrData(TRANSFER_CODE) } returns MediaSendQrRepository.QrCheckResult.ReRegistration(TRANSFER_CODE)

    val viewModel = MediaSendFlowViewModel(
      savedStateHandle = SavedStateHandle(
        mapOf(
          "media_send_vm_args" to MediaSendFlowActivityContract.Args(),
          "media_send_vm_identity_changes_since" to 0L
        )
      ),
      repository = mediaSendDependenciesRule.mediaSendRepository,
      preUploadController = PreUploadController(),
      isMeteredFlow = flowOf(false)
    )
    scope.launch { viewModel.hudCommands.collect { commands += it } }

    composeTestRule.setContent {
      SignalTheme {
        viewModel.reRegistrationScannedDialog.Content { _, onDismissRequest, onConfirm, _, onDeny ->
          TellomiReRegistrationScannedDialog(onConfirm = onConfirm, onDeny = onDeny, onDismissRequest = onDismissRequest)
        }
      }
    }

    viewModel.onEvent(MediaSendFlowEvent.QrCodeScanned(TRANSFER_CODE))
    composeTestRule.waitUntil(timeoutMillis = 5_000) { dialogShown() }
  }

  @Test
  fun `a transfer code asks first and only continue opens the transfer page`() {
    scanTransferCode()

    assertThat(commands.toList()).isEmpty()

    composeTestRule.onNodeWithText(string(R.string.TellomiReRegistrationScannedDialog__continue)).performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) { commandsArrived() }

    assertThat(commands.toList()).containsExactly(MediaSendFlowHudCommand.GoToQuickTransfer(TRANSFER_CODE))
  }

  @Test
  fun `cancelling a transfer code does not open the transfer page`() {
    scanTransferCode()

    composeTestRule.onNodeWithText(string(android.R.string.cancel)).performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) { !dialogShown() }
    // 取消之后要是照样发命令，一秒之内就会收到（确认那条里，点「继续」到收到命令远不到一秒）
    runCatching { composeTestRule.waitUntil(timeoutMillis = 1_000) { commandsArrived() } }

    assertThat(commands.toList()).isEmpty()
  }

  companion object {
    private const val TRANSFER_CODE = "tellomi://rereg?uuid=0f0e0d0c&pub_key=BQ"
  }
}
