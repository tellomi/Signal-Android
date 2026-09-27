/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.signal.core.ui.compose.Dialogs

/**
 * Tellomi：相机扫到新手机上的「转移帐户」码（`tellomi://rereg`、旧 `sgnl://rereg`）时，进转移页之前先问一句。
 *
 * 上游直接进转移页：只剩一个「转移帐户」按钮，设备没锁屏时连认证都跳过，一点就把手机号、帐户熵池、PIN、身份私钥
 * 发给显示码的那台手机，对方不用短信验证码就能注册这个号。同一处扫到「关联设备」码、用户名码都先确认，唯独这个没有。
 * 国内「扫一扫加好友」是日常动作，被人一句「扫我的码加个好友」骗去扫的场景要挡住。
 * 新手机上的说明仍然教用户用相机扫，所以不改入口，只在前面加这一步。
 */
@Composable
internal fun TellomiReRegistrationScannedDialog(
  onConfirm: () -> Unit,
  onDeny: () -> Unit,
  onDismissRequest: () -> Unit
) {
  Dialogs.SimpleAlertDialog(
    title = stringResource(R.string.TellomiReRegistrationScannedDialog__title),
    body = stringResource(R.string.TellomiReRegistrationScannedDialog__body),
    confirm = stringResource(R.string.TellomiReRegistrationScannedDialog__continue),
    dismiss = stringResource(android.R.string.cancel),
    onConfirm = onConfirm,
    onDeny = onDeny,
    onDismissRequest = onDismissRequest
  )
}
