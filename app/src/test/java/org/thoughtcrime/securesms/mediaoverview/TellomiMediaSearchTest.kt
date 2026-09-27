/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediaoverview

import android.app.Application
import androidx.core.content.contentValuesOf
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.loaders.GroupedThreadMediaLoader
import org.thoughtcrime.securesms.database.loaders.MediaLoader
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * Tellomi（tellomi/tellomi#1174，需求 official-account-and-saved §3.2 第 2 条）：「我的收藏」的所有媒体里按类型搜。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiMediaSearchTest {

  @get:Rule
  val recipients = RecipientTestRule()

  @Test
  fun `words in the middle of a chinese sentence are found, in file names, captions, text and links`() {
    assertThat(TellomiMediaSearch.matches("审完", fileName = null, caption = null, body = "7 个 PR 审完了", linkPreviewJson = null)).isTrue()
    assertThat(TellomiMediaSearch.matches("报告", fileName = "第三季度报告.pdf", caption = null, body = null, linkPreviewJson = null)).isTrue()
    assertThat(TellomiMediaSearch.matches("REPORT", fileName = "q3-report.pdf", caption = null, body = null, linkPreviewJson = null)).isTrue()
    assertThat(TellomiMediaSearch.matches("海边", fileName = null, caption = "去年夏天在海边", body = null, linkPreviewJson = null)).isTrue()

    val link = """[{"url":"https://tellomi.app","title":"Tellomi — 越聊，越懂你。","description":"私聊、群聊、联系人"}]"""
    assertThat(TellomiMediaSearch.matches("越懂", null, null, null, link)).isTrue()
    assertThat(TellomiMediaSearch.matches("tellomi.app", null, null, null, link)).isTrue()
    assertThat(TellomiMediaSearch.matches("群聊", null, null, null, link)).isTrue()

    assertThat(TellomiMediaSearch.matches("发票", fileName = "第三季度报告.pdf", caption = "去年夏天", body = "7 个 PR 审完了", linkPreviewJson = link)).isFalse()
  }

  @Test
  fun `a blank query keeps everything`() {
    assertThat(TellomiMediaSearch.isActive(null)).isFalse()
    assertThat(TellomiMediaSearch.isActive("  ")).isFalse()
    assertThat(TellomiMediaSearch.matches("  ", null, null, null, null)).isTrue()
  }

  @Test
  fun `the files page keeps only the files whose name or message text matches`() {
    val thread = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipients.self))
    saveFile("第三季度报告.pdf", body = null)
    saveFile("发票.pdf", body = "报销用的")
    saveFile("scan.pdf", body = "给会计的季度报告")

    assertThat(fileNames(thread, query = null)).containsExactlyInAnyOrder("第三季度报告.pdf", "发票.pdf", "scan.pdf")
    assertThat(fileNames(thread, query = "报告")).containsExactlyInAnyOrder("第三季度报告.pdf", "scan.pdf")
    assertThat(fileNames(thread, query = "报销")).containsExactlyInAnyOrder("发票.pdf")
  }

  private fun saveFile(fileName: String, body: String?) {
    val messageId = recipients.insertOutgoingMessage(recipients.self, body = body ?: "")
    SignalDatabase.writableDatabase.insert(
      AttachmentTable.TABLE_NAME,
      null,
      contentValuesOf(
        AttachmentTable.MESSAGE_ID to messageId,
        AttachmentTable.CONTENT_TYPE to "application/pdf",
        AttachmentTable.FILE_NAME to fileName,
        AttachmentTable.TRANSFER_STATE to AttachmentTable.TRANSFER_PROGRESS_DONE,
        AttachmentTable.DATA_FILE to "/tellomi-test/$messageId"
      )
    )
  }

  private fun fileNames(threadId: Long, query: String?): List<String?> {
    val loader = GroupedThreadMediaLoader(ApplicationProvider.getApplicationContext(), threadId, MediaLoader.MediaType.DOCUMENT, MediaTable.Sorting.Newest, 0, query)
    val media = loader.loadInBackground()!!
    return (0 until media.sectionCount).flatMap { section ->
      (0 until media.getSectionItemCount(section)).map { item -> media.get(section, item).attachment?.fileName }
    }
  }
}
