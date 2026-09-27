/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.app.Application
import android.content.ContentValues
import android.webkit.MimeTypeMap
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.TestSms
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger

/**
 * Tellomi（tellomi/tellomi#1121 F-6）：「最近发送的文件」的查询——只要我发出的文件类附件，新的在前，按内容去重，
 * 本机已经没有文件的标成不在本机。判据同上游「所有媒体 → 文件」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiAttachmentFilesRepositoryTest {

  @get:Rule
  val signalDatabaseRule = SignalDatabaseRule()

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      Log.initialize(SystemOutLogger())
    }
  }

  @Test
  fun `Given sent and received attachments, when queried, then only my sent files are listed, newest first`() {
    val older = sent(at = 100)
    val newer = sent(at = 300)
    val received = received(at = 400)
    attach(older, "a.pdf", "application/pdf", hash = "h-a")
    attach(newer, "b.zip", "application/zip", hash = "h-b")
    attach(received, "theirs.pdf", "application/pdf", hash = "h-theirs")

    val files = TellomiAttachmentFilesRepository.query(limit = 100)

    assertThat(files.map { it.fileName }).containsExactly("b.zip", "a.pdf")
    assertThat(files.map { it.sentAt }).containsExactly(300L, 100L)
  }

  @Test
  fun `Given photos, videos, voice notes and long text, when queried, then they are not files but svg is`() {
    val message = sent(at = 100)
    attach(message, "p.jpg", "image/jpeg", hash = "h1")
    attach(message, "v.mp4", "video/mp4", hash = "h2")
    attach(message, "voice.aac", "audio/aac", hash = "h3")
    attach(message, "long.txt", "text/x-signal-plain", hash = "h4")
    attach(message, "logo.svg", "image/svg+xml", hash = "h5")
    attach(message, "notes.txt", "text/plain", hash = "h6")

    assertThat(TellomiAttachmentFilesRepository.query(limit = 100).map { it.fileName }).containsExactly("notes.txt", "logo.svg")
  }

  @Test
  fun `Given view once, story, old edit, scheduled, quote and sticker attachments, when queried, then none of them are listed`() {
    val viewOnce = sent(at = 100, extra = { put(MessageTable.VIEW_ONCE, 1) })
    attach(viewOnce, "once.pdf", "application/pdf", hash = "h1")
    attach(sent(at = 110, extra = { put(MessageTable.STORY_TYPE, 1) }), "story.pdf", "application/pdf", hash = "h2")
    attach(sent(at = 120, extra = { put(MessageTable.LATEST_REVISION_ID, viewOnce) }), "old-edit.pdf", "application/pdf", hash = "h3")
    attach(sent(at = 130, extra = { put(MessageTable.SCHEDULED_DATE, 9_999_999_999_999L) }), "later.pdf", "application/pdf", hash = "h4")
    attach(sent(at = 140), "quoted.pdf", "application/pdf", hash = "h5", extra = { put(AttachmentTable.QUOTE, 1) })
    attach(sent(at = 150), "sticker.webp", "application/octet-stream", hash = "h6", extra = { put(AttachmentTable.STICKER_PACK_ID, "pack") })
    attach(sent(at = 160), "kept.pdf", "application/pdf", hash = "h7")

    assertThat(TellomiAttachmentFilesRepository.query(limit = 100).map { it.fileName }).containsExactly("kept.pdf")
  }

  @Test
  fun `Given the same file sent to several chats, when queried, then it is listed once with the latest send`() {
    attach(sent(at = 100), "contract.pdf", "application/pdf", hash = "same")
    attach(sent(at = 300), "contract.pdf", "application/pdf", hash = "same")
    attach(sent(at = 200), "other.pdf", "application/pdf", hash = "different")
    attach(sent(at = 150), "unhashed.pdf", "application/pdf", hash = null)
    attach(sent(at = 140), "unhashed.pdf", "application/pdf", hash = null)

    val files = TellomiAttachmentFilesRepository.query(limit = 100)

    assertThat(files.map { it.fileName to it.sentAt }).containsExactly(
      "contract.pdf" to 300L,
      "other.pdf" to 200L,
      "unhashed.pdf" to 150L,
      "unhashed.pdf" to 140L
    )
  }

  @Test
  fun `Given a file whose data was cleaned up, when queried, then it is listed as no longer on the device`() {
    attach(sent(at = 200), "here.pdf", "application/pdf", hash = "h1")
    attach(sent(at = 100), "gone.pdf", "application/pdf", hash = "h2", dataFile = null)

    val files = TellomiAttachmentFilesRepository.query(limit = 100)

    assertThat(files.map { it.fileName to it.isOnDevice }).containsExactly("here.pdf" to true, "gone.pdf" to false)
    assertThat(files[0].size).isEqualTo(1_234L)
  }

  @Test
  fun `Given more files than the limit, when queried, then only the newest are listed`() {
    (0 until 5).forEach { index -> attach(sent(at = 100L + index), "$index.pdf", "application/pdf", hash = "h$index") }

    assertThat(TellomiAttachmentFilesRepository.query(limit = 3).map { it.fileName }).containsExactly("4.pdf", "3.pdf", "2.pdf")
  }

  @Test
  fun `Given a file without a name, when shown, then it gets the unnamed file name with an extension from its type`() {
    shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("pdf", "application/pdf")

    assertThat(TellomiAttachmentFilesRepository.displayName(" plan.xlsx ", null)).isEqualTo("plan.xlsx")
    assertThat(TellomiAttachmentFilesRepository.displayName(null, "application/pdf")).isEqualTo("Unnamed file.pdf")
    assertThat(TellomiAttachmentFilesRepository.displayName("  ", "application/x-unknown-thing")).isEqualTo("Unnamed file")
  }

  // region helpers

  private fun sent(at: Long, extra: ContentValues.() -> Unit = {}): Long {
    return message(type = MessageTypes.BASE_SENT_TYPE or MessageTypes.SECURE_MESSAGE_BIT, at = at, extra = extra)
  }

  private fun received(at: Long): Long {
    return message(type = MessageTypes.BASE_INBOX_TYPE or MessageTypes.SECURE_MESSAGE_BIT, at = at, extra = {})
  }

  private fun message(type: Long, at: Long, extra: ContentValues.() -> Unit): Long {
    val id = TestSms.insert(signalDatabaseRule.writeableDatabase, sentTimestampMillis = at, type = type)
    val values = ContentValues().apply(extra)
    if (values.size() > 0) {
      SignalDatabase.writableDatabase.update(MessageTable.TABLE_NAME, values, "${MessageTable.ID} = ?", arrayOf(id.toString()))
    }
    return id
  }

  private fun attach(
    messageId: Long,
    fileName: String,
    contentType: String,
    hash: String?,
    dataFile: String? = "/data/$fileName-$messageId",
    extra: ContentValues.() -> Unit = {}
  ) {
    SignalDatabase.writableDatabase.insert(
      AttachmentTable.TABLE_NAME,
      null,
      ContentValues().apply {
        put(AttachmentTable.MESSAGE_ID, messageId)
        put(AttachmentTable.TRANSFER_STATE, AttachmentTable.TRANSFER_PROGRESS_DONE)
        put(AttachmentTable.CONTENT_TYPE, contentType)
        put(AttachmentTable.FILE_NAME, fileName)
        put(AttachmentTable.DATA_SIZE, 1_234L)
        if (dataFile != null) put(AttachmentTable.DATA_FILE, dataFile)
        if (hash != null) put(AttachmentTable.DATA_HASH_END, hash)
        extra()
      }
    )
  }

  // endregion
}
