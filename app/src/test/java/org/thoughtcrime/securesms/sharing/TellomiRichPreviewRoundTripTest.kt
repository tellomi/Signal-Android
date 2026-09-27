/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.sharing

import android.app.Application
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.jobs.PushSendJob
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.messages.DataMessageProcessor
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.util.RemoteConfig
import org.thoughtcrime.securesms.util.toDataMessage
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.Attr
import org.whispersystems.signalservice.internal.push.Preview
import org.whispersystems.signalservice.internal.push.RichContent

/**
 * ADR-0063 §4.5 / §7.4 (tellomi/tellomi#1420): a received `Preview.rich` (field 1000) shows the same snapshot as a
 * preview without it, and its bytes — including fields this build does not know — survive receive → database →
 * forward → database → send unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiRichPreviewRoundTripTest {

  @get:Rule
  val recipients = RecipientTestRule()

  companion object {
    private const val URL = "https://www.bilibili.com/video/BV1GJ411x7h7"
    private const val BODY = "看这个 $URL"

    private val RICH_BYTES: ByteArray = RichContent(
      kind = "video",
      provider = "bilibili",
      schema = 1,
      canonical_url = URL,
      attrs = listOf(Attr(key = "author", value_ = "某位 UP 主"), Attr(key = "duration_ms", value_ = "212000")),
      level = 2
    ).encode() + "from a newer client".toByteArray().let { byteArrayOf(0x9A.toByte(), 0x06, it.size.toByte()) + it } // field 99

    private fun varint(value: Int): ByteArray {
      val out = mutableListOf<Byte>()
      var rest = value
      while (rest >= 0x80) {
        out += ((rest and 0x7F) or 0x80).toByte()
        rest = rest ushr 7
      }
      out += rest.toByte()
      return out.toByteArray()
    }

    /** key = 1000 << 3 | 2, then the length, then the value. */
    private fun field1000(rich: ByteArray): ByteArray = byteArrayOf(0xC2.toByte(), 0x3E) + varint(rich.size) + rich

    private fun received(snapshot: Preview, withRich: Boolean): Preview {
      val bytes = if (withRich) snapshot.encode() + field1000(RICH_BYTES) else snapshot.encode()
      return Preview.ADAPTER.decode(bytes)
    }

    private val SNAPSHOT = Preview(url = URL, title = "某个视频的标题", description = "视频简介", date = 1790000000000L)

    private val IMAGE = AttachmentPointer(
      cdnKey = "cdnKey",
      cdnNumber = 2,
      key = byteArrayOf(1, 2, 3).toByteString(),
      digest = byteArrayOf(4, 5, 6).toByteString(),
      contentType = "image/jpeg",
      size = 34,
      width = 1200,
      height = 630
    )
  }

  @Test
  fun `a preview carrying rich shows the same snapshot as one without`() {
    val snapshot = SNAPSHOT.newBuilder().image(IMAGE).build()

    val withRich = DataMessageProcessor.getLinkPreviews(listOf(received(snapshot, withRich = true)), BODY, false).single()
    val withoutRich = DataMessageProcessor.getLinkPreviews(listOf(received(snapshot, withRich = false)), BODY, false).single()

    assertEquals(withoutRich.url, withRich.url)
    assertEquals(withoutRich.title, withRich.title)
    assertEquals(withoutRich.description, withRich.description)
    assertEquals(withoutRich.date, withRich.date)
    val a = withRich.thumbnail.get()
    val b = withoutRich.thumbnail.get()
    assertEquals(listOf(b.remoteLocation, b.remoteKey, b.contentType, b.size, b.width, b.height), listOf(a.remoteLocation, a.remoteKey, a.contentType, a.size, a.width, a.height))
    assertEquals(withoutRich.serialize(), LinkPreview(withRich.url, withRich.title, withRich.description, withRich.date, withRich.attachmentId).serialize())

    assertArrayEquals(RICH_BYTES, withRich.rich)
    assertNull(withoutRich.rich)
    assertFalse("a preview without rich stores the same JSON as before", withoutRich.serialize().contains("rich"))
  }

  @Test
  fun `receive, persist, load, forward, persist and send keep the rich bytes`() {
    // Receive.
    val preview = DataMessageProcessor.getLinkPreviews(listOf(received(SNAPSHOT, withRich = true)), BODY, false).single()
    assertArrayEquals(RICH_BYTES, preview.rich)

    // Persist and load.
    val alice = Recipient.resolved(recipients.createRecipient("Alice"))
    val incomingId = SignalDatabase.messages.insertMessageInbox(
      IncomingMessage(
        type = MessageType.NORMAL,
        from = alice.id,
        sentTimeMillis = 1790000000001L,
        serverTimeMillis = 1790000000001L,
        receivedTimeMillis = 1790000000002L,
        body = BODY,
        linkPreviews = listOf(preview)
      ),
      SignalDatabase.threads.getOrCreateThreadIdFor(alice)
    ).get().messageId
    val loaded = (SignalDatabase.messages.getMessageRecord(incomingId) as MmsMessageRecord).linkPreviews.single()
    assertArrayEquals(RICH_BYTES, loaded.rich)

    // Forward: MultiselectForward hands the preview to MultiShareArgs, which crosses a Parcel as JSON, and
    // MultiShareSender rebuilds it for the new outgoing message.
    val parcel = Parcel.obtain()
    MultiShareArgs.Builder().withLinkPreview(loaded).build().writeToParcel(parcel, 0)
    parcel.setDataPosition(0)
    val fromParcel = MultiShareArgs.CREATOR.createFromParcel(parcel).linkPreview
    parcel.recycle()
    val forwarded = MultiShareSender.buildLinkPreviews(ApplicationProvider.getApplicationContext(), fromParcel)

    // Persist the outgoing message and read it back the way the send job does.
    val bob = Recipient.resolved(recipients.createRecipient("Bob", profileSharing = false))
    val outgoingId = recipients.insertOutgoingMessage(
      OutgoingMessage(threadRecipient = bob, sentTimeMillis = 1790000000003L, body = BODY, isSecure = true, linkPreviews = forwarded),
      SignalDatabase.threads.getOrCreateThreadIdFor(bob)
    )
    val toSend = SignalDatabase.messages.getOutgoingMessage(outgoingId)
    assertArrayEquals(RICH_BYTES, toSend.linkPreviews.single().rich)

    val expected = Preview(url = SNAPSHOT.url, title = SNAPSHOT.title, description = SNAPSHOT.description, date = SNAPSHOT.date).encode() + field1000(RICH_BYTES)

    // Send (IndividualSendJob / PushGroupSendJob): PushSendJob.getPreviewsFor → SignalServiceMessageSender's builder.
    val job = mockk<PushSendJob>()
    every { job.getPreviewsFor(any()) } answers { callOriginal() }
    val servicePreview = job.getPreviewsFor(toSend).single()
    val encoded = SignalServiceMessageSender.createPreviewBuilder(servicePreview).build().encode()
    assertEquals(expected.toByteString().hex(), encoded.toByteString().hex())

    // Send (IndividualSendJobV2): OutgoingMessage.toDataMessage. RecipientTestRule mocks RemoteConfig.
    every { RemoteConfig.maxIncrementalMacsPerEnvelope } returns 10
    val dataMessage = toSend.toDataMessage().getOrNull()!!
    assertEquals(expected.toByteString().hex(), dataMessage.preview.single().encode().toByteString().hex())
  }
}
