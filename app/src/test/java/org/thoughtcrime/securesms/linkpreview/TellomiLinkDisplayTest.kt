/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.Optional

/**
 * ADR-0063 §5.1 / §4.8 (tellomi/tellomi#1422): what each level shows in the message bubble.
 */
class TellomiLinkDisplayTest {

  private val strings = TellomiLinkDisplay.Strings(officialTitle = "Tellomi website", tellomiUser = "Tellomi user")
  private val snapshot = LinkPreview("https://www.bilibili.com/video/BV1", "Sender title", "Sender description", 0, Optional.empty())
  private val base = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, domain = "bilibili.com")

  @Test
  fun `no decision and generic keep Signal's display`() {
    assertNull(TellomiLinkDisplay.of(snapshot, null, Locale.US, strings))
    assertNull(TellomiLinkDisplay.of(snapshot, base, Locale.US, strings))
  }

  @Test
  fun `a brand shell shows only the platform name`() {
    val card = base.copy(
      level = TellomiLinkCard.Level.BRAND,
      provider = "taobao",
      providerName = TellomiLinkCard.LocalizedName(zhHans = "淘宝", en = "Taobao"),
      domain = "taobao.com",
      showImage = false
    )
    assertEquals(TellomiLinkDisplay("Taobao", null, "taobao.com", false), TellomiLinkDisplay.of(snapshot, card, Locale.US, strings))
    assertEquals("淘宝", TellomiLinkDisplay.of(snapshot, card, Locale.SIMPLIFIED_CHINESE, strings)?.title)
  }

  @Test
  fun `a structured card puts the attrs line in place of the description`() {
    val card = base.copy(
      level = TellomiLinkCard.Level.STRUCTURED,
      kind = "video",
      title = "《柯洁围棋入门课》",
      attrs = listOf(
        TellomiLinkCard.Attr("duration_ms", "3723000"),
        TellomiLinkCard.Attr("author", "柯洁"),
        TellomiLinkCard.Attr("published_at", "2026-09-01T00:00:00Z")
      )
    )
    assertEquals(TellomiLinkDisplay("《柯洁围棋入门课》", "柯洁 · 1:02:03", "bilibili.com", false), TellomiLinkDisplay.of(snapshot, card, Locale.US, strings))
  }

  @Test
  fun `the official site card shows fixed text, the path and the badge`() {
    val card = base.copy(
      level = TellomiLinkCard.Level.FIRST_PARTY,
      kind = "tellomi.official",
      domain = "tellomi.app",
      officialBadge = true,
      firstParty = TellomiLinkCard.FirstParty(type = "official", path = "/download"),
      showImage = false
    )
    val lying = LinkPreview("https://tellomi.app/download", "Account locked, reply with your code", "", 0, Optional.empty())
    assertEquals(TellomiLinkDisplay("Tellomi website", "/download", "tellomi.app", true), TellomiLinkDisplay.of(lying, card, Locale.US, strings))
  }

  @Test
  fun `a user card is named from the URL, never by the sender`() {
    fun user(display: String?) = base.copy(
      level = TellomiLinkCard.Level.FIRST_PARTY,
      kind = "tellomi.user",
      domain = "tell.cc",
      firstParty = TellomiLinkCard.FirstParty(type = "user", display = display, username = display?.let { "kefu.57" }),
      showImage = false
    )
    val lying = LinkPreview("https://tell.cc/kefu.57", "@kefu", "", 0, Optional.empty())
    assertEquals(TellomiLinkDisplay("@kefu.57", "Tellomi user", "tell.cc", false), TellomiLinkDisplay.of(lying, user("@kefu.57"), Locale.US, strings))
    assertEquals(TellomiLinkDisplay("Tellomi user", null, "tell.cc", false), TellomiLinkDisplay.of(lying, user(null), Locale.US, strings))
  }

  @Test
  fun `group, call and sticker cards keep Signal's display`() {
    val card = base.copy(
      level = TellomiLinkCard.Level.FIRST_PARTY,
      kind = "tellomi.group",
      firstParty = TellomiLinkCard.FirstParty(type = "group", title = "周末爬山群", memberCount = 12)
    )
    assertNull(TellomiLinkDisplay.of(snapshot, card, Locale.US, strings))
  }

  @Test
  fun `formats short durations and skips attrs not shown as text`() {
    assertEquals("1:05", TellomiLinkDisplay.formatAttrs(base.copy(attrs = listOf(TellomiLinkCard.Attr("duration_ms", "65000")))))
    assertNull(TellomiLinkDisplay.formatAttrs(base.copy(attrs = listOf(TellomiLinkCard.Attr("member_count", "12")))))
  }

  @Test
  fun `picks the script for the locale`() {
    val name = TellomiLinkCard.LocalizedName(zhHans = "网易云音乐", zhHant = "網易雲音樂", en = "NetEase")
    assertEquals("网易云音乐", name.forLocale(Locale.SIMPLIFIED_CHINESE))
    assertEquals("網易雲音樂", name.forLocale(Locale.forLanguageTag("zh-HK")))
    assertEquals("網易雲音樂", name.forLocale(Locale.TRADITIONAL_CHINESE))
    assertEquals("NetEase", name.forLocale(Locale.US))
    assertEquals("淘宝", TellomiLinkCard.LocalizedName(zhHans = "淘宝", en = "Taobao").forLocale(Locale.forLanguageTag("zh-TW")))
  }
}
