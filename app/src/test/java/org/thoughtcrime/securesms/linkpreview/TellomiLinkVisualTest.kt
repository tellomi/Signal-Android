/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * card-visual §3.2 / §3.3 / §3.8 (tellomi/tellomi#1422): the shape of a card and the colours from its own image come from
 * rust/links (`layout` / `tint`); the client only reads the answer and applies it. Same cases as Desktop
 * (`linkCardVisual_test.std.ts`, tellomi/Signal-Desktop#30).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiLinkVisualTest {

  private val orange = """{"tinted":true,"source":"#FE7500","light":{"background":"#FE7500","text":"#000000"},"dark":{"background":"#994600","text":"#FFFFFF"}}"""
  private val card = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, domain = "bilibili.com", tintable = true)

  @Test
  fun `the four shapes are read, anything else is no decision`() {
    assertEquals(TellomiLinkVisual.Layout.FIRST_PARTY, TellomiLinkVisual.parseLayout("first_party"))
    assertEquals(TellomiLinkVisual.Layout.LARGE_IMAGE, TellomiLinkVisual.parseLayout("large_image"))
    assertEquals(TellomiLinkVisual.Layout.ICON, TellomiLinkVisual.parseLayout("icon"))
    assertEquals(TellomiLinkVisual.Layout.NO_IMAGE, TellomiLinkVisual.parseLayout("no_image"))
    assertNull(TellomiLinkVisual.parseLayout("hologram"))
    assertNull(TellomiLinkVisual.parseLayout(""))
    assertNull(TellomiLinkVisual.parseLayout("\"icon\""))
  }

  @Test
  fun `a tinted result has a light and a dark set of colours`() {
    val tint = TellomiLinkVisual.parseTint(orange)
    assertEquals(
      TellomiLinkVisual.Tint(
        tinted = true,
        light = TellomiLinkVisual.Colors(Color.parseColor("#FE7500"), Color.parseColor("#000000")),
        dark = TellomiLinkVisual.Colors(Color.parseColor("#994600"), Color.parseColor("#FFFFFF"))
      ),
      tint
    )
    assertEquals(Color.parseColor("#FE7500"), tint?.colors(isDark = false)?.background)
    assertEquals(Color.parseColor("#994600"), tint?.colors(isDark = true)?.background)
    assertEquals(Color.parseColor("#FFFFFF"), tint?.colors(isDark = true)?.text)
  }

  @Test
  fun `a neutral image keeps the default colours`() {
    val tint = TellomiLinkVisual.parseTint("""{"tinted":false,"source":"#808082"}""")
    assertEquals(TellomiLinkVisual.Tint(tinted = false), tint)
    assertNull(tint?.colors(isDark = false))
    assertNull(TellomiLinkVisual.Tint(tinted = true).colors(isDark = true))
  }

  @Test
  fun `anything it does not understand is no decision`() {
    assertNull(TellomiLinkVisual.parseTint("not json"))
    assertNull(TellomiLinkVisual.parseTint("{}"))
    assertNull(TellomiLinkVisual.parseTint("""{"tinted":true}"""))
    assertNull(
      "a colour that is not #RRGGBB is not applied",
      TellomiLinkVisual.parseTint("""{"tinted":true,"light":{"background":"red","text":"#000000"},"dark":{"background":"#994600","text":"#FFFFFF"}}""")
    )
  }

  @Test
  fun `only third-party cards with an image are tinted, and never in a message request`() {
    assertTrue(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.ICON, isMessageRequest = false))
    assertTrue(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.LARGE_IMAGE, isMessageRequest = false))
    assertFalse(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.ICON, isMessageRequest = true))
    assertFalse(TellomiLinkVisual.shouldTint(card.copy(tintable = false), TellomiLinkVisual.Layout.ICON, isMessageRequest = false))
    assertFalse(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.FIRST_PARTY, isMessageRequest = false))
    assertFalse(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.NO_IMAGE, isMessageRequest = false))
    assertFalse(TellomiLinkVisual.shouldTint(card, null, isMessageRequest = false))
    assertFalse(TellomiLinkVisual.shouldTint(null, TellomiLinkVisual.Layout.ICON, isMessageRequest = false))
  }

  @Test
  fun `the layout is asked for the size of the image the card shows, and none for a card that shows none`() {
    val asked = mutableListOf<List<Any>>()
    val bridge = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String {
        asked += listOf(imageWidth, imageHeight, kind, level)
        return "icon"
      }

      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String = error("not asked")
    }

    val structured = card.copy(level = TellomiLinkCard.Level.STRUCTURED, kind = "video")
    assertEquals(TellomiLinkVisual.Layout.ICON, TellomiLinkVisual.layout(bridge, structured, imageWidth = 1200, imageHeight = 630))
    assertEquals(listOf<Any>(1200, 630, "video", "structured"), asked[0])

    TellomiLinkVisual.layout(bridge, card.copy(level = TellomiLinkCard.Level.FIRST_PARTY, kind = "tellomi.group"), imageWidth = 1200, imageHeight = 630)
    assertEquals(listOf<Any>(1200, 630, "tellomi.group", "first_party"), asked[1])

    TellomiLinkVisual.layout(bridge, card.copy(showImage = false), imageWidth = 1200, imageHeight = 630)
    assertEquals("a card that shows no image is asked with none", listOf<Any>(0, 0, "", "generic"), asked[2])

    TellomiLinkVisual.layout(bridge, card.copy(level = TellomiLinkCard.Level.PLAIN_LINK), imageWidth = 0, imageHeight = 0)
    assertEquals("plain_link", asked[3][3])
  }

  @Test
  fun `a bridge that fails is no decision`() {
    val failing = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = throw IllegalStateException("no native library")
      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String = throw IllegalStateException("no native library")
    }
    assertNull(TellomiLinkVisual.layout(failing, card, imageWidth = 100, imageHeight = 100))
    assertNull(TellomiLinkVisual.tint(failing, TellomiLinkVisual.Layout.ICON, solidPng(Color.rgb(254, 117, 0))))
  }

  @Test
  fun `the image is reduced to 32 by 32 RGBA before rust-links sees it`() {
    var seen: Triple<String, Int, Int>? = null
    var pixels: ByteArray? = null
    val bridge = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = error("not asked")

      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String {
        seen = Triple(layout, width, height)
        pixels = rgba
        return orange
      }
    }

    val tint = TellomiLinkVisual.tint(bridge, TellomiLinkVisual.Layout.ICON, solidPng(Color.rgb(254, 117, 0), width = 200, height = 100))

    assertNotNull(tint)
    assertEquals(Triple("icon", 32, 32), seen)
    assertEquals(32 * 32 * 4, pixels?.size)
    assertArrayEquals("first pixel is opaque orange, as R G B A", byteArrayOf(0xFE.toByte(), 0x75, 0x00, 0xFF.toByte()), pixels?.copyOfRange(0, 4))
    assertArrayEquals("and so is the last", byteArrayOf(0xFE.toByte(), 0x75, 0x00, 0xFF.toByte()), pixels?.copyOfRange(32 * 32 * 4 - 4, 32 * 32 * 4))
  }

  @Test
  fun `an image that cannot be decoded is not tinted`() {
    val bridge = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = error("not asked")
      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String = error("not asked")
    }
    assertNull(TellomiLinkVisual.tint(bridge, TellomiLinkVisual.Layout.ICON, byteArrayOf(1, 2, 3)))
  }

  private fun bridgeOf(layoutName: String = "icon", tintJson: String = orange, onTint: () -> Unit = {}) = object : TellomiLinkVisual.Bridge {
    override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = layoutName
    override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String {
      onTint()
      return tintJson
    }
  }

  @Test
  fun `a card gets its shape and the colours of its image`() {
    val visual = TellomiLinkVisual.decide(bridgeOf(), card, 100, 100, "decide-1") { solidPng(Color.rgb(254, 117, 0)) }

    assertEquals(TellomiLinkVisual.Layout.ICON, visual.layout)
    assertEquals(Color.parseColor("#FE7500"), visual.tint?.colors(isDark = false)?.background)
  }

  @Test
  fun `no card, no decision`() {
    assertEquals(TellomiLinkVisual.Visual.NONE, TellomiLinkVisual.decide(bridgeOf(), null, 100, 100, "decide-2") { error("not read") })
    assertEquals(TellomiLinkVisual.Visual.NONE, TellomiLinkVisual.decide(bridgeOf(layoutName = "hologram"), card, 100, 100, "decide-2") { error("not read") })
  }

  @Test
  fun `a card that is not tinted never reads its image`() {
    val notTintable = card.copy(tintable = false)
    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.ICON, null), TellomiLinkVisual.decide(bridgeOf(), notTintable, 100, 100, "decide-3") { error("not read") })

    val firstParty = card.copy(level = TellomiLinkCard.Level.FIRST_PARTY)
    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.FIRST_PARTY, null), TellomiLinkVisual.decide(bridgeOf("first_party"), firstParty, 100, 100, "decide-3") { error("not read") })

    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null), TellomiLinkVisual.decide(bridgeOf("no_image"), card, 0, 0, null) { error("not read") })
  }

  @Test
  fun `an image that is not on the device yet gives a shape and no colours`() {
    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.ICON, null), TellomiLinkVisual.decide(bridgeOf(), card, 100, 100, "decide-4") { null })
  }

  @Test
  fun `a neutral image gives no colours, and the same image is read once`() {
    var tints = 0
    var reads = 0
    val neutral = bridgeOf(tintJson = """{"tinted":false,"source":"#808082"}""", onTint = { tints++ })
    repeat(2) {
      val visual = TellomiLinkVisual.decide(neutral, card, 100, 100, "decide-5") {
        reads++
        solidPng(Color.GRAY)
      }
      assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.ICON, null), visual)
    }
    assertEquals(1, reads)
    assertEquals(1, tints)
  }

  private fun solidPng(color: Int, width: Int = 64, height: Int = 64): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color)
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }
}
