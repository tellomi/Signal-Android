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
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.R

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
}
