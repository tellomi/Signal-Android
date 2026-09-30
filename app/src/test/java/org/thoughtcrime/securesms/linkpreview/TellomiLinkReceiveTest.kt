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
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.Hex
import org.signal.core.util.logging.Log
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.calls.links.CallLinks
import org.thoughtcrime.securesms.messages.DataMessageProcessor
import org.thoughtcrime.securesms.messages.SignalServiceProtoUtil.toPointer
import org.thoughtcrime.securesms.testutil.UriAttachmentBuilder
import org.thoughtcrime.securesms.util.LinkUtil
import org.whispersystems.signalservice.api.messages.TellomiRichContent
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.Preview
import java.util.Optional

/**
 * ADR-0063 §5.1 rule 4 / §6.1 / §7.4 (S2): what is written of a received preview is what rust/links says — `receive_check` for
 * the preview and its `rich`, `classify`'s `show_image` for its image — and when rust/links cannot say, nothing changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkReceiveTest {

  private val url = "https://www.163.com/news/article/K1234.html"
  private val video = "https://www.bilibili.com/video/BV1YDhJ6ZEL6"
  private val taobao = "https://item.taobao.com/item.htm?id=100032608854"
  private val group = "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  private val user = "https://tell.cc/ceshi.57"
  private val official = "https://tellomi.app/security"
  private val call = "https://tell.cc/call/#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz"

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
    Log.initialize()
  }

  private val image = AttachmentPointer(
    cdnKey = "cdnKey",
    cdnNumber = 2,
    key = byteArrayOf(1, 2, 3).toByteString(),
    digest = byteArrayOf(4, 5, 6).toByteString(),
    contentType = "image/jpeg",
    size = 34,
    width = 1200,
    height = 630
  )

  private fun preview(url: String, title: String? = "Sender title", withImage: Boolean = false, rich: ByteArray? = null): Preview {
    val snapshot = Preview(url = url, title = title, description = "Sender description", date = 1_790_000_000_000L, image = if (withImage) image else null)
    return if (rich == null) snapshot else Preview.ADAPTER.decode(snapshot.encode() + field1000(rich))
  }

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

  private fun receive(preview: Preview, body: String, isStory: Boolean = false, attachments: List<String> = emptyList()): List<LinkPreview> {
    return DataMessageProcessor.getLinkPreviews(listOf(preview), body, isStory, attachments)
  }

  // ---- what rust/links says, without the native library ----

  private val kept = TellomiReceiveCheck(keepPreview = true, keepRich = true)

  private fun candidate(withImage: Boolean = true): LinkPreview {
    return LinkPreview(url, "Sender title", "", 0, if (withImage) Optional.of<Attachment>(UriAttachmentBuilder.build(id = 1, contentType = "image/jpeg")) else Optional.empty())
  }

  private val generic = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, title = "t", domain = "163.com", showImage = true)

  @Test
  fun `a preview rust-links does not keep is dropped, and its image is not even asked about`() {
    var classified = 0

    val verdict = TellomiLinkReceive.decide(candidate(), "hello", false, emptyList(), { _, _, _, _ -> TellomiReceiveCheck(keepPreview = false, keepRich = false) }, { _, _, _, _ ->
      classified++
      generic
    })

    assertEquals(TellomiLinkReceive.Verdict(keepPreview = false, keepRich = false, keepImage = false), verdict)
    assertEquals(0, classified)
  }

  @Test
  fun `the rich is kept or dropped as receive_check says, the preview stays either way`() {
    val keepNoRich = TellomiLinkReceive.decide(candidate(false), url, false, emptyList(), { _, _, _, _ -> TellomiReceiveCheck(true, false) }, { _, _, _, _ -> generic })
    val keepRich = TellomiLinkReceive.decide(candidate(false), url, false, emptyList(), { _, _, _, _ -> TellomiReceiveCheck(true, true) }, { _, _, _, _ -> generic })

    assertEquals(TellomiLinkReceive.Verdict(keepPreview = true, keepRich = false, keepImage = true), keepNoRich)
    assertEquals(TellomiLinkReceive.Verdict(keepPreview = true, keepRich = true, keepImage = true), keepRich)
  }

  @Test
  fun `the image is kept when the card shows it and dropped when it does not, and classify sees the message as it is drawn`() {
    val asked = mutableListOf<List<Any>>()
    val decide = { showImage: Boolean ->
      TellomiLinkReceive.decide(candidate(), "body text", true, listOf("text/x-signal-plain"), { _, _, _, _ -> kept }, { preview, body, isStory, attachments ->
        asked += listOf(preview.thumbnail.isPresent, body, isStory, attachments)
        generic.copy(showImage = showImage)
      })
    }

    assertTrue(decide(true)!!.keepImage)
    assertFalse(decide(false)!!.keepImage)
    assertEquals(listOf(true, "body text", true, listOf("text/x-signal-plain")), asked.first())
  }

  @Test
  fun `a preview without an image is not classified`() {
    var classified = 0

    val verdict = TellomiLinkReceive.decide(candidate(withImage = false), url, false, emptyList(), { _, _, _, _ -> kept }, { _, _, _, _ ->
      classified++
      generic.copy(showImage = false)
    })

    assertEquals(0, classified)
    assertTrue(verdict!!.keepImage)
  }

  @Test
  fun `without a decision from receive_check there is no verdict, whatever the reason`() {
    val failures: List<() -> TellomiReceiveCheck?> = listOf(
      { null },
      { throw IllegalStateException("no registry") },
      { throw IllegalArgumentException("rust said no") },
      { throw UnsatisfiedLinkError("no native library") },
      { throw NoClassDefFoundError("org.signal.libsignal.links.LinkRegistry") }
    )
    for (failure in failures) {
      assertNull(TellomiLinkReceive.decide(candidate(), url, false, emptyList(), { _, _, _, _ -> failure() }, { _, _, _, _ -> generic }))
    }
  }

  @Test
  fun `when classify cannot say, the image stays as it was`() {
    val failures: List<() -> TellomiLinkCard?> = listOf({ null }, { throw IllegalStateException("x") }, { throw UnsatisfiedLinkError("no native library") })
    for (failure in failures) {
      val verdict = TellomiLinkReceive.decide(candidate(), url, false, emptyList(), { _, _, _, _ -> kept }, { _, _, _, _ -> failure() })

      assertEquals(TellomiLinkReceive.Verdict(keepPreview = true, keepRich = true, keepImage = true), verdict)
    }
  }

  @Test
  fun `what is kept of a preview is the preview as it arrived, less the image and the rich that are not kept`() {
    // The text is stored escaped as it arrived and read unescaped once; rebuilding it from what is read would unescape it twice.
    val received = LinkPreview("https://a.example/x", "1 &amp;lt; 2", "3 &amp;gt; 4", 1_790_000_000_000L, Optional.of<Attachment>(UriAttachmentBuilder.build(id = 1, contentType = "image/jpeg")), byteArrayOf(1, 2, 3))

    val all = TellomiLinkReceive.outcome(received, TellomiLinkReceive.Verdict(keepPreview = true, keepRich = true, keepImage = true))
    val noImage = TellomiLinkReceive.outcome(received, TellomiLinkReceive.Verdict(keepPreview = true, keepRich = true, keepImage = false))
    val noRich = TellomiLinkReceive.outcome(received, TellomiLinkReceive.Verdict(keepPreview = true, keepRich = false, keepImage = true))
    val neither = TellomiLinkReceive.outcome(received, TellomiLinkReceive.Verdict(keepPreview = true, keepRich = false, keepImage = false)) as TellomiLinkReceive.Outcome.Kept

    assertSame(received, (all as TellomiLinkReceive.Outcome.Kept).preview)
    assertFalse((noImage as TellomiLinkReceive.Outcome.Kept).preview.thumbnail.isPresent)
    assertArrayEquals(byteArrayOf(1, 2, 3), noImage.preview.rich)
    assertTrue((noRich as TellomiLinkReceive.Outcome.Kept).preview.thumbnail.isPresent)
    assertNull(noRich.preview.rich)
    assertFalse(neither.preview.thumbnail.isPresent)
    assertNull(neither.preview.rich)
    for (kept in listOf(noImage.preview, noRich.preview, neither.preview)) {
      assertEquals("the text is not decoded twice", "1 &lt; 2", kept.title)
      assertEquals("3 &gt; 4", kept.description)
      assertEquals(received.url, kept.url)
      assertEquals(received.date, kept.date)
    }
  }

  @Test
  fun `the outcome is undecided without a verdict and dropped when the preview is not kept`() {
    val received = candidate()

    assertEquals(TellomiLinkReceive.Outcome.Undecided, TellomiLinkReceive.outcome(received, null))
    assertEquals(TellomiLinkReceive.Outcome.Dropped, TellomiLinkReceive.outcome(received, TellomiLinkReceive.Verdict(keepPreview = false, keepRich = false, keepImage = false)))
  }

  @Test
  fun `a failure is logged by its class only, never the link`() {
    val canary = "zqxCANARY7f3e"
    val lines = java.util.Collections.synchronizedList(ArrayList<String>())
    Log.initialize(object : Log.Logger() {
      private fun add(level: String, tag: String, message: String?, t: Throwable?) {
        lines += "$level/$tag: $message ${t?.stackTraceToString() ?: ""}"
      }

      override fun v(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("V", tag, message, t)
      override fun d(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("D", tag, message, t)
      override fun i(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("I", tag, message, t)
      override fun w(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("W", tag, message, t)
      override fun e(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("E", tag, message, t)
      override fun flush() = Unit
    })
    val secret = LinkPreview("https://a.example/$canary#$canary", "$canary title", "$canary description", 0, Optional.of<Attachment>(UriAttachmentBuilder.build(id = 1, contentType = "image/jpeg")))

    TellomiLinkReceive.decide(secret, "$canary", false, emptyList(), { _, _, _, _ -> throw IllegalStateException("$canary is in this message on purpose") }, { _, _, _, _ -> generic })
    TellomiLinkReceive.decide(secret, "$canary", false, emptyList(), { _, _, _, _ -> kept }, { _, _, _, _ -> throw UnsatisfiedLinkError("$canary is in this message on purpose") })
    TellomiLinkRegistry.setForTesting(null)
    DataMessageProcessor.getLinkPreviews(listOf(Preview(url = "https://a.example/$canary#$canary", title = canary)), "$canary", false)
    DataMessageProcessor.getLinkPreviews(listOf(Preview(url = "https://a.example/$canary#$canary", title = null)), "$canary", false)

    assertTrue("expected the receive path to log its failures", lines.isNotEmpty())
    val hits = lines.filter { it.contains(canary, ignoreCase = true) && !it.contains("is in this message on purpose") }
    assertEquals("log lines containing the link:\n" + hits.joinToString("\n"), 0, hits.size)
  }

  // ---- with the real rust-links and the registry that ships with the app ----

  @Test
  fun `a preview without a title is kept, so a tell-cc user and the official site still draw a card`() {
    for ((name, link) in mapOf("user" to user, "official" to official, "call" to call)) {
      val stored = receive(preview(link, title = null), link).singleOrNull()

      assertNotNull("kept: $name", stored)
      val card = TellomiLinkRegistry.classify(stored!!, link, false, emptyList())
      assertEquals("still a first-party card: $name", TellomiLinkCard.Level.FIRST_PARTY, card?.level)
    }
  }

  @Test
  fun `a title-less preview of an ordinary page is kept too, and is drawn as a plain link`() {
    val stored = receive(preview(url, title = null), url).single()

    assertEquals(TellomiLinkCard.Level.PLAIN_LINK, TellomiLinkRegistry.classify(stored, url, false, emptyList())?.level)
  }

  @Test
  fun `a preview whose link is not in the body, or that is not https, is dropped, and the rest of the message is not affected`() {
    assertTrue(receive(preview(url), "hello").isEmpty())
    assertTrue(receive(preview("http://www.163.com/news"), "http://www.163.com/news").isEmpty())
    assertTrue(receive(preview("https://localhost/x"), "https://localhost/x").isEmpty())
    assertEquals(1, receive(preview(url), "看看 $url，挺有意思").size)
    assertEquals("a story need not have its link in the text", 1, receive(preview(url), "", isStory = true).size)
  }

  @Test
  fun `an oversized or malformed rich is dropped whole and the snapshot stays, a good one is stored as it arrived`() {
    val good = hexOf(golden, "structured video: sender level 2, required fields present")
    val oversized = hexOf(golden, "§6.1 oversized RichContent (kind of 33 characters) is dropped whole → generic")
    val tooManyAttrs = hexOf(golden, "§6.1 17 attrs: the whole rich is dropped → generic")

    val kept = receive(preview(video, withImage = false, rich = good), video).single()
    val droppedBig = receive(preview(video, withImage = false, rich = oversized), video).single()
    val droppedMany = receive(preview(video, withImage = false, rich = tooManyAttrs), video).single()

    assertArrayEquals("stored exactly as received", good, kept.rich)
    assertNull(droppedBig.rich)
    assertNull(droppedMany.rich)
    assertEquals("Sender title", droppedBig.title)
    assertEquals("Sender description", droppedBig.description)
    assertEquals(video, droppedBig.url)
  }

  private fun hexOf(golden: JsonObject, name: String): ByteArray {
    val case = golden["classify"]!!.jsonArray.map { it.jsonObject }.first { it.str("name") == name }
    return Hex.fromStringCondensed(Json.parseToJsonElement(case.str("preview")!!).jsonObject["rich"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the image is downloaded only when the card shows it`() {
    fun kept(link: String, title: String? = "Sender title", rich: ByteArray? = null, attachments: List<String> = emptyList(), body: String = link): Boolean {
      val stored = receive(preview(link, title = title, withImage = true, rich = rich), body, attachments = attachments).single()
      return stored.thumbnail.isPresent
    }

    assertTrue("a page's image is the card's", kept(url))
    assertTrue("a group invite's picture", kept(group, title = "周末爬山群"))
    assertTrue("a structured card's cover", kept(video, rich = hexOf(golden, "structured video: sender level 2, required fields present")))
    assertTrue("an old sender's preview of a brand-tier platform has no rich: it is a page like any other", kept(taobao))
    assertFalse("a brand shell shows its own icon, not the sender's image", kept(taobao, rich = hexOf(golden, "§6.1 brand-tier platform claiming a structured card with attrs → brand, no attrs")))
    assertFalse("a link with no title is a plain link", kept(url, title = null))
    assertFalse("a user card has no picture", kept(user))
    assertFalse("the official card has no picture", kept(official, title = "Tellomi"))
    assertTrue("a long text does not take the card away", kept(url, attachments = listOf("text/x-signal-plain")))
    assertFalse("an image sent with the message takes the card away", kept(url, attachments = listOf("image/jpeg")))
    assertFalse("two attachments take it away", kept(url, attachments = listOf("text/x-signal-plain", "text/x-signal-plain")))
  }

  @Test
  fun `for every golden case, what is stored is what receive_check and classify say`() {
    val cases = golden["classify"]!!.jsonArray.map { it.jsonObject }
    var checked = 0
    var skipped = 0

    for (case in cases) {
      val name = case.str("name")!!
      val previewJson = Json.parseToJsonElement(case.str("preview")!!).jsonObject
      val messageJson = Json.parseToJsonElement(case.str("message")?.takeIf { it.isNotEmpty() } ?: "{}").jsonObject
      val expectedCheck = Json.parseToJsonElement(case.str("receive_check")!!).jsonObject
      val expectedCard = TellomiLinkCard.parse(case.str("card")!!)!!
      val hasImage = previewJson["has_image"]?.jsonPrimitive?.booleanOrNull == true
      val richBytes = previewJson.str("rich")?.let { Hex.fromStringCondensed(it) }

      val proto = try {
        preview(previewJson.str("url")!!, title = previewJson.str("title"), withImage = hasImage, rich = richBytes).newBuilder().description(previewJson.str("description")).build()
      } catch (e: Exception) {
        skipped++ // a `rich` that is not protobuf at all cannot even arrive: the envelope would not parse
        continue
      }
      val attachments = messageJson["attachment_content_types"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

      val stored = receive(proto, case.str("body")!!, messageJson["is_story"]?.jsonPrimitive?.boolean ?: false, attachments)

      if (expectedCheck["keep_preview"]!!.jsonPrimitive.boolean.not()) {
        assertTrue("dropped: $name", stored.isEmpty())
        continue
      }
      checked++
      val one = stored.single()
      assertEquals("rich kept as receive_check says: $name", expectedCheck["keep_rich"]!!.jsonPrimitive.boolean, one.rich != null)
      if (one.rich != null) {
        assertArrayEquals("rich stored as it arrived: $name", proto.rich!!.encode(), one.rich)
      }
      assertEquals("image kept as classify's show_image says: $name", hasImage && expectedCard.showImage, one.thumbnail.isPresent)
      assertEquals("the snapshot is untouched: $name", proto.url, one.url)
    }

    assertTrue("most golden cases are kept ($checked kept, $skipped not representable)", checked >= 40 && skipped <= 2)
  }

  // ---- when rust-links cannot say: exactly what happened before ----

  @Test
  fun `without a registry the earlier rules apply, title and all, and the image and rich are kept`() {
    TellomiLinkRegistry.setForTesting(null)
    val rich = hexOf(golden, "structured video: sender level 2, required fields present")

    val titled = receive(preview(video, withImage = true, rich = rich), video).single()
    assertTrue(titled.thumbnail.isPresent)
    assertArrayEquals(rich, titled.rich)

    assertTrue("no title: dropped, as before", receive(preview(user, title = null), user).isEmpty())
    assertEquals("a call link needs no title", 1, receive(preview(call, title = null), call).size)
    assertEquals("a story needs neither the title nor the link in the text", 1, receive(preview(url, title = null), "", isStory = true).size)
    assertTrue("not in the body", receive(preview(url), "hello").isEmpty())
    assertTrue("not https", receive(preview("http://www.163.com/news"), "http://www.163.com/news").isEmpty())
    assertTrue("an image is kept for a brand shell too: nothing is decided", receive(preview(taobao, withImage = true), taobao).single().thumbnail.isPresent)
  }

  /** `DataMessageProcessor.getLinkPreviews` as it was before rust/links took over: the oracle for "nothing changes without a decision". */
  private fun earlierRules(previews: List<Preview>, body: String, isStoryEmbed: Boolean): List<LinkPreview> {
    val urlsInMessage = LinkPreviewUtil.findValidPreviewUrls(body)
    return previews.mapNotNull { preview ->
      val thumbnail: Attachment? = preview.image?.toPointer()
      val url = Optional.ofNullable(preview.url)
      val title = Optional.ofNullable(preview.title)
      val hasTitle = !title.orElse("").isEmpty()
      val presentInBody = url.isPresent && urlsInMessage.containsUrl(url.get())
      val validDomain = url.isPresent && LinkUtil.isValidPreviewUrl(url.get())
      val isForCallLink = url.isPresent && CallLinks.isCallLink(url.get())
      if ((hasTitle || isForCallLink || isStoryEmbed) && (presentInBody || isStoryEmbed) && validDomain) {
        LinkPreview(url.get(), title.orElse(""), Optional.ofNullable(preview.description).orElse(""), preview.date ?: 0, Optional.ofNullable(thumbnail), TellomiRichContent.receivedBytes(preview))
      } else {
        null
      }
    }
  }

  @Test
  fun `without a registry every golden case is stored exactly as the earlier rules stored it`() {
    TellomiLinkRegistry.setForTesting(null)
    val cases = golden["classify"]!!.jsonArray.map { it.jsonObject }
    var kept = 0
    var dropped = 0

    for (case in cases) {
      val previewJson = Json.parseToJsonElement(case.str("preview")!!).jsonObject
      val proto = try {
        preview(previewJson.str("url")!!, title = previewJson.str("title"), withImage = previewJson["has_image"]?.jsonPrimitive?.booleanOrNull == true, rich = previewJson.str("rich")?.let { Hex.fromStringCondensed(it) })
      } catch (e: Exception) {
        continue
      }
      val messageJson = Json.parseToJsonElement(case.str("message")?.takeIf { it.isNotEmpty() } ?: "{}").jsonObject
      val isStory = messageJson["is_story"]?.jsonPrimitive?.boolean ?: false
      val attachments = messageJson["attachment_content_types"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
      val name = case.str("name")!!

      val now = DataMessageProcessor.getLinkPreviews(listOf(proto), case.str("body")!!, isStory, attachments)
      val before = earlierRules(listOf(proto), case.str("body")!!, isStory)

      assertEquals(name, before.size, now.size)
      now.zip(before).forEach { (a, b) ->
        assertEquals(name, b.serialize(), a.serialize())
        assertEquals("image: $name", b.thumbnail.isPresent, a.thumbnail.isPresent)
        assertArrayEquals("rich: $name", b.rich, a.rich)
      }
      if (now.isEmpty()) dropped++ else kept++
    }
    assertTrue("both outcomes were exercised ($kept kept, $dropped dropped)", kept >= 20 && dropped >= 4)
  }
}
