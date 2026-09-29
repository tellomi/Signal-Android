/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * card-visual §3.5 (tellomi/tellomi#1422): a message that is nothing but one link shows the card alone; with no
 * preview, or a plain-link decision, a no-image card drawn from the URL. Same rules as Desktop
 * (tellomi/Signal-Desktop#26, `linkOnlyMessage.std.ts`).
 */
class TellomiLinkOnlyTest {

  private val url = "https://www.bilibili.com/video/BV1YDhJ6ZEL6"
  private val card = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, title = "Sender title", domain = "bilibili.com")

  @Test
  fun `the link is the whole body, around white space`() {
    assertEquals(url, TellomiLinkOnly.linkOnlyUrl(url, false))
    assertEquals(url, TellomiLinkOnly.linkOnlyUrl("  $url\n", false))
    assertEquals("http://www.163.com/news/article/K1234.html", TellomiLinkOnly.linkOnlyUrl("http://www.163.com/news/article/K1234.html", false))
    assertEquals("HTTPS://WWW.BILIBILI.COM/video/BV1", TellomiLinkOnly.linkOnlyUrl("HTTPS://WWW.BILIBILI.COM/video/BV1", false))
  }

  @Test
  fun `any other text keeps the text`() {
    assertNull(TellomiLinkOnly.linkOnlyUrl("看看这个 $url", false))
    assertNull(TellomiLinkOnly.linkOnlyUrl("$url 看看", false))
    assertNull(TellomiLinkOnly.linkOnlyUrl("$url\n$url", false))
  }

  @Test
  fun `a link the body shows shorter than the text keeps the text`() {
    assertNull(TellomiLinkOnly.linkOnlyUrl("https://www.163.com/a.", false))
    assertNull(TellomiLinkOnly.linkOnlyUrl("https://www.163.com/a b", false))
  }

  @Test
  fun `only http and https links written out in full`() {
    assertNull(TellomiLinkOnly.linkOnlyUrl("tell.cc/kaixin", false))
    assertNull(TellomiLinkOnly.linkOnlyUrl("www.bilibili.com", false))
    assertNull(TellomiLinkOnly.linkOnlyUrl("ftp://example.org/file", false))
    assertNull(TellomiLinkOnly.linkOnlyUrl("mailto:someone@example.org", false))
    assertNull(TellomiLinkOnly.linkOnlyUrl("sgnl://signal.group/#abc", false))
  }

  @Test
  fun `anything but plain text keeps Signal's layout`() {
    assertNull(TellomiLinkOnly.linkOnlyUrl(url, true))
    assertNull(TellomiLinkOnly.linkOnlyUrl(null, false))
    assertNull(TellomiLinkOnly.linkOnlyUrl("", false))
    assertNull(TellomiLinkOnly.linkOnlyUrl("   ", false))
  }

  @Test
  fun `the card stands alone only for the one preview of that link, with a decision`() {
    assertTrue(TellomiLinkOnly.isLinkCardOnly(url, listOf(url), card))
    assertFalse("no decision: Signal's layout", TellomiLinkOnly.isLinkCardOnly(url, listOf(url), null))
    assertFalse(TellomiLinkOnly.isLinkCardOnly(url, listOf("$url/"), card))
    assertFalse(TellomiLinkOnly.isLinkCardOnly(url, emptyList(), card))
    assertFalse(TellomiLinkOnly.isLinkCardOnly(url, listOf(url, url), card))
    assertFalse(TellomiLinkOnly.isLinkCardOnly(null, listOf(url), card))
  }

  @Test
  fun `a plain card keeps only the domain and the lookalike warning`() {
    val firstParty = card.copy(
      level = TellomiLinkCard.Level.FIRST_PARTY,
      provider = "tellomi",
      providerName = TellomiLinkCard.LocalizedName(zhHans = "官网", en = "Website"),
      kind = "tellomi.official",
      route = "official",
      description = "Description",
      attrs = listOf(TellomiLinkCard.Attr("author", "Someone")),
      domain = "tellomi.app",
      officialBadge = true,
      firstParty = TellomiLinkCard.FirstParty(type = "official", path = "/download"),
      lookalike = "tellomi.app",
      showImage = true,
      tintable = true
    )
    assertEquals(
      TellomiLinkCard(level = TellomiLinkCard.Level.PLAIN_LINK, domain = "tellomi.app", lookalike = "tellomi.app", showImage = false, tintable = false),
      TellomiLinkOnly.toPlainLinkCard(firstParty, null)
    )
    assertEquals("bilibili.com", TellomiLinkOnly.toPlainLinkCard(card.copy(domain = "bi1ibili.com"), "bilibili.com").lookalike)
    assertEquals("apple.com", TellomiLinkOnly.toPlainLinkCard(card.copy(lookalike = "apple.com"), null).lookalike)
  }
}
