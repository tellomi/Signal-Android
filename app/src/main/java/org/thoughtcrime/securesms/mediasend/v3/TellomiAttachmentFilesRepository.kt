/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.webkit.MimeTypeMap
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.requireBoolean
import org.signal.core.util.requireLong
import org.signal.core.util.requireString
import org.signal.mediasend.screens.files.AttachmentFilesRepository
import org.signal.mediasend.screens.files.RecentSentFile
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.PushMediaConstraints

/**
 * Tellomi（tellomi/tellomi#1121 F-6、F-9）：附件 Sheet「文件」页的数据——本机数据库里我发出的文件类附件。
 *
 * 判据同上游「所有媒体 → 文件」（`MediaTable.DOCUMENT_MEDIA_QUERY`：不是图片 / 视频 / 音频 / 长文本，svg 算文件；
 * 不含一次性查看、快拍、编辑前的旧版本、引用里的缩略图、贴纸、定时未发的），只是不按会话、只要我发出的。
 * 本机已经没有文件的（被存储管理清掉了）也列出来，标成不在本机。
 */
object TellomiAttachmentFilesRepository : AttachmentFilesRepository {

  override suspend fun getRecentSentFiles(limit: Int): List<RecentSentFile> = withContext(Dispatchers.IO) {
    query(limit)
  }

  /** 服务端下发的附件上限（`global.attachments.maxBytes`），和会话页发文件时的校验是同一个值。 */
  override fun getMaxFileSize(): Long = PushMediaConstraints(null).documentMaxSize

  private val SQL = """
    SELECT
      a.${AttachmentTable.ID} AS attachment_id,
      a.${AttachmentTable.FILE_NAME} AS file_name,
      a.${AttachmentTable.CONTENT_TYPE} AS content_type,
      a.${AttachmentTable.DATA_SIZE} AS data_size,
      a.${AttachmentTable.DATA_FILE} IS NOT NULL AS on_device,
      COALESCE(a.${AttachmentTable.DATA_HASH_END}, a.${AttachmentTable.DATA_HASH_START}) AS content_hash,
      m.${MessageTable.DATE_SENT} AS date_sent
    FROM ${AttachmentTable.TABLE_NAME} AS a
    INNER JOIN ${MessageTable.TABLE_NAME} AS m ON a.${AttachmentTable.MESSAGE_ID} = m.${MessageTable.ID}
    WHERE
      (m.${MessageTable.TYPE} & ${MessageTypes.BASE_TYPE_MASK}) IN (${MessageTypes.OUTGOING_MESSAGE_TYPES.joinToString(",")}) AND
      m.${MessageTable.VIEW_ONCE} = 0 AND
      m.${MessageTable.STORY_TYPE} = 0 AND
      m.${MessageTable.LATEST_REVISION_ID} IS NULL AND
      m.${MessageTable.SCHEDULED_DATE} < 0 AND
      a.${AttachmentTable.QUOTE} = 0 AND
      a.${AttachmentTable.STICKER_PACK_ID} IS NULL AND
      (
        a.${AttachmentTable.CONTENT_TYPE} LIKE 'image/svg%' OR
        (
          a.${AttachmentTable.CONTENT_TYPE} NOT LIKE 'image/%' AND
          a.${AttachmentTable.CONTENT_TYPE} NOT LIKE 'video/%' AND
          a.${AttachmentTable.CONTENT_TYPE} NOT LIKE 'audio/%' AND
          a.${AttachmentTable.CONTENT_TYPE} NOT LIKE 'text/x-signal-plain'
        )
      )
    ORDER BY m.${MessageTable.DATE_SENT} DESC, a.${AttachmentTable.ID} DESC
  """

  /**
   * 新的在前，按内容去重（同一份文件发给几个人是几行附件、同一个内容哈希，只留最新的一次；没算出哈希的按行各算一个），
   * 最多 [limit] 条。
   */
  @VisibleForTesting
  fun query(limit: Int): List<RecentSentFile> {
    val seen = HashSet<String>()
    val files = ArrayList<RecentSentFile>()
    SignalDatabase.readableDatabase.rawQuery(SQL, arrayOf<String>()).use { cursor ->
      while (files.size < limit && cursor.moveToNext()) {
        val attachmentId = cursor.requireLong("attachment_id")
        val key = cursor.requireString("content_hash") ?: "row:$attachmentId"
        if (!seen.add(key)) {
          continue
        }
        files += RecentSentFile(
          attachmentId = attachmentId,
          fileName = displayName(cursor.requireString("file_name"), cursor.requireString("content_type")),
          size = cursor.requireLong("data_size"),
          sentAt = cursor.requireLong("date_sent"),
          isOnDevice = cursor.requireBoolean("on_device")
        )
      }
    }
    return files
  }

  /** 没有原文件名的（很少见）：「未命名文件」+ 按类型补的扩展名。 */
  @VisibleForTesting
  fun displayName(fileName: String?, contentType: String?): String {
    fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val base = AppDependencies.application.getString(R.string.DocumentView_unnamed_file)
    val fileExtension = contentType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    return if (fileExtension.isNullOrEmpty()) base else "$base.$fileExtension"
  }
}
