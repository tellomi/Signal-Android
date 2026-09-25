/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.LocalDisplayNameProvider
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.screens.edit.MediaEditScreenDialogs
import org.signal.mediasend.screens.select.AttachmentSheetState
import org.signal.mediasend.screens.select.LocalAttachmentSheetState

@Composable
fun MediaSendScreen(
  contractArgs: MediaSendFlowActivityContract.Args,
  modifier: Modifier = Modifier,
  textStoryEditorSlot: @Composable () -> Unit = {},
  sendSlot: @Composable (MediaSendFlowState) -> Unit = {},
  onExternalHudCommand: (MediaSendFlowHudCommand) -> Unit = {}
) {
  val viewModel = viewModel<MediaSendFlowViewModel>(factory = MediaSendFlowViewModel.Factory(args = contractArgs))

  // Tellomi（tellomi/tellomi#1115）：从「+」打开的附件 Sheet——窗口透明、聊天露在上面，选图网格是 Sheet。
  val attachmentSheetState = remember { if (contractArgs.attachmentSheet != null) AttachmentSheetState() else null }

  LaunchedEffect(viewModel) {
    viewModel.hudCommands.collect { command ->
      onExternalHudCommand(command)
    }
  }

  SignalTheme {
    CompositionLocalProvider(
      LocalNavigationEventDispatcherOwner provides LocalActivity.current as NavigationEventDispatcherOwner,
      LocalAttachmentSheetState provides attachmentSheetState
    ) {
      Surface(
        color = if (attachmentSheetState != null) Color.Transparent else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
      ) {
        viewModel.usernameScannedDialog.Content { username, onDismissRequest, onConfirm, _, onDeny ->
          Dialogs.SimpleAlertDialog(
            title = stringResource(R.string.UsernameScannedDialog__username_dialog_title, username),
            body = stringResource(R.string.UsernameScannedDialog__username_dialog_body, username),
            confirm = stringResource(R.string.UsernameScannedDialog__username_dialog_go_to_chat_button),
            onConfirm = onConfirm,
            onDeny = onDeny,
            onDismissRequest = onDismissRequest
          )
        }
        viewModel.linkedDeviceScannedDialog.Content { _, onDismissRequest, onConfirm, _, onDeny ->
          Dialogs.SimpleAlertDialog(
            title = stringResource(R.string.LinkedDeviceScannedDialog__device_link_dialog_title),
            body = stringResource(R.string.LinkedDeviceScannedDialog__it_looks_like_youre_trying),
            confirm = stringResource(R.string.LinkedDeviceScannedDialog__device_link_dialog_continue),
            onConfirm = onConfirm,
            onDeny = onDeny,
            onDismissRequest = onDismissRequest
          )
        }

        viewModel.addToGroupStoryDialog.Content { recipientId, onDismissRequest, onConfirm, _, onDeny ->
          val groupName: String by LocalDisplayNameProvider.current(recipientId.id)

          MediaEditScreenDialogs.AddToGroupStoryConfirmationDialog(
            groupName = groupName,
            onAddToStory = onConfirm,
            onDeny = onDeny,
            onDismissRequest = onDismissRequest
          )
        }

        MediaSendNavigation(
          viewModel = viewModel,
          modifier = modifier,
          textStoryEditorSlot = textStoryEditorSlot,
          sendSlot = sendSlot
        )
      }
    }
  }
}
