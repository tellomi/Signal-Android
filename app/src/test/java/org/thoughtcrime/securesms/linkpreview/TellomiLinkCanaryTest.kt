/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.testutil.UriAttachmentBuilder
import java.util.Locale
import java.util.Optional

/**
 * ADR-0063 §8.1 row 9 (audit K5): "send a link with a canary string in its path and in its `#` fragment, go through sending,
 * receiving and opening, export the debug log, and grep for the canary: zero". [TellomiLinkFetcherLoggingTest] covers the fetcher
 * alone; this walks the whole trip with the real rust/links and the registry that ships with the app:
 *
 * - sending: rust/links' job, through the contract fetcher to a local https server ([TellomiLinkSender]), and Signal's own
 *   lookups for a tell.cc object;
 * - receiving: `receive_check` and `classify`, the card and the open plan the message gets ([TellomiLinkReceive], [TellomiLinkOnly]);
 * - the first-party lookup of a tell.cc object in the local database ([TellomiFirstPartyLocalLookup]);
 * - opening: `open_plan`, and the steps, also when nothing can open the link and it is copied ([TellomiLinkOpener]).
 *
 * The canary is made of hex digits, so it is also a valid username, sticker key and group key. Each case checks that the parts that
 * should log something did (a test that logs nothing proves nothing), then that no line holds the canary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class TellomiLinkCanaryTest {

  @get:Rule
  val fixture = TellomiLinkFetchFixture()

  private val d = TellomiLinkFetchFixture.DOMAIN
  private val canary = "cafe0123babe"
  private val logger = CapturingLogger()

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  private val golden = Json.parseToJsonElement(resource("send-golden.json").decodeToString()).jsonObject
  private val registry by lazy { LinkRegistry.load(resource(golden["registry"]!!.jsonPrimitive.content)) }

  private lateinit var activity: AppCompatActivity

  @Before
  fun setUp() {
    Log.initialize(logger)
    TellomiLinkRegistry.setForTesting(registry)
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.Signal_DayNight)
    activity = controller.setup().get()
  }

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
    Log.initialize()
  }

  private class FakeLookups(val firstParty: TellomiLinkSender.FirstParty = TellomiLinkSender.FirstParty.NotFound) : TellomiLinkSender.Lookups {
    override fun firstParty(kind: String, url: String): TellomiLinkSender.FirstParty = firstParty
    override fun thumbnail(bytes: ByteArray): Attachment? = UriAttachmentBuilder.build(id = 7, contentType = "image/jpeg")
  }

  /** The composer's side: rust/links' job, the fetcher, and Signal's lookups. */
  private fun send(url: String, lookups: TellomiLinkSender.Lookups = FakeLookups()): TellomiLinkSender.Result? {
    val fetcher = fixture.fetcher()
    val sender = TellomiLinkSender(fetcher, expandShortLinks = { true }, locale = { Locale.US }, lookups = lookups)
    return sender.preview(registry, url, fetcher.newSession()) { false }
  }

  /** The receiving side and the tap: what is stored, what the bubble draws, what a local lookup finds, what opening does. */
  private fun receiveAndOpen(url: String, title: String) {
    val preview = LinkPreview(url, title, "", 0, Optional.empty())
    TellomiLinkReceive.receive(preview, url, isStory = false, attachmentContentTypes = emptyList())

    val record = FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview))
    val decision = TellomiLinkOnly.decide(record, hasMentions = false)
    TellomiLinkOnly.decideRequest(record, hasMentions = false)
    TellomiFirstPartyLocalLookup.forMessage(record, decision)
    TellomiFirstPartyLocalLookup.lookup(url, decision.card)

    // Tapped: the plan, and the steps it names.
    TellomiLinkOpener.plan(url)
    TellomiLinkOpener.open(activity, url) { }
    // Tapped on a phone where nothing can open it: the link is copied.
    shadowOf(activity.application).checkActivities(true)
    TellomiLinkOpener.open(activity, url) { }
  }

  private fun assertNoCanaryAnywhere(expectedSomewhere: List<String>) {
    for (expected in expectedSomewhere) {
      assertTrue("expected a log line with \"$expected\" (a test that logs nothing proves nothing); the log:\n${logger.lines.joinToString("\n")}", logger.lines.any { it.contains(expected) })
    }
    val hits = logger.lines.filter { it.contains(canary, ignoreCase = true) }
    assertEquals("log lines containing the canary:\n" + hits.joinToString("\n"), 0, hits.size)
  }

  @Test
  fun `a web page with the canary in its path, query and fragment, from the composer to the tap`() {
    val fetched = "https://a.$d/$canary/page?q=$canary"
    val link = "$fetched#$canary-fragment"
    fixture.html(fetched, "<html><head><meta property=\"og:title\" content=\"Title\"></head></html>")

    val sent = send(link) as TellomiLinkSender.Result.Found
    assertEquals(link, sent.preview.url)
    receiveAndOpen(link, sent.preview.title)

    assertNoCanaryAnywhere(listOf("HTML: ok", "generic", "card ", "Opened via", "No browser could open the link"))
  }

  @Test
  fun `a page the composer could not reach, and its short link`() {
    val short = "https://s.$d/$canary"
    val landing = "https://t.$d/$canary/landing?q=$canary"
    fixture.redirect(short, landing)
    fixture.route(landing, MockResponse().setResponseCode(404))
    fixture.redirect("https://a.$d/$canary/private", "https://10.0.0.1/$canary")

    for (link in listOf("$short#$canary", "$landing#$canary", "https://a.$d/$canary/private#$canary", "https://$canary.$d/$canary#$canary")) {
      send(link)
      receiveAndOpen(link, "")
    }

    assertNoCanaryAnywhere(listOf("HTML:", "Opened via"))
  }

  @Test
  fun `a brand shell and a structured link`() {
    val taobao = "https://item.taobao.com/item.htm?id=100032608854&spm=$canary#$canary"
    val bilibili = "https://www.bilibili.com/video/BV1YDhJ6ZEL6/?spm_id_from=$canary#$canary"
    fixture.dns.resolve("www.bilibili.com", "203.0.113.10")
    fixture.html("https://www.bilibili.com/video/BV1YDhJ6ZEL6/?spm_id_from=$canary", "<html><head><meta property=\"og:title\" content=\"Title\"></head></html>")

    for (link in listOf(taobao, bilibili)) {
      send(link)
      receiveAndOpen(link, "Title")
    }

    assertNoCanaryAnywhere(listOf("taobao", "Opened via"))
  }

  @Test
  fun `a tell-cc user, with the canary as its name`() {
    val link = "https://tell.cc/$canary.57"

    val sent = send(link)
    receiveAndOpen(link, "@$canary.57")

    assertNotNull(sent)
    assertNoCanaryAnywhere(listOf("tellomi", "Local lookup failed", "Opened via"))
  }

  @Test
  fun `a tell-cc group, whose invite holds the canary in its fragment`() {
    val link = "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAA${canary}AAAAAAAA"
    val lookups = FakeLookups(TellomiLinkSender.FirstParty.Found(LinkPreview(link, "Group", "", 0, Optional.empty()), count = 12))

    send(link, lookups)
    send(link, FakeLookups(TellomiLinkSender.FirstParty.Inactive))
    send(link, FakeLookups(TellomiLinkSender.FirstParty.NotFound))
    receiveAndOpen(link, "Group")

    assertNoCanaryAnywhere(listOf("tellomi", "Opened via"))
  }

  @Test
  fun `a tell-cc sticker pack and call link, with the canary in the key`() {
    val sticker = "https://tell.cc/s#pack_id=${canary}${canary}${canary.take(8)}&pack_key=${canary.repeat(5)}${canary.take(4)}"
    val call = "https://tell.cc/call#key=$canary-$canary-$canary"

    for (link in listOf(sticker, call)) {
      send(link, FakeLookups(TellomiLinkSender.FirstParty.Found(LinkPreview(link, "Title", "", 0, Optional.empty()), count = 24)))
      receiveAndOpen(link, "Title")
    }

    assertNoCanaryAnywhere(listOf("tellomi", "Opened via"))
  }

  @Test
  fun `a link with the canary that is not one the app opens, and a lookalike`() {
    val links = listOf(
      "https://www.bi1ibili.com/video/BV1YDhJ6ZEL6?x=$canary#$canary",
      "https://render.alipay.com/p/f/$canary/index.html#$canary",
      "javascript:alert('$canary')",
      "intent://$canary#Intent;scheme=x;end"
    )
    for (link in links) {
      receiveAndOpen(link, "")
    }

    assertNoCanaryAnywhere(listOf("Opened via", "Not opening a link that is not http(s)"))
  }

  private class CapturingLogger : Log.Logger() {
    val lines: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    private fun add(level: String, tag: String, message: String?, t: Throwable?) {
      lines += "$level/$tag: $message ${t?.stackTraceToString() ?: ""}"
    }

    override fun v(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("V", tag, message, t)
    override fun d(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("D", tag, message, t)
    override fun i(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("I", tag, message, t)
    override fun w(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("W", tag, message, t)
    override fun e(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("E", tag, message, t)
    override fun flush() = Unit
  }
}
