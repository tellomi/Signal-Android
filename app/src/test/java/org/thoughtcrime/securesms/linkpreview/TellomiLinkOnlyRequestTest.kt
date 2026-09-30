/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.Hex
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.testutil.UriAttachmentBuilder
import java.util.Optional

/**
 * card-visual §3.3 / §7.3, ADR-0063 §8.1 row 6 (S1): what a received message shows for its link while its conversation is
 * still a message request: the domain and nothing the sender wrote. Decided in the data layer, like the full card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkOnlyRequestTest {

  private val url = "https://www.163.com/news/article/K1234.html"
  private val group = "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

  private val classified = mutableListOf<LinkPreview>()
  private val classifiedBodies = mutableListOf<String>()
  private val classifiedAttachments = mutableListOf<List<String>>()

  private val plain = TellomiLinkCard(level = TellomiLinkCard.Level.PLAIN_LINK, domain = "163.com", showImage = false, reason = "no_title")

  private fun decide(record: MmsMessageRecord, hasMentions: Boolean = false, card: TellomiLinkCard? = plain, lookalike: String? = null): TellomiLinkCard? {
    return TellomiLinkOnly.decideRequest(
      record,
      hasMentions,
      { preview, body, _, attachments ->
        classified += preview
        classifiedBodies += body
        classifiedAttachments += attachments
        card
      },
      { lookalike }
    )
  }

  private fun withTheSendersContent(): LinkPreview {
    return LinkPreview(
      url,
      "Sender title",
      "Sender description",
      1_790_000_000_000L,
      Optional.of<Attachment>(UriAttachmentBuilder.build(id = 7, contentType = "image/jpeg")),
      byteArrayOf(0x0a, 0x05, 0x76, 0x69, 0x64, 0x65, 0x6f)
    )
  }

  private fun message(body: String = url, previews: List<LinkPreview> = emptyList(), slideDeck: SlideDeck = SlideDeck(), mailbox: Long = MessageTypes.BASE_INBOX_TYPE, storyType: StoryType = StoryType.NONE): MmsMessageRecord {
    return FakeMessageRecords.buildMediaMmsMessageRecord(body = body, linkPreviews = previews, slideDeck = slideDeck, mailbox = mailbox, storyType = storyType)
  }

  @Test
  fun `rust-links is asked about the URL alone, never the sender's title, description, image or rich`() {
    decide(message(previews = listOf(withTheSendersContent())))

    val asked = classified.single()
    assertEquals(url, asked.url)
    assertEquals("", asked.title)
    assertEquals("", asked.description)
    assertEquals(0L, asked.date)
    assertFalse("no image: has_image is false", asked.thumbnail.isPresent)
    assertNull(asked.rich)
    assertEquals("""{"url":"$url","has_image":false}""", TellomiLinkCard.previewInputJson(asked))
  }

  @Test
  fun `a message with a preview gets the domain card, whatever the message says besides the link`() {
    val card = decide(message("看看 $url，挺有意思", listOf(withTheSendersContent())))

    assertEquals(TellomiLinkCard(level = TellomiLinkCard.Level.PLAIN_LINK, domain = "163.com", showImage = false, tintable = false, reason = "no_title"), card)
    assertEquals("the body is what rust-links checks the URL against", listOf("看看 $url，挺有意思"), classifiedBodies)
  }

  @Test
  fun `the card keeps the domain and whether it imitates a well-known one, and nothing else`() {
    val everything = TellomiLinkCard(
      level = TellomiLinkCard.Level.STRUCTURED,
      provider = "bilibili",
      providerName = TellomiLinkCard.LocalizedName("哔哩哔哩", null, "Bilibili"),
      kind = "video",
      route = "video",
      title = "Sender title",
      description = "Sender description",
      attrs = listOf(TellomiLinkCard.Attr("author", "Someone")),
      domain = "bilibili.com",
      officialBadge = true,
      firstParty = TellomiLinkCard.FirstParty(type = "group", title = "周末爬山群", memberCount = 12),
      lookalike = null,
      showImage = true,
      icon = "taobao.png",
      tintable = true,
      payment = false,
      reason = "why"
    )

    val card = decide(message(previews = listOf(withTheSendersContent())), card = everything, lookalike = "apple.com")!!

    assertEquals(TellomiLinkCard.Level.PLAIN_LINK, card.level)
    assertEquals("bilibili.com", card.domain)
    assertEquals("apple.com", card.lookalike)
    assertNull(card.provider)
    assertNull(card.providerName)
    assertNull(card.kind)
    assertNull(card.route)
    assertNull(card.title)
    assertNull(card.description)
    assertTrue(card.attrs.isEmpty())
    assertFalse(card.officialBadge)
    assertNull(card.firstParty)
    assertFalse(card.showImage)
    assertNull(card.icon)
    assertFalse("never tinted", card.tintable)
    assertFalse(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.ICON))
    assertFalse(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.LARGE_IMAGE))
  }

  @Test
  fun `a domain that imitates a well-known one is flagged, from rust-links or from the open check`() {
    assertEquals("bilibili.com", decide(message(previews = listOf(withTheSendersContent())), card = plain.copy(lookalike = "bilibili.com"))?.lookalike)
    assertEquals("bilibili.com", decide(message(previews = listOf(withTheSendersContent())), lookalike = "bilibili.com")?.lookalike)
    assertNull(decide(message(previews = listOf(withTheSendersContent())))?.lookalike)
  }

  @Test
  fun `just a link sent without a preview gets the domain card too, which the full card would have drawn locally`() {
    val card = decide(message())

    assertEquals("163.com", card?.domain)
    assertEquals(url, classified.single().url)
    assertEquals(0L, classified.single().date)
  }

  @Test
  fun `a message with text around a link and no preview, or with mentions or formatting, has no card`() {
    assertNull(decide(message("看看 $url")))
    assertNull(decide(message(), hasMentions = true))
    assertTrue("rust-links is not asked when there is nothing to draw", classified.isEmpty())
  }

  @Test
  fun `what this device sent is left alone`() {
    assertNull(decide(message(previews = listOf(withTheSendersContent()), mailbox = MessageTypes.BASE_SENT_TYPE)))
    assertNull(decide(message(mailbox = MessageTypes.BASE_SENT_TYPE)))
    assertTrue(classified.isEmpty())
  }

  @Test
  fun `without a registry, or without a domain, there is no card and never the full card`() {
    assertNull(decide(message(previews = listOf(withTheSendersContent())), card = null))
    assertNull(decide(message(previews = listOf(withTheSendersContent())), card = plain.copy(domain = null, reason = "attachments")))
    assertNull(decide(message(), card = null))
  }

  @Test
  fun `the attachments are handed to rust-links, which drops the preview when there are any but a long text`() {
    val image = SlideDeck(listOf(FakeMessageRecords.buildDatabaseAttachment(contentType = "image/jpeg")))

    decide(message(previews = listOf(withTheSendersContent()), slideDeck = image))

    assertEquals(listOf(listOf("image/jpeg")), classifiedAttachments)
  }

  @Test
  fun `the full card is decided as before`() {
    val generic = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, title = "Sender title", domain = "163.com")
    val record = message(previews = listOf(withTheSendersContent()))

    val full = TellomiLinkOnly.decide(record, false, { _, _, _, _ -> generic }, { null })

    assertEquals(TellomiLinkOnly.Decision(generic, null, true), full)
  }

  // ---- with the real rust-links and the registry that ships with the app ----

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  private val golden = Json.parseToJsonElement(String(resource("classify-golden.json"))).jsonObject

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  @Before
  fun loadRegistry() {
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(resource(golden.str("registry")!!)))
  }

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
  }

  private fun real(record: MmsMessageRecord) = TellomiLinkOnly.decideRequest(record, false)

  private fun preview(url: String, title: String = "Sender title", image: Boolean = true, rich: ByteArray? = null): LinkPreview {
    return LinkPreview(
      url,
      title,
      "Sender description",
      1_790_000_000_000L,
      if (image) Optional.of<Attachment>(UriAttachmentBuilder.build(id = 9, contentType = "image/jpeg")) else Optional.empty(),
      rich
    )
  }

  private fun assertDomainOnly(name: String, card: TellomiLinkCard?) {
    assertNotNull(name, card)
    card!!
    assertEquals(name, TellomiLinkCard.Level.PLAIN_LINK, card.level)
    assertFalse(name, card.domain.isNullOrEmpty())
    assertNull(name, card.title)
    assertNull(name, card.description)
    assertTrue(name, card.attrs.isEmpty())
    assertNull(name, card.provider)
    assertNull(name, card.providerName)
    assertNull(name, card.kind)
    assertNull(name, card.route)
    assertFalse(name, card.officialBadge)
    assertNull(name, card.firstParty)
    assertFalse(name, card.showImage)
    assertNull(name, card.icon)
    assertFalse(name, card.tintable)
    assertFalse(name, card.payment)
  }

  @Test
  fun `a tell-cc group invite shows only the domain, not the group name, avatar or the join button`() {
    val card = real(message(group, listOf(preview(group, title = "周末爬山群"))))

    assertDomainOnly("group invite", card)
    assertEquals("tell.cc", card?.domain)
  }

  @Test
  fun `the other first-party links show only the domain too`() {
    val links = mapOf(
      "user" to "https://tell.cc/scam01",
      "encrypted user" to "https://tell.cc/u#eu/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "call" to "https://tell.cc/call/#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz",
      "official" to "https://tellomi.app/security"
    )
    for ((name, link) in links) {
      val card = real(message(link, listOf(preview(link, title = "@kefu"))))

      assertDomainOnly(name, card)
      assertEquals(name, if (name == "official") "tellomi.app" else "tell.cc", card?.domain)
    }
  }

  @Test
  fun `a brand shell and a payment shell show only the domain, without their icon or name`() {
    for (link in listOf("https://item.taobao.com/item.htm?id=100032608854", "https://render.alipay.com/p/f/fd-j5rqp49m/index.html")) {
      assertDomainOnly(link, real(message(link, listOf(preview(link, title = "淘宝")))))
    }
  }

  @Test
  fun `a structured card shows only the domain, without the title, sub line or image`() {
    val bilibili = "https://www.bilibili.com/video/BV1YDhJ6ZEL6"

    val card = real(message(bilibili, listOf(preview(bilibili, title = "《柯洁围棋入门课》"))))

    assertDomainOnly("bilibili", card)
    assertEquals("bilibili.com", card?.domain)
  }

  @Test
  fun `a domain that imitates a well-known one stays flagged`() {
    val lookalike = "https://www.bi1ibili.com/video/BV1YDhJ6ZEL6"

    val card = real(message(lookalike, listOf(preview(lookalike, title = "哔哩哔哩"))))

    assertDomainOnly("lookalike", card)
    assertEquals("bi1ibili.com", card?.domain)
    assertEquals("bilibili.com", card?.lookalike)
  }

  @Test
  fun `an ordinary page shows only its registrable domain`() {
    val card = real(message(url, listOf(preview(url))))

    assertDomainOnly("163", card)
    assertEquals("163.com", card?.domain)
  }

  @Test
  fun `just a link without a preview, and a preview not in the body, behave as the full card decides`() {
    assertEquals("163.com", real(message())?.domain)
    assertNull("the preview's URL is not in the body", real(message("hello", listOf(preview(url)))))
    assertNull("text around a link, no preview", real(message("看看 $url")))
  }

  @Test
  fun `an attachment takes the card away, except a long text`() {
    val image = SlideDeck(listOf(FakeMessageRecords.buildDatabaseAttachment(contentType = "image/jpeg")))
    val longText = SlideDeck(listOf(FakeMessageRecords.buildDatabaseAttachment(contentType = "text/x-signal-plain")))

    assertNull(real(message(previews = listOf(preview(url)), slideDeck = image)))
    assertDomainOnly("long text", real(message(previews = listOf(preview(url)), slideDeck = longText)))
  }

  @Test
  fun `for every golden case, nothing the sender wrote gets into the request card`() {
    val cases = golden["classify"]!!.jsonArray.map { it.jsonObject }
    var drawn = 0

    for (case in cases) {
      val name = case.str("name")!!
      val preview = Json.parseToJsonElement(case.str("preview")!!).jsonObject
      val message = Json.parseToJsonElement(case.str("message")?.takeIf { it.isNotEmpty() } ?: "{}").jsonObject

      val thumbnail: Optional<Attachment> = if (preview["has_image"]?.jsonPrimitive?.booleanOrNull == true) {
        Optional.of(UriAttachmentBuilder.build(id = 1, contentType = "image/jpeg"))
      } else {
        Optional.empty()
      }
      val linkPreview = LinkPreview(preview.str("url")!!, preview.str("title") ?: "", preview.str("description") ?: "", 0, thumbnail, preview.str("rich")?.let { Hex.fromStringCondensed(it) })
      val attachments = message["attachment_content_types"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
      val story = message["is_story"]?.jsonPrimitive?.boolean ?: false
      val record = message(
        case.str("body")!!,
        listOf(linkPreview),
        SlideDeck(attachments.mapIndexed { i, type -> FakeMessageRecords.buildDatabaseAttachment(contentType = type, mmsId = 1, attachmentId = org.signal.core.models.database.AttachmentId(i + 1L)) }),
        storyType = if (story) StoryType.TEXT_STORY_WITH_REPLIES else StoryType.NONE
      )

      val card = real(record)
      val bare = TellomiLinkRegistry.classify(LinkPreview(linkPreview.url, "", "", 0, Optional.empty()), case.str("body")!!, story, attachments)
      if (bare?.domain == null) {
        assertNull("no domain, no card: $name", card)
        continue
      }

      drawn++
      assertDomainOnly(name, card)
      assertEquals("the domain is rust-links' for the bare URL: $name", bare.domain, card?.domain)
      assertEquals("the imitation flag is rust-links' for the bare URL: $name", bare.lookalike, card?.lookalike)
    }

    assertTrue("most golden cases draw a domain card ($drawn of ${cases.size})", drawn >= 40)
  }
}
