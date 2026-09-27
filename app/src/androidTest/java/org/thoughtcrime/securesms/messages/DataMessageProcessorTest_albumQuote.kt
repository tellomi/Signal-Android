/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.messages

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import okio.ByteString.Companion.toByteString
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.models.database.AttachmentId
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.DataMessage
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Tellomi（tellomi/tellomi#1257，owner 2026-09-26「回复整条」）：对方引用我发的相册时，不管对方带来的缩略图是哪一张，
 * 都照上游用本机原消息里的图（防伪）；单张与没带缩略图时同样。
 */
@Suppress("ClassName")
@RunWith(AndroidJUnit4::class)
class DataMessageProcessorTest_albumQuote {

  @get:Rule
  val harness = SignalActivityRule()

  private lateinit var alice: Recipient
  private var threadId = 0L

  @Before
  fun setUp() {
    alice = Recipient.resolved(harness.others[0])
    threadId = SignalDatabase.threads.getOrCreateThreadIdFor(alice)
  }

  @Test
  fun quoteOfAnAlbum_keepsTheLocalFirstItemEvenWithTheSendersThumbnail() {
    val (sentAt, firstId) = insertOutgoingImages(count = 3)

    val quote = validatedQuote(sentAt, thumbnailKey = randomKey())

    assertThat(quote.isOriginalMissing).isFalse()
    assertThat(quote.attachment).isNotNull().isInstanceOf(DatabaseAttachment::class)
    assertThat((quote.attachment as DatabaseAttachment).attachmentId).isEqualTo(firstId)
  }

  @Test
  fun quoteOfASingleImage_keepsTheLocalImage() {
    val (sentAt, firstId) = insertOutgoingImages(count = 1)

    val quote = validatedQuote(sentAt, thumbnailKey = randomKey())

    assertThat(quote.attachment).isNotNull().isInstanceOf(DatabaseAttachment::class)
    assertThat((quote.attachment as DatabaseAttachment).attachmentId).isEqualTo(firstId)
  }

  @Test
  fun quoteOfAnAlbumWithoutAThumbnail_keepsTheLocalFirstItem() {
    val (sentAt, firstId) = insertOutgoingImages(count = 3)

    val quote = validatedQuote(sentAt, thumbnailKey = null)

    assertThat(quote.attachment).isNotNull().isInstanceOf(DatabaseAttachment::class)
    assertThat((quote.attachment as DatabaseAttachment).attachmentId).isEqualTo(firstId)
  }

  private fun validatedQuote(sentAt: Long, thumbnailKey: ByteArray?): QuoteModel {
    val thumbnail = thumbnailKey?.let {
      AttachmentPointer(
        cdnKey = "tellomi-album-item",
        cdnNumber = 3,
        contentType = "image/jpeg",
        key = it.toByteString(),
        size = 2048,
        digest = ByteArray(32).toByteString(),
        width = 150,
        height = 200
      )
    }
    val timestamp = System.currentTimeMillis()
    val message = DataMessage(
      body = "回复第三张",
      timestamp = timestamp,
      quote = DataMessage.Quote(
        id = sentAt,
        authorAci = harness.self.requireAci().toString(),
        text = "",
        attachments = listOf(DataMessage.Quote.QuotedAttachment(contentType = "image/jpeg", thumbnail = thumbnail))
      )
    )
    return DataMessageProcessor.getValidatedQuote(harness.context, timestamp, message, alice, alice)!!
  }

  /** 自己发给 alice 的 [count] 张图（带真实数据）；返回发送时间与按顺序的第一张。 */
  private fun insertOutgoingImages(count: Int): Pair<Long, AttachmentId> {
    val now = System.currentTimeMillis()
    val attachments = List(count) { i ->
      val bytes = jpeg(i)
      UriAttachment(
        dataUri = AppDependencies.blobs.forData(bytes).createForSingleSessionInMemory(),
        contentType = "image/jpeg",
        transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
        size = bytes.size.toLong(),
        width = 40,
        height = 30,
        fileName = null,
        fastPreflightId = null,
        voiceNote = false,
        borderless = false,
        videoGif = false,
        quote = false,
        quoteTargetContentType = null,
        caption = null,
        stickerLocator = null,
        blurHash = null,
        audioHash = null,
        transformProperties = null,
        uuid = null
      )
    }
    val message = OutgoingMessage(threadRecipient = alice, sentTimeMillis = now, body = "", isSecure = true, attachments = attachments)
    val messageId = SignalDatabase.messages.insertMessageOutbox(message, threadId, false, null).messageId
    SignalDatabase.messages.markAsSent(messageId, true)
    val first = SignalDatabase.attachments.getAttachmentsForMessage(messageId).sortedBy { it.displayOrder }.first()
    return now to first.attachmentId
  }

  private fun jpeg(number: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.HSVToColor(floatArrayOf((number * 70) % 360f, 0.6f, 0.9f)))
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
      out.toByteArray()
    }
  }

  private fun randomKey(): ByteArray = ByteArray(64).also { SecureRandom().nextBytes(it) }
}
