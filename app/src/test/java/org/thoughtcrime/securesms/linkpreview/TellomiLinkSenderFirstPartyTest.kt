/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.attachments.Attachment
import java.util.Locale
import java.util.Optional

/**
 * ADR-0063 §4.8 / card-visual §3.9 (tellomi/tellomi#1422): what the sender's own lookup of a tell.cc object
 * tells rust/links — the name, and how many members or stickers it has, so the receiver's card can say
 * "12 members" / "24 stickers". Same JSON as the golden's `first_party` script and as Desktop
 * (tellomi/Signal-Desktop#29). A scripted job stands in for rust/links, so this runs without the native library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkSenderFirstPartyTest {

  @get:Rule
  val fixture = TellomiLinkFetchFixture()

  private class ScriptedJob(private val kind: String) : TellomiLinkSendJob.Job {
    var firstPartyResult: String? = null
    private var asked = false

    override fun nextRequest(): String? {
      if (asked) return null
      asked = true
      return """{"id":1,"type":"first_party","kind":"$kind"}"""
    }

    override fun onResponse(id: Int, status: Int, finalUrl: String, contentType: String, location: String?, body: ByteArray) = error("no fetch expected")
    override fun onNetworkError(id: Int) = error("no fetch expected")
    override fun onFailure(id: Int) = error("no fetch expected")
    override fun onFirstParty(id: Int, result: String) {
      firstPartyResult = result
    }
    override fun onImage(id: Int, ok: Boolean) = error("no image expected")
    override fun finish(): String = """{"level":"first_party","provider":"tellomi","route":null,"kind":"$kind","preview":null,"group_link_invalid":false,"lookalike":null,"newly_unreachable_hosts":[],"failures":[]}"""
  }

  private class Lookups(val firstParty: TellomiLinkSender.FirstParty) : TellomiLinkSender.Lookups {
    override fun firstParty(kind: String, url: String): TellomiLinkSender.FirstParty = firstParty
    override fun thumbnail(bytes: ByteArray): Attachment? = null
  }

  private fun sentToRustLinks(kind: String, firstParty: TellomiLinkSender.FirstParty): String? {
    val fetcher = fixture.fetcher()
    val sender = TellomiLinkSender(fetcher, expandShortLinks = { true }, locale = { Locale.US }, lookups = Lookups(firstParty))
    val job = ScriptedJob(kind)
    sender.preview(job, "https://tell.cc/x", fetcher.newSession()) { false }
    return job.firstPartyResult
  }

  private fun found(title: String, count: Int?): TellomiLinkSender.FirstParty {
    return TellomiLinkSender.FirstParty.Found(LinkPreview("https://tell.cc/x", title, "", 0, Optional.empty()), count)
  }

  @Test
  fun `a group passes its name and member count, as in the golden`() {
    assertEquals("""{"ok":true,"title":"周末爬山群","member_count":12}""", sentToRustLinks("tellomi.group", found("周末爬山群", 12)))
  }

  @Test
  fun `a sticker pack passes its name and sticker count`() {
    assertEquals("""{"ok":true,"title":"Bandit","sticker_count":24}""", sentToRustLinks("tellomi.sticker", found("Bandit", 24)))
  }

  @Test
  fun `a count that is not above zero is left out`() {
    assertEquals("""{"ok":true,"title":"G"}""", sentToRustLinks("tellomi.group", found("G", 0)))
    assertEquals("""{"ok":true,"title":"G"}""", sentToRustLinks("tellomi.group", found("G", null)))
  }

  @Test
  fun `a call link has no count`() {
    assertEquals("""{"ok":true,"title":"Camping Prep"}""", sentToRustLinks("tellomi.call", found("Camping Prep", 3)))
  }

  @Test
  fun `an inactive group link and a lookup that found nothing say so`() {
    assertEquals("""{"ok":false,"invalid":true}""", sentToRustLinks("tellomi.group", TellomiLinkSender.FirstParty.Inactive))
    assertEquals("""{"ok":false}""", sentToRustLinks("tellomi.sticker", TellomiLinkSender.FirstParty.NotFound))
  }
}
