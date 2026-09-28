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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.Hex
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.testutil.UriAttachmentBuilder
import java.util.Optional

/**
 * ADR-0063 §5.1 rule 4 (tellomi/tellomi#1422): every golden `classify` case from rust/links, fed
 * through Android's own shapes (a [LinkPreview] with its thumbnail and raw `rich` bytes, the slide
 * deck's content types), must give exactly the card Rust gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiLinkRegistryTest {

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  private val golden = Json.parseToJsonElement(String(resource("classify-golden.json"))).jsonObject

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
  }

  @Test
  fun `gives the rust-links card for every golden case`() {
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(resource(golden.str("registry")!!)))
    val cases = golden["classify"]!!.jsonArray.map { it.jsonObject }
    assert(cases.size >= 40)

    for (case in cases) {
      val name = case.str("name")!!
      val preview = Json.parseToJsonElement(case.str("preview")!!).jsonObject
      val message = Json.parseToJsonElement(case.str("message")?.takeIf { it.isNotEmpty() } ?: "{}").jsonObject

      val thumbnail: Optional<Attachment> = if (preview["has_image"]?.jsonPrimitive?.booleanOrNull == true) {
        Optional.of(UriAttachmentBuilder.build(id = 1, contentType = "image/jpeg"))
      } else {
        Optional.empty()
      }
      val linkPreview = LinkPreview(
        preview.str("url")!!,
        preview.str("title") ?: "",
        preview.str("description") ?: "",
        0,
        thumbnail,
        preview.str("rich")?.let { Hex.fromStringCondensed(it) }
      )

      val card = TellomiLinkRegistry.classify(
        linkPreview,
        case.str("body")!!,
        message["is_story"]?.jsonPrimitive?.boolean ?: false,
        message["attachment_content_types"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
      )

      val expected = TellomiLinkCard.parse(case.str("card")!!)
      assertNotNull("golden card parses: $name", expected)
      assertEquals(name, expected, card)
    }
  }

  @Test
  fun `makes no decision without a registry`() {
    TellomiLinkRegistry.setForTesting(null)
    val card = TellomiLinkRegistry.classify(
      LinkPreview("https://example.com/", "Example", "", 0, Optional.empty()),
      "https://example.com/",
      false,
      emptyList()
    )
    assertNull(card)
  }

  @Test
  fun `sends rich as hex and says whether there is an image`() {
    val withRich = TellomiLinkCard.previewInputJson(
      LinkPreview("https://a.example/", "A", "", 0, Optional.of(UriAttachmentBuilder.build(id = 2, contentType = "image/png")), byteArrayOf(0x0a, 0x05, 0x76))
    )
    assertEquals("""{"url":"https://a.example/","title":"A","has_image":true,"rich":"0a0576"}""", withRich)

    val plain = TellomiLinkCard.previewInputJson(LinkPreview("https://b.example/", "", "", 0, Optional.empty()))
    assertEquals("""{"url":"https://b.example/","has_image":false}""", plain)
  }
}
