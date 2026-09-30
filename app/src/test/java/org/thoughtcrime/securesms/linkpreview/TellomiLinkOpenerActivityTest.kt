/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import android.text.SpannableStringBuilder
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import org.signal.core.util.logging.Log
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.v2.items.V2ConversationItemUtils
import org.thoughtcrime.securesms.util.InterceptableLongClickCopyLinkSpan

/**
 * ADR-0063 §4.9 / §6.1 (tellomi/tellomi#1422): what actually gets started when a link in a message is
 * tapped, with the registry that ships with the app. Robolectric runs at API 28 here unless a test says otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkOpenerActivityTest {

  private val bilibili = "https://www.bilibili.com/video/BV1YDhJ6ZEL6/?spm_id_from=333.1007"
  private val lookalike = "https://www.bi1ibili.com/video/BV1YDhJ6ZEL6"
  private val alipay = "https://render.alipay.com/p/f/fd-j5rqp49m/index.html"
  private val group = "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

  private lateinit var activity: AppCompatActivity
  private var fellBack = false

  @Before
  fun setUp() {
    val golden = Json.parseToJsonElement(resource("open-plan-golden.json").decodeToString()).jsonObject
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(resource(golden["registry"]!!.jsonPrimitive.content)))

    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.Signal_DayNight)
    activity = controller.setup().get()
  }

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
  }

  @Test
  fun `a tell-cc link stays inside Tellomi`() {
    open(group)

    val started = nextStarted()!!
    assertEquals(Intent.ACTION_VIEW, started.action)
    assertEquals(Uri.parse(group), started.data)
    assertEquals(activity.packageName, started.`package`)
    assertNull(nextStarted())
    assertFalse(fellBack)
  }

  @Test
  fun `a platform link goes to its one installed app`() {
    installApp("tv.danmaku.bili", bilibili)
    installBrowser("com.example.browser", bilibili)

    open(bilibili)

    val started = nextStarted()!!
    assertEquals("tv.danmaku.bili", started.`package`)
    assertEquals(Uri.parse(bilibili), started.data)
    assertNull(started.selector)
    assertNull(nextStarted())
  }

  @Test
  fun `without the app installed a platform link goes to a browser, never to another app`() {
    installBrowser("com.example.browser", bilibili)

    open(bilibili)

    val started = nextStarted()!!
    assertNull(started.`package`)
    assertTrue(started.selector!!.hasCategory(Intent.CATEGORY_APP_BROWSER))
    assertNull(nextStarted())
  }

  @Test
  @Config(sdk = [30])
  fun `from API 30 the system is asked for an installed app that is not a browser`() {
    open(bilibili)

    val started = nextStarted()!!
    assertEquals(Uri.parse(bilibili), started.data)
    assertTrue(started.flags and Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER != 0)
    assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
    assertNull(started.`package`)
  }

  @Test
  @Config(sdk = [30])
  fun `when nothing can open it the link is copied`() {
    shadowOf(activity.application).checkActivities(true)

    open(bilibili)

    assertNull(nextStarted())
    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    assertEquals(bilibili, clipboard.primaryClip!!.getItemAt(0).text.toString())
  }

  @Test
  fun `the copy step copies the link and says so`() {
    assertTrue(TellomiLinkOpener.ActivityLauncher(activity).copyLink(group))

    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    assertEquals(group, clipboard.primaryClip!!.getItemAt(0).text.toString())
    assertEquals(activity.getString(R.string.TellomiLinks__link_copied), ShadowToast.getTextOfLatestToast())
    assertNull(nextStarted())
  }

  @Test
  fun `a payment link only goes to a browser, even with the app installed`() {
    installApp("com.eg.android.AlipayGphone", alipay)

    open(alipay)

    val started = nextStarted()!!
    assertNull(started.`package`)
    assertTrue(started.selector!!.hasCategory(Intent.CATEGORY_APP_BROWSER))
    assertNull(nextStarted())
  }

  @Test
  fun `a lookalike domain is only opened after the warning is confirmed`() {
    open(lookalike)
    assertNull(nextStarted())
    val cancelled = latestDialog()
    assertTrue(cancelled.isShowing)
    assertEquals(
      activity.getString(R.string.TellomiLinkOpen__lookalike_message, "bilibili.com"),
      cancelled.findViewById<TextView>(android.R.id.message)!!.text.toString()
    )
    cancelled.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    assertNull(nextStarted())

    open(lookalike)
    val confirmed = latestDialog()
    assertEquals(activity.getString(R.string.TellomiLinkOpen__open_anyway), confirmed.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
    confirmed.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    val started = nextStarted()!!
    assertEquals(Uri.parse(lookalike), started.data)
    assertFalse(fellBack)
  }

  @Test
  fun `an intent link opens nothing and shows nothing`() {
    open("intent://scan/#Intent;scheme=zxing;end")

    assertNull(nextStarted())
    assertNull(ShadowDialog.getLatestDialog())
    assertFalse(fellBack)
  }

  @Test
  fun `an email or phone link is handed to Signal's own handling, not swallowed`() {
    for (url in listOf("mailto:a@b.co", "tel:+8613800000000", "MAILTO:a@b.co", "Tel:+8613800000000")) {
      fellBack = false

      open(url)

      assertTrue("$url should go to the system like upstream", fellBack)
      assertNull(url, nextStarted())
      assertNull(url, ShadowDialog.getLatestDialog())
    }
  }

  @Test
  fun `the email and phone links Signal finds in a message arrive as mailto and tel and open`() {
    val body = SpannableStringBuilder("write to a@b.co or call +86 138 0000 0000")
    val clicked = mutableListOf<String>()
    V2ConversationItemUtils.linkifyUrlLinks(body, true) { url ->
      clicked += url
      true
    }
    val spans = body.getSpans(0, body.length, InterceptableLongClickCopyLinkSpan::class.java)
    spans.sortedBy { body.getSpanStart(it) }.forEach { it.onClick(TextView(activity)) }
    assertEquals(listOf("mailto:a@b.co", "tel:+8613800000000"), clicked)

    val opened = mutableListOf<String>()
    for (url in clicked) {
      TellomiLinkOpener.open(activity, url) { opened += url }
    }
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals("every tapped link should reach the system handling", clicked, opened)
    assertNull(nextStarted())
  }

  @Test
  fun `only mailto and tel get through with an empty plan, every other scheme opens nothing`() {
    val notOpened = listOf(
      "intent://scan/#Intent;scheme=zxing;end",
      "intent:#Intent;action=android.intent.action.VIEW;end",
      "javascript:alert(1)",
      "JavaScript:alert(1)",
      "javascript:alert('mailto:a@b.co')",
      "intent://x#Intent;S.browser_fallback_url=tel:+8613800000000;end",
      "data:text/html;base64,PGI+eDwvYj4=",
      "file:///sdcard/Download/a.txt",
      "content://com.example.provider/a",
      "sms:+8613800000000",
      "smsto:+8613800000000?body=x",
      "geo:0,0?q=x",
      "market://details?id=com.example",
      "tg://resolve?domain=x",
      "weixin://dl/scan",
      "ftp://a.example/x",
      "mailto",
      "tell:+8613800000000",
      "xmailto:a@b.co",
      "mailto.evil:a@b.co",
      " intent:#Intent;end",
      "//a.example/x",
      "a@b.co",
      "+8613800000000"
    )
    for (url in notOpened) {
      fellBack = false

      open(url)

      assertFalse("$url must not reach the system", fellBack)
      assertNull(url, nextStarted())
      assertNull(url, ShadowDialog.getLatestDialog())
    }
  }

  @Test
  fun `the logs never contain the link, for the schemes that open and for the ones that do not`() {
    val canary = "zqxCANARY7f3e"
    val logger = CapturingLogger()
    Log.initialize(logger)
    try {
      for (url in listOf("mailto:$canary@b.co", "tel:+86$canary", "javascript:$canary", "intent://$canary#Intent;end", "file:///$canary", "data:text/plain,$canary")) {
        open(url)
      }
    } finally {
      Log.initialize()
    }

    assertTrue("expected the opener to log its decisions", logger.lines.isNotEmpty())
    val hits = logger.lines.filter { it.contains(canary, ignoreCase = true) }
    assertEquals("log lines containing the canary:\n" + hits.joinToString("\n"), 0, hits.size)
  }

  @Test
  fun `without a registry Signal's own handling runs`() {
    TellomiLinkRegistry.setForTesting(null)

    open(bilibili)

    assertTrue(fellBack)
    assertNull(nextStarted())
  }

  private fun open(url: String) {
    TellomiLinkOpener.open(activity, url) { fellBack = true }
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun nextStarted(): Intent? = shadowOf(activity).nextStartedActivity

  private fun latestDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  /** An app (not a browser) that claims [url], as the pre-API-30 lookup sees it. */
  private fun installApp(packageName: String, url: String) {
    shadowOf(activity.packageManager).addResolveInfoForIntent(
      Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
      resolveInfo(packageName)
    )
  }

  /** A browser: it handles every web link, including [url]. */
  private fun installBrowser(packageName: String, url: String) {
    val packageManager = shadowOf(activity.packageManager)
    packageManager.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE), resolveInfo(packageName))
    packageManager.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE), resolveInfo(packageName))
  }

  private fun resolveInfo(packageName: String): ResolveInfo {
    return ResolveInfo().apply {
      activityInfo = ActivityInfo().apply {
        this.packageName = packageName
        name = "$packageName.Main"
      }
    }
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
