/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkdevice

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.signal.core.ui.compose.Dialogs
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository.LinkDeviceResult

/**
 * Tellomi：关联设备失败的分型与提示（tellomi/tellomi#1219）。
 *
 * 上游的两条关联路径都是**先退出扫码页、再发起关联**（[AddLinkDeviceFragment] 的 onQrCodeApproved、
 * [LinkDeviceSyncBottomSheet] 的 onLink），而失败结果只有扫码页（[LinkDeviceQrScanScreen]）会显示。
 * 结果是失败时「正在关联设备…」转圈消失后什么都没有，要等下次打开扫码页才弹出一个过时的提示。
 * 这里改成在「已关联设备」页（关联进行时用户所在的页面）弹对话框，每一型都给出路。
 */
object TellomiLinkDeviceErrors {

  /**
   * `PUT /v1/provisioning/{address}` 的状态码 → 结果。
   *
   * 404 是服务端上这个关联地址当时没有设备在等（服务端 ProvisioningController.sendProvisioningMessage）：
   * 码过期了，或者是别的服务器的码——Signal Desktop 的码连的是 Signal 的服务器，在我们的服务器上永远是 404。
   * 服务端分不出这两种，所以是同一型。上游把 404 归到网络错误，用户看到的是「网络错误」。其余状态码照上游。
   */
  fun resultForProvisioningStatus(code: Int, error: Throwable): LinkDeviceResult {
    return when (code) {
      403 -> LinkDeviceResult.NoDevice
      404 -> LinkDeviceResult.ExpiredOrForeignCode
      409 -> LinkDeviceResult.NoDevice
      411 -> LinkDeviceResult.LimitExceeded
      else -> LinkDeviceResult.NetworkError(error)
    }
  }

  data class FailureDialog(
    @StringRes val body: Int,
    val canScanAgain: Boolean = true
  )

  /** 失败结果 → 对话框内容；不是失败（None / Success）返回 null。 */
  fun failureDialogFor(result: LinkDeviceResult): FailureDialog? {
    return when (result) {
      LinkDeviceResult.None, is LinkDeviceResult.Success -> null
      // 不点名别的 App（taishi 中转包 8：界面上出不出现「Signal」是品牌决定，先不点名）
      LinkDeviceResult.ExpiredOrForeignCode -> FailureDialog(R.string.AddLinkDeviceFragment__tellomi_code_expired_or_foreign)
      is LinkDeviceResult.NetworkError -> FailureDialog(R.string.AddLinkDeviceFragment__tellomi_network_error)
      LinkDeviceResult.NoDevice -> FailureDialog(R.string.DeviceProvisioningActivity_content_progress_no_device)
      LinkDeviceResult.KeyError -> FailureDialog(R.string.DeviceProvisioningActivity_content_progress_key_error)
      LinkDeviceResult.BadCode -> FailureDialog(R.string.DeviceActivity_sorry_this_is_not_a_valid_device_link_qr_code)
      // 已经到设备数上限：重新扫码结果一样，要先移除一台
      LinkDeviceResult.LimitExceeded -> FailureDialog(R.string.DeviceProvisioningActivity_sorry_you_have_too_many_devices_linked_already, canScanAgain = false)
    }
  }
}

/**
 * 关联失败的对话框。任何一种关闭方式都会调 [onDismiss]（调用方在这里清掉结果）；点「重新扫码」是先 [onDismiss] 再 [onScanAgain]。
 */
@Composable
fun TellomiLinkDeviceFailureDialog(
  result: LinkDeviceResult,
  onScanAgain: () -> Unit,
  onDismiss: () -> Unit
) {
  val dialog = TellomiLinkDeviceErrors.failureDialogFor(result) ?: return
  val title = stringResource(R.string.AddLinkDeviceFragment__linking_device_failed)
  val body = stringResource(dialog.body)

  if (dialog.canScanAgain) {
    Dialogs.SimpleAlertDialog(
      title = title,
      body = body,
      confirm = stringResource(R.string.AddLinkDeviceFragment__tellomi_scan_again),
      onConfirm = onScanAgain,
      dismiss = stringResource(android.R.string.cancel),
      onDismiss = onDismiss
    )
  } else {
    Dialogs.SimpleAlertDialog(
      title = title,
      body = body,
      confirm = stringResource(android.R.string.ok),
      onConfirm = {},
      onDismiss = onDismiss
    )
  }
}
