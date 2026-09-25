/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.os.Build
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.DynamicTheme

/**
 * Tellomi（tellomi/tellomi#1115）：会话页「+」打开的附件 Sheet。和 [MediaSendV3Activity] 同一个流程，只是窗口透明
 * （聊天露在 Sheet 上面），所以单独一个 Activity——窗口透不透明只能在清单的主题里定。
 *
 * 进场由 Sheet 自己从底部滑上来（会话页启动时窗口不做动画）；退场整个窗口往下滑出、淡出，和会话页插入新消息同时发生。
 */
class MediaSendAttachmentSheetActivity : MediaSendV3Activity() {

  override fun createDynamicTheme(): DynamicTheme = AttachmentSheetTheme()

  override fun finish() {
    super.finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(0, R.anim.tellomi_attachment_sheet_exit)
  }

  override fun setRequestedOrientation(requestedOrientation: Int) {
    // Android 8.0 不许透明窗口固定方向（Only fullscreen opaque activities can request orientation）
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
      return
    }
    super.setRequestedOrientation(requestedOrientation)
  }

  private class AttachmentSheetTheme : DynamicTheme() {
    override fun getTheme(): Int = R.style.Signal_DayNight_NoActionBar_AttachmentSheet
  }
}
