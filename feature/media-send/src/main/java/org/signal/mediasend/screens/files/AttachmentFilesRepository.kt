/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import androidx.compose.runtime.Immutable

/**
 * Tellomi（tellomi/tellomi#1121 F-6、F-9）：附件 Sheet「文件」页的数据，由 app 层实现。
 * 只查本机数据库——端到端加密，服务端没有明文，不能照 Telegram 去服务端查「我发过的文件」。
 */
interface AttachmentFilesRepository {
  /** 我发出的文件类附件：新的在前、按内容去重（同一份文件发给几个人只列一次，留最新的一次），最多 [limit] 条。 */
  suspend fun getRecentSentFiles(limit: Int): List<RecentSentFile>

  /** 单个文件的上限（字节），读服务端下发的配置，不写死。 */
  fun getMaxFileSize(): Long
}

/**
 * 「最近发送的文件」里的一项。
 *
 * @param attachmentId 本机附件行号（再发时从这一行读文件）。
 * @param isOnDevice 本机还有这份文件（没被存储管理清掉），才能再发。
 */
@Immutable
data class RecentSentFile(
  val attachmentId: Long,
  val fileName: String,
  val size: Long,
  val sentAt: Long,
  val isOnDevice: Boolean
)
