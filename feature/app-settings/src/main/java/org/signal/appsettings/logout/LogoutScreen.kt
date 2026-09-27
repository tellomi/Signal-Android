/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.logout

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.signal.appsettings.R
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.ui.R as CoreUiR

@VisibleForTesting
object LogoutTestTags {
  const val SCROLLER = "logout-scroller"
  const val INTRO = "logout-intro"
  const val ROW_SCREEN_LOCK = "logout-row-screen-lock"
  const val ROW_MANAGE_STORAGE = "logout-row-manage-storage"
  const val ROW_CHANGE_PHONE_NUMBER = "logout-row-change-phone-number"
  const val ROW_UNLINK_DEVICES = "logout-row-unlink-devices"
  const val ROW_LOG_OUT = "logout-row-log-out"
  const val DIALOG_CONFIRM_LOGOUT = "logout-dialog-confirm"
  const val BUTTON_LOG_OUT = "logout-button-log-out"
  const val BUTTON_LOG_OUT_AND_DELETE = "logout-button-log-out-and-delete"
  const val BUTTON_CANCEL = "logout-button-cancel"
  const val DIALOG_CONFIRM_DELETE = "logout-dialog-confirm-delete"
  const val DIALOG_NEEDS_NETWORK = "logout-dialog-needs-network"
}

/**
 * Tellomi（ADR-0072 §四、需求 §3.2，tellomi/tellomi#1414）：设置 → 账号 →「退出登录」进来的替代方案页。
 *
 * 交互照 Telegram 的「退出登录」页（先列几条可能真正解决问题的办法，最后才是红色的退出），视觉全部用现有的设置页组件：
 * 三条替代方案打开现有的页面（屏幕锁定 / 管理存储空间 / 更换手机号）；有已链接设备时多一个「同时让已链接的设备退出」；
 * 最下面红色「退出登录」弹确认框——默认「退出登录」保留本机聊天记录，「退出并删除本机数据」要再确认一次。
 * 多账号上线前没有「添加账号」（ADR-0072 §六）。
 */
@Composable
fun LogoutScreen(
  state: LogoutState,
  onEvent: (LogoutEvent) -> Unit
) {
  Scaffolds.Settings(
    title = stringResource(R.string.TellomiLogout__log_out),
    onNavigationClick = { onEvent(LogoutEvent.NavigateBackClicked) },
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { contentPadding ->
    LazyColumn(
      modifier = Modifier
        .padding(contentPadding)
        .testTag(LogoutTestTags.SCROLLER)
    ) {
      item {
        Text(
          text = stringResource(R.string.TellomiLogout__intro),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .padding(horizontal = dimensionResource(CoreUiR.dimen.gutter), vertical = 16.dp)
            .testTag(LogoutTestTags.INTRO)
        )
      }

      if (state.showScreenLock) {
        item {
          Rows.TextRow(
            text = stringResource(R.string.TellomiLogout__screen_lock),
            label = stringResource(R.string.TellomiLogout__screen_lock_description),
            onClick = { onEvent(LogoutEvent.ScreenLockClicked) },
            modifier = Modifier.testTag(LogoutTestTags.ROW_SCREEN_LOCK)
          )
        }
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.TellomiLogout__manage_storage),
          label = stringResource(R.string.TellomiLogout__manage_storage_description),
          onClick = { onEvent(LogoutEvent.ManageStorageClicked) },
          modifier = Modifier.testTag(LogoutTestTags.ROW_MANAGE_STORAGE)
        )
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.TellomiLogout__change_number),
          label = stringResource(R.string.TellomiLogout__change_number_description),
          onClick = { onEvent(LogoutEvent.ChangePhoneNumberClicked) },
          modifier = Modifier.testTag(LogoutTestTags.ROW_CHANGE_PHONE_NUMBER)
        )
      }

      item {
        Dividers.Default()
      }

      if (state.hasLinkedDevices) {
        item {
          Rows.ToggleRow(
            checked = state.unlinkDevices,
            text = stringResource(R.string.TellomiLogout__also_log_out_linked_devices),
            onCheckChanged = { onEvent(LogoutEvent.UnlinkDevicesToggled(it)) },
            modifier = Modifier.testTag(LogoutTestTags.ROW_UNLINK_DEVICES)
          )
        }
      }

      item {
        Rows.TextRow(
          text = {
            Text(
              text = stringResource(R.string.TellomiLogout__log_out),
              style = MaterialTheme.typography.bodyLarge,
              color = SignalTheme.colors.colorAlert
            )
          },
          onClick = { onEvent(LogoutEvent.LogoutClicked) },
          modifier = Modifier.testTag(LogoutTestTags.ROW_LOG_OUT)
        )
      }
    }
  }

  when (state.dialog) {
    LogoutState.Dialog.None -> Unit
    LogoutState.Dialog.ConfirmLogout -> ConfirmLogoutDialog(onEvent)
    LogoutState.Dialog.ConfirmDeleteLocalData -> {
      Dialogs.SimpleAlertDialog(
        title = stringResource(R.string.TellomiLogout__delete_title),
        body = stringResource(R.string.TellomiLogout__delete_body),
        confirm = stringResource(R.string.TellomiLogout__delete_and_log_out),
        dismiss = stringResource(R.string.TellomiLogout__cancel),
        onConfirm = { onEvent(LogoutEvent.DeleteLocalDataConfirmed) },
        onDeny = { onEvent(LogoutEvent.DialogDismissed) },
        onDismissRequest = { onEvent(LogoutEvent.DialogDismissed) },
        confirmColor = SignalTheme.colors.colorAlert,
        modifier = Modifier.testTag(LogoutTestTags.DIALOG_CONFIRM_DELETE)
      )
    }
    LogoutState.Dialog.InProgress -> Dialogs.IndeterminateProgressDialog()
    LogoutState.Dialog.NeedsNetwork -> {
      Dialogs.SimpleMessageDialog(
        message = stringResource(R.string.TellomiLogout__needs_network),
        dismiss = stringResource(android.R.string.ok),
        onDismiss = { onEvent(LogoutEvent.DialogDismissed) },
        modifier = Modifier.testTag(LogoutTestTags.DIALOG_NEEDS_NETWORK)
      )
    }
  }
}

/**
 * 「退出登录？」三个选项竖排：默认的「退出登录」在最上面，红色的「退出并删除本机数据」其次，「取消」最后。
 * 上游的两按钮对话框放不下第三个，这里在同一个对话框外观里把按钮区换成一列。
 */
@Composable
private fun ConfirmLogoutDialog(onEvent: (LogoutEvent) -> Unit) {
  Dialogs.BaseAlertDialog(
    onDismissRequest = { onEvent(LogoutEvent.DialogDismissed) },
    title = { Text(text = stringResource(R.string.TellomiLogout__confirm_title)) },
    text = { Text(text = stringResource(R.string.TellomiLogout__confirm_body)) },
    confirmButton = {
      Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.fillMaxWidth()
      ) {
        DialogButton(
          text = stringResource(R.string.TellomiLogout__log_out),
          onClick = { onEvent(LogoutEvent.LogoutConfirmed) },
          modifier = Modifier.testTag(LogoutTestTags.BUTTON_LOG_OUT)
        )
        DialogButton(
          text = stringResource(R.string.TellomiLogout__log_out_and_delete_data),
          color = SignalTheme.colors.colorAlert,
          onClick = { onEvent(LogoutEvent.LogoutAndDeleteClicked) },
          modifier = Modifier.testTag(LogoutTestTags.BUTTON_LOG_OUT_AND_DELETE)
        )
        DialogButton(
          text = stringResource(R.string.TellomiLogout__cancel),
          onClick = { onEvent(LogoutEvent.DialogDismissed) },
          modifier = Modifier.testTag(LogoutTestTags.BUTTON_CANCEL)
        )
      }
    },
    modifier = Modifier.testTag(LogoutTestTags.DIALOG_CONFIRM_LOGOUT)
  )
}

@Composable
private fun DialogButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified
) {
  TextButton(onClick = onClick, modifier = modifier) {
    Text(text = text, color = color)
  }
}

@DayNightPreviews
@Composable
private fun LogoutScreenPreview() {
  Previews.Preview {
    LogoutScreen(state = LogoutState(hasLinkedDevices = true), onEvent = {})
  }
}

@DayNightPreviews
@Composable
private fun LogoutScreenConfirmPreview() {
  Previews.Preview {
    LogoutScreen(state = LogoutState(dialog = LogoutState.Dialog.ConfirmLogout), onEvent = {})
  }
}
