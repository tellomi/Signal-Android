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

  /** Tellomi（tellomi/tellomi#947）：群邀请码（`tell.cc/g#…`、旧 `signal.group/#…`）——直接进加群。 */
  class GroupInvite(val url: String) : QrScanResult()

  /** Tellomi（tellomi/tellomi#947）：不是 Tellomi 的码——显示内容 +「打开」/「复制」，不再一律「二维码无效」。 */
  class OtherContent(val text: String) : QrScanResult()

  object QrNotFound : QrScanResult()
}
