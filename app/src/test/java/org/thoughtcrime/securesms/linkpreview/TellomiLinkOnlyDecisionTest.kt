/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.mms.SlideDeck

/**
 * card-visual §3.5 (tellomi/tellomi#1422): what the data layer decides for a message's link, before any view binds it.
 * Same cases as Desktop's selector (tellomi/Signal-Desktop#26).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkOnlyDecisionTest {

  private val url = "https://www.163.com/news/article/K1234.html"

  private val classified = mutableListOf<LinkPreview>()

  private fun decide(record: MmsMessageRecord, hasMentions: Boolean = false, card: TellomiLinkCard?, lookalike: String? = null): TellomiLinkOnly.Decision {
    return TellomiLinkOnly.decide(
      record,
      hasMentions,
      { preview, _, _, _ ->
        classified += preview
        card
      },
      { lookalike }
    )
  }

  private fun message(body: String = url, previews: List<LinkPreview> = emptyList()): MmsMessageRecord {
    return FakeMessageRecords.buildMediaMmsMessageRecord(body = body, linkPreviews = previews)
  }

  private val generic = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, title = "网易新闻", domain = "163.com")
  private val plain = TellomiLinkCard(level = TellomiLinkCard.Level.PLAIN_LINK, domain = "163.com", showImage = false, reason = "no_provider")
  private val preview = FakeMessageRecords.buildLinkPreview(url = url, title = "网易新闻")

  @Test
  fun `just a link sent without a preview gets a card drawn from the URL`() {
    val decision = decide(message(), card = plain)

    assertTrue(decision.cardOnly)
    assertEquals(url, decision.localPreview?.url)
    assertEquals("", decision.localPreview?.title)
    assertFalse(decision.localPreview!!.thumbnail.isPresent)
    assertEquals(TellomiLinkCard(level = TellomiLinkCard.Level.PLAIN_LINK, domain = "163.com", showImage = false, tintable = false, reason = "no_provider"), decision.card)
    assertEquals(listOf(url), classified.map { it.url })
  }

  @Test
  fun `the card drawn from the URL is red when the domain imitates a well-known one`() {
    assertEquals("bilibili.com", decide(message("https://www.bi1ibili.com/video/1"), card = plain, lookalike = "bilibili.com").card?.lookalike)
  }

  @Test
  fun `without a registry, or without a domain, there is no card`() {
    assertEquals(TellomiLinkOnly.Decision.NONE, decide(message(), card = null))
    assertEquals(TellomiLinkOnly.Decision.NONE, decide(message(), card = plain.copy(domain = null)))
  }

  @Test
  fun `a message with more than the link, and no preview, is left alone without asking rust-links`() {
    assertEquals(TellomiLinkOnly.Decision.NONE, decide(message("看看 $url"), card = plain))
    assertEquals(TellomiLinkOnly.Decision.NONE, decide(message(), hasMentions = true, card = plain))
    assertTrue(classified.isEmpty())
  }

  @Test
  fun `just the link of its one preview shows the card alone`() {
    assertEquals(TellomiLinkOnly.Decision(generic, null, true), decide(message(previews = listOf(preview)), card = generic))
  }

  @Test
  fun `text around the link keeps the text and the card`() {
    assertEquals(TellomiLinkOnly.Decision(generic, null, false), decide(message("看看 $url", listOf(preview)), card = generic))
  }

  @Test
  fun `without a decision Signal's layout stays`() {
    assertEquals(TellomiLinkOnly.Decision.NONE, decide(message(previews = listOf(preview)), card = null))
  }

  @Test
  fun `a plain-link preview shows as the no-image card only when the message is just the link`() {
    val alone = decide(message(previews = listOf(preview)), card = plain.copy(title = "Sender title"), lookalike = "bilibili.com")
    assertTrue(alone.cardOnly)
    assertNull(alone.localPreview)
    assertNull(alone.card?.title)
    assertEquals("bilibili.com", alone.card?.lookalike)

    assertEquals(TellomiLinkOnly.Decision(plain, null, false), decide(message("看看 $url", listOf(preview)), card = plain))
  }

  @Test
  fun `anything besides plain text keeps Signal's layout`() {
    val record = message()
    assertFalse(TellomiLinkOnly.hasOtherContent(record, false))
    assertTrue(TellomiLinkOnly.hasOtherContent(record, true))

    val attachment = FakeMessageRecords.buildMediaMmsMessageRecord(body = url, slideDeck = SlideDeck(listOf(FakeMessageRecords.buildDatabaseAttachment())))
    val bold = FakeMessageRecords.buildMediaMmsMessageRecord(
      body = url,
      messageRanges = BodyRangeList(ranges = listOf(BodyRangeList.BodyRange(start = 0, length = 5, style = BodyRangeList.BodyRange.Style.BOLD)))
    )
    val viewOnce = FakeMessageRecords.buildMediaMmsMessageRecord(body = url, viewOnce = true)
    val story = FakeMessageRecords.buildMediaMmsMessageRecord(body = url, storyType = StoryType.TEXT_STORY_WITH_REPLIES)

    for (other in listOf(attachment, bold, viewOnce, story)) {
      assertTrue(TellomiLinkOnly.hasOtherContent(other, false))
      assertEquals(TellomiLinkOnly.Decision.NONE, decide(other, card = plain))
    }
  }
}
