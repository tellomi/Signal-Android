/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.messages

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.Hex
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.TellomiLinkRegistry
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.Preview
import java.util.UUID

/**
 * ADR-0063 §7.4 (S2, PR "receive_check"): an incoming message is stored the way rust/links says, and the image of its preview is
 * only fetched when the card that will be drawn shows it. This drives the real receive path (`DataMessageProcessor`'s media-message
 * handler → the database → the download jobs it queues) with a real database and the real rust/links.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiReceiveDownloadGateTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private val url = "https://www.163.com/news/article/K1234.html"
  private val taobao = "https://item.taobao.com/item.htm?id=100032608854"
  private val user = "https://tell.cc/ceshi.57"
  private val group = "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

  private lateinit var alice: Recipient
  private val queued = mutableListOf<Job>()
  private var clock = 1_790_000_000_000L

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  private val golden = Json.parseToJsonElement(String(resource("classify-golden.json"))).jsonObject

  private fun richOf(name: String): ByteArray {
    val case = golden["classify"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }
    return Hex.fromStringCondensed(Json.parseToJsonElement(case["preview"]!!.jsonPrimitive.content).jsonObject["rich"]!!.jsonPrimitive.content)
  }

  @Before
  fun setUp() {
    alice = Recipient.resolved(recipients.createRecipient("Alice"))
    val jobManager = mockk<JobManager>(relaxed = true)
    val slot = slot<List<Job>>()
    every { jobManager.addAll(capture(slot)) } answers { queued += slot.captured }
    every { AppDependencies.jobManager } returns jobManager
    every { SignalStore.remoteConfig } returns mockk(relaxed = true)
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(resource(golden["registry"]!!.jsonPrimitive.content)))
  }

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
  }

  private fun pointer(type: String, tag: String, width: Int = 1200, height: Int = 630) = AttachmentPointer(
    cdnKey = tag,
    cdnNumber = 2,
    key = byteArrayOf(1, 2, 3).toByteString(),
    digest = byteArrayOf(4, 5, 6).toByteString(),
    contentType = type,
    size = 34,
    width = width,
    height = height
  )

  private fun preview(link: String, title: String? = "Sender title", withImage: Boolean = true, rich: ByteArray? = null): Preview {
    val snapshot = Preview(url = link, title = title, description = "Sender description", date = 1_790_000_000_000L, image = if (withImage) pointer("image/jpeg", "preview-image") else null)
    return if (rich == null) snapshot else Preview.ADAPTER.decode(snapshot.encode() + field1000(rich))
  }

  /** key = 1000 << 3 | 2, then the length as a varint, then the value. */
  private fun field1000(rich: ByteArray): ByteArray {
    val length = mutableListOf<Byte>()
    var rest = rich.size
    while (rest >= 0x80) {
      length += ((rest and 0x7F) or 0x80).toByte()
      rest = rest ushr 7
    }
    length += rest.toByte()
    return byteArrayOf(0xC2.toByte(), 0x3E) + length.toByteArray() + rich
  }

  private class Received(val result: MessageTable.InsertResult?, val stored: MmsMessageRecord?, val downloads: List<AttachmentDownloadJob>) {
    val previewImages get() = result?.insertedAttachments?.keys.orEmpty().count { it.contentType == "image/jpeg" }
    val ownAttachments get() = result?.insertedAttachments?.keys.orEmpty().count { it.contentType != "image/jpeg" }
  }

  /** The incoming media message the way `DataMessageProcessor.process` hands it to the handler. */
  private fun receive(body: String, vararg previews: Preview, attachments: List<AttachmentPointer> = emptyList()): Received {
    queued.clear()
    val message = DataMessage(body = body, preview = previews.toList(), attachments = attachments)
    val envelope = Envelope(clientTimestamp = ++clock, serverTimestamp = clock + 1)
    val metadata = EnvelopeMetadata(alice.requireAci(), null, 1, false, null, ACI.from(UUID.randomUUID()), 0)
    val handler = DataMessageProcessor::class.java.declaredMethods.first { it.name == "handleMediaMessage" }.apply { isAccessible = true }
    val result = try {
      handler.invoke(DataMessageProcessor, ApplicationProvider.getApplicationContext<Context>(), envelope, metadata, message, alice, alice, null, clock + 2, null, OneTimeBatchCache()) as MessageTable.InsertResult?
    } catch (e: java.lang.reflect.InvocationTargetException) {
      throw e.cause!!
    }
    val stored = result?.let { SignalDatabase.messages.getMessageRecord(it.messageId) as MmsMessageRecord }
    return Received(result, stored, queued.filterIsInstance<AttachmentDownloadJob>())
  }

  private fun LinkPreview.hasImage() = attachmentId != null

  @Test
  fun `a page's preview image is downloaded`() {
    val received = receive("看看 $url", preview(url))

    assertEquals(1, received.stored!!.linkPreviews.size)
    assertTrue(received.stored.linkPreviews.single().hasImage())
    assertEquals(1, received.previewImages)
    assertEquals(1, received.downloads.size)
  }

  @Test
  fun `a brand shell's preview image is not turned into an attachment, and nothing is queued for download`() {
    val rich = richOf("§6.1 brand-tier platform claiming a structured card with attrs → brand, no attrs")

    val received = receive(taobao, preview(taobao, title = "淘宝", rich = rich))

    val preview = received.stored!!.linkPreviews.single()
    assertFalse("no attachment behind the preview", preview.hasImage())
    assertFalse(preview.thumbnail.isPresent)
    assertEquals(0, received.previewImages)
    assertEquals("the message itself is stored", taobao, received.stored.body)
    assertTrue("nothing to download", received.downloads.isEmpty())
    assertArrayEquals("the rich still arrives as it was sent", rich, preview.rich)
  }

  @Test
  fun `a plain link and a tell-cc user card have no image to download either`() {
    val plain = receive(url, preview(url, title = null))
    val userCard = receive(user, preview(user, title = null))

    assertEquals(1, plain.stored!!.linkPreviews.size)
    assertEquals(1, userCard.stored!!.linkPreviews.size)
    assertTrue(plain.downloads.isEmpty())
    assertTrue(userCard.downloads.isEmpty())
    assertEquals(0, plain.previewImages + userCard.previewImages)
  }

  @Test
  fun `a group invite's picture and a structured card's cover are downloaded, as the card shows them`() {
    val video = "https://www.bilibili.com/video/BV1YDhJ6ZEL6"

    val invite = receive(group, preview(group, title = "周末爬山群"))
    val structured = receive(video, preview(video, rich = richOf("structured video: sender level 2, required fields present")))

    assertEquals(1, invite.previewImages)
    assertEquals(1, structured.previewImages)
    assertEquals(1, invite.downloads.size)
    assertEquals(1, structured.downloads.size)
  }

  @Test
  fun `an image sent with the message takes the card, and so its image, away, and the message's own image is still fetched`() {
    val received = receive(url, preview(url), attachments = listOf(pointer("image/png", "own-image", 640, 480)))

    assertFalse(received.stored!!.linkPreviews.single().hasImage())
    assertEquals(0, received.previewImages)
    assertEquals(1, received.ownAttachments)
    assertEquals("only the message's own image is fetched", 1, received.downloads.size)
  }

  @Test
  fun `a long text does not take the card away`() {
    val received = receive(url, preview(url), attachments = listOf(pointer("text/x-signal-plain", "long-text", 0, 0)))

    assertTrue(received.stored!!.linkPreviews.single().hasImage())
    assertEquals(2, received.downloads.size)
  }

  @Test
  fun `a preview for a link that is not in the message is dropped and the message and its attachments are kept`() {
    val received = receive("hello", preview(url), attachments = listOf(pointer("image/png", "own-image", 640, 480)))

    assertNotNull(received.result)
    assertTrue(received.stored!!.linkPreviews.isEmpty())
    assertEquals("hello", received.stored.body)
    assertEquals(0, received.previewImages)
    assertEquals(1, received.downloads.size)
  }

  @Test
  fun `an oversized rich is dropped, the snapshot and its image stay`() {
    val received = receive(url, preview(url, rich = richOf("§6.1 oversized RichContent (kind of 33 characters) is dropped whole → generic")))

    val preview = received.stored!!.linkPreviews.single()
    assertNull(preview.rich)
    assertEquals("Sender title", preview.title)
    assertTrue(preview.hasImage())
    assertEquals(1, received.downloads.size)
  }

  @Test
  fun `without a registry the message is stored and its preview handled as it always was`() {
    TellomiLinkRegistry.setForTesting(null)
    val rich = richOf("§6.1 brand-tier platform claiming a structured card with attrs → brand, no attrs")

    val brand = receive(taobao, preview(taobao, title = "淘宝", rich = rich))
    val untitled = receive(user, preview(user, title = null))

    assertTrue("the image is fetched: nothing is decided", brand.stored!!.linkPreviews.single().hasImage())
    assertEquals(1, brand.downloads.size)
    assertArrayEquals(rich, brand.stored.linkPreviews.single().rich)
    assertNotNull("the message is stored", untitled.stored)
    assertTrue("a preview without a title is dropped, as before", untitled.stored!!.linkPreviews.isEmpty())
  }
}
