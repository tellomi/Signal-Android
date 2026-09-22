package org.thoughtcrime.securesms.components.settings.app.usernamelinks.main

import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Result of taking data from the QR scanner and trying to resolve it to a recipient.
 */
sealed class QrScanResult {
  class Success(val recipient: Recipient) : QrScanResult()

  class NotFound(val username: String?) : QrScanResult()

  object InvalidData : QrScanResult()

  /**
   * Tellomi（#947）：扫到的是**设备配对码**，不是联系人名片。
   * 上游这里一律回 InvalidData（「二维码无效」），用户不知道自己扫错了入口——owner 撞过。
   */
  object DeviceLinkCode : QrScanResult()

  object NetworkError : QrScanResult()

  object QrNotFound : QrScanResult()
}
