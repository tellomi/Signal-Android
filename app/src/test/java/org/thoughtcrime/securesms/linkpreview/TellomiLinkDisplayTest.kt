/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.util.Locale
import java.util.Optional

/**
 * ADR-0063 §5.1 / §4.8 (tellomi/tellomi#1422): what each level shows in the message bubble, per the
 * finalized card spec (card-visual §3.7 / §3.9 / §3.10, 2026-09-29): title, one sub line, domain line;
 * the sender's description never shows. Robolectric because [LinkPreview.getTitle] unescapes HTML.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkDisplayTest {

  private val strings = TellomiLinkDisplay.Strings(
    officialTitle = "Tellomi website",
    tellomiUser = "Tellomi user",
    place = "Location",
    kindName = { kind -> mapOf("product" to "Product", "web" to "Web page", "video" to "Video")[kind] },
    trackCount = { count -> "$count tracks" },
    date = { millis -> "D$millis" }
  )
  private val snapshot = LinkPreview("https://www.bilibili.com/video/BV1", "Sender title", "Sender description", 0, Optional.empty())
  private val base = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, domain = "bilibili.com")

  private fun structured(kind: String, vararg attrs: Pair<String, String>, title: String? = "Title") = base.copy(
    level = TellomiLinkCard.Level.STRUCTURED,
    kind = kind,
    title = title,
    attrs = attrs.map { TellomiLinkCard.Attr(it.first, it.second) }
  )

  private fun line(card: TellomiLinkCard): String? = TellomiLinkDisplay.of(snapshot, card, Locale.US, strings)?.description

  @Test
  fun `no decision keeps Signal's display`() {
    assertNull(TellomiLinkDisplay.of(snapshot, null, Locale.US, strings))
  }

  @Test
  fun `generic shows the snapshot title and the registrable domain, never the description`() {
    assertEquals(TellomiLinkDisplay("Sender title", null, "bilibili.com", false), TellomiLinkDisplay.of(snapshot, base, Locale.US, strings))

    val untitled = LinkPreview("https://example.com/a", "", "Sender description", 0, Optional.empty())
    assertEquals(TellomiLinkDisplay(null, null, "example.com", false), TellomiLinkDisplay.of(untitled, base.copy(domain = "example.com"), Locale.US, strings))
  }

  @Test
  fun `a brand shell shows the platform name and what the link is`() {
    val card = base.copy(
      level = TellomiLinkCard.Level.BRAND,
      provider = "taobao",
      providerName = TellomiLinkCard.LocalizedName(zhHans = "淘宝", en = "Taobao"),
      kind = "product",
      domain = "taobao.com",
      showImage = false
    )
    assertEquals(TellomiLinkDisplay("Taobao", "Product", "taobao.com", false), TellomiLinkDisplay.of(snapshot, card, Locale.US, strings))
    assertEquals("淘宝", TellomiLinkDisplay.of(snapshot, card, Locale.SIMPLIFIED_CHINESE, strings)?.title)
    assertEquals("Web page", line(card.copy(kind = "web")))
    assertNull(line(card.copy(kind = null)))
    assertNull("no English kind id when the table has no name for it", line(card.copy(kind = "podcast")))
  }

  @Test
  fun `a video shows author and duration, and the publish date after the domain`() {
    val publishedAt = "2026-09-01T08:00:00+08:00"
    val card = structured("video", "duration_ms" to "3723000", "author" to "柯洁", "published_at" to publishedAt, title = "《柯洁围棋入门课》")
    val millis = OffsetDateTime.parse(publishedAt).toInstant().toEpochMilli()

    // card-visual §3.4 / §3.7: the domain and the date are joined with U+22C5, the sub line's parts with U+00B7.
    assertEquals(
      TellomiLinkDisplay("《柯洁围棋入门课》", "柯洁 · 1:02:03", "bilibili.com ⋅ D$millis", false),
      TellomiLinkDisplay.of(snapshot, card, Locale.US, strings)
    )
    assertEquals("bilibili.com", TellomiLinkDisplay.of(snapshot, structured("video", "published_at" to "not a date"), Locale.US, strings)?.domain)
  }

  @Test
  fun `the domain and the publish date are joined by a dot operator, not the middle dot of the sub line`() {
    val card = structured("video", "author" to "柯洁", "duration_ms" to "65000", "published_at" to "2026-09-01T08:00:00+08:00")
    val shown = TellomiLinkDisplay.of(snapshot, card, Locale.US, strings)!!

    assertEquals("the sub line keeps U+00B7", "柯洁 · 1:05", shown.description)
    assertEquals("the domain line uses U+22C5", "bilibili.com ⋅ D${OffsetDateTime.parse("2026-09-01T08:00:00+08:00").toInstant().toEpochMilli()}", shown.domain)
  }

  @Test
  fun `each structured kind has its own sub line`() {
    assertEquals("Up", line(structured("channel", "author" to "Up")))
    assertEquals("Artist · Album · 3:45", line(structured("music.track", "album" to "Album", "duration_ms" to "225000", "artist" to "Artist")))
    assertEquals("Artist · 12 tracks", line(structured("music.album", "track_count" to "12", "artist" to "Artist")))
    assertEquals("Curator · 30 tracks", line(structured("music.playlist", "author" to "Curator", "track_count" to "30")))
    assertEquals("Developer · iOS", line(structured("app", "platform" to "ios", "developer" to "Developer")))
    assertEquals("Android", line(structured("app", "platform" to "android")))
    assertEquals("tellomi", line(structured("repo", "owner" to "tellomi")))
    assertNull("a video's date is on the domain line, not the sub line", line(structured("video", "published_at" to "2026-09-01T00:00:00Z")))
  }

  @Test
  fun `a sub line skips missing and invalid values`() {
    assertEquals("1:05", line(structured("video", "duration_ms" to "65000")))
    assertNull(line(structured("video", "duration_ms" to "0")))
    assertNull(line(structured("music.album", "track_count" to "0")))
    assertNull(line(structured("music.album", "track_count" to "many")))
    assertNull("attrs of other kinds are not shown", line(structured("repo", "author" to "Someone")))
  }

  @Test
  fun `a place is titled by its name, never shows coordinates`() {
    val place = structured("place", "lat" to "31.2", "lng" to "121.4", "coord_sys" to "gcj02", title = null)
    val untitled = LinkPreview("https://www.bilibili.com/video/BV1", "", "Sender description", 0, Optional.empty())
    assertEquals(TellomiLinkDisplay("Location", null, "bilibili.com", false), TellomiLinkDisplay.of(untitled, place, Locale.US, strings))
    assertEquals("Sender title", TellomiLinkDisplay.of(snapshot, place, Locale.US, strings)?.title)

    val named = structured("place", "name" to "外滩", "address" to "上海市黄浦区中山东一路", title = "Title")
    assertEquals(TellomiLinkDisplay("外滩", "上海市黄浦区中山东一路", "bilibili.com", false), TellomiLinkDisplay.of(snapshot, named, Locale.US, strings))
  }

  @Test
  fun `a structured kind this build does not know shows like generic`() {
    val card = structured("podcast", "author" to "Host", title = "Episode 1")
    assertEquals(TellomiLinkDisplay("Episode 1", null, "bilibili.com", false), TellomiLinkDisplay.of(snapshot, card, Locale.US, strings))
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
  fun `a plain-link card shows the domain once, as the title, with the link icon`() {
    val plain = TellomiLinkCard(level = TellomiLinkCard.Level.PLAIN_LINK, domain = "163.com", showImage = false)
    assertEquals(
      TellomiLinkDisplay("163.com", null, null, false, plainLink = true, lookalike = false),
      TellomiLinkDisplay.of(snapshot, plain, Locale.US, strings)
    )
    assertEquals(true, TellomiLinkDisplay.of(snapshot, plain.copy(domain = "bi1ibili.com", lookalike = "bilibili.com"), Locale.US, strings)?.lookalike)
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
  fun `formats durations`() {
    assertEquals("0:01", TellomiLinkDisplay.formatDuration("500"))
    assertEquals("3:45", TellomiLinkDisplay.formatDuration("225000"))
    assertEquals("1:02:07", TellomiLinkDisplay.formatDuration("3727000"))
    assertNull(TellomiLinkDisplay.formatDuration("0"))
    assertNull(TellomiLinkDisplay.formatDuration("-5"))
    assertNull(TellomiLinkDisplay.formatDuration("long"))
    assertNull(TellomiLinkDisplay.formatDuration(null))
  }

  /** card-visual §3.9: rounded to the second; "0 or not valid shows nothing", and a duration that rounds to 0 is 0. */
  @Test
  fun `a duration that rounds to zero seconds shows nothing`() {
    assertNull("1 ms", TellomiLinkDisplay.formatDuration("1"))
    assertNull("499 ms rounds down to 0", TellomiLinkDisplay.formatDuration("499"))
    assertEquals("500 ms rounds up to 1", "0:01", TellomiLinkDisplay.formatDuration("500"))
    assertEquals("0:01", TellomiLinkDisplay.formatDuration("999"))
    assertEquals("0:01", TellomiLinkDisplay.formatDuration("1499"))
    assertEquals("0:02", TellomiLinkDisplay.formatDuration("1500"))
    assertEquals("59:59", TellomiLinkDisplay.formatDuration("3599499"))
    assertEquals("1:00:00", TellomiLinkDisplay.formatDuration("3599500"))
    assertNull("empty", TellomiLinkDisplay.formatDuration(""))
    assertNull("a fraction is not a whole number of milliseconds", TellomiLinkDisplay.formatDuration("1.5"))
    assertNull("too big for a Long is not a number", TellomiLinkDisplay.formatDuration("9223372036854775808"))
  }

  @Test
  fun `a video whose duration rounds to zero has no duration on its sub line`() {
    assertEquals("柯洁", line(structured("video", "author" to "柯洁", "duration_ms" to "499")))
    assertNull(line(structured("video", "duration_ms" to "499")))
    assertEquals("柯洁 · 0:01", line(structured("video", "author" to "柯洁", "duration_ms" to "500")))
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
