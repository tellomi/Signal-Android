/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediaoverview

import androidx.annotation.WorkerThread
import org.json.JSONArray
import org.json.JSONException
import org.signal.core.util.readToList
import org.signal.core.util.requireLong
import org.signal.core.util.requireString
import org.signal.core.util.select
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase

/**
 * Tellomi：「我的收藏」的所有媒体里按类型搜（tellomi/tellomi#1174，需求 official-account-and-saved §3.2 第 2 条「可按类型筛」）。
 *
 * 在当前这一页（媒体 / 文件 / 音频 / 链接 / 全部）里按文字找：文件名、说明文字、消息正文、链接的网址 / 标题 / 描述。
 * 用「包含」匹配，所以中文句子中间的词也找得到。只在自己的会话里开（MediaOverviewActivity 决定显不显示搜索）。
 */
object TellomiMediaSearch {

  /** 空白的查询 = 不筛。 */
  @JvmStatic
  fun isActive(query: String?): Boolean = !query.isNullOrBlank()

  @JvmStatic
  fun matches(record: MediaTable.MediaRecord, query: String?, bodies: Map<Long, String>): Boolean {
    return matches(
      query = query,
      fileName = record.attachment?.fileName,
      caption = record.attachment?.caption,
      body = bodies[record.messageId],
      linkPreviewJson = record.linkPreviewJson
    )
  }

  fun matches(query: String?, fileName: String?, caption: String?, body: String?, linkPreviewJson: String?): Boolean {
    val needle = query?.trim().orEmpty()
    if (needle.isEmpty()) {
      return true
    }
    val texts = listOf(fileName, caption, body) + linkPreviewTexts(linkPreviewJson)
    return texts.any { it != null && it.contains(needle, ignoreCase = true) }
  }

  private fun linkPreviewTexts(json: String?): List<String?> {
    if (json.isNullOrEmpty()) {
      return emptyList()
    }
    return try {
      val previews = JSONArray(json)
      (0 until previews.length()).flatMap { i ->
        val preview = previews.optJSONObject(i) ?: return@flatMap emptyList<String?>()
        listOf(preview.optString("url"), preview.optString("title"), preview.optString("description"))
      }
    } catch (e: JSONException) {
      emptyList()
    }
  }

  /** 这个会话里每条消息的正文（按消息 id），搜的时候查一次。 */
  @JvmStatic
  @WorkerThread
  fun bodies(threadId: Long): Map<Long, String> {
    return SignalDatabase.readableDatabase
      .select(MessageTable.ID, MessageTable.BODY)
      .from(MessageTable.TABLE_NAME)
      .where("${MessageTable.THREAD_ID} = ? AND ${MessageTable.BODY} IS NOT NULL", threadId)
      .run()
      .readToList { it.requireLong(MessageTable.ID) to it.requireString(MessageTable.BODY) }
      .mapNotNull { (id, body) -> body?.let { id to it } }
      .toMap()
  }
}
