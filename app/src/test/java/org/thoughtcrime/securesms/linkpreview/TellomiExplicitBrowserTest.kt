/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.linkpreview.TellomiExplicitBrowser.Outcome

/**
 * ADR-0063 §4.9 第 3 步的底层接入：显式指定浏览器打开，`intent:` / `javascript:` 这类 URL 不产生任何跳转（§8.1 第 7 行判据）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiExplicitBrowserTest {

  private lateinit var application: Application

  @Before
  fun setUp() {
    application = ApplicationProvider.getApplicationContext()
  }

  @Test
  fun `intent, javascript and other non-web URLs produce no navigation`() {
    val hostile = listOf(
      "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
      "intent:#Intent;action=android.intent.action.VIEW;S.browser_fallback_url=https%3A%2F%2Fexample.org;end",
      "INTENT://x#Intent;end",
      "javascript:alert(1)",
      "JavaScript:alert(document.cookie)",
      " javascript:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "file:///sdcard/Download/x.html",
      "content://com.example.provider/secret",
      "market://details?id=com.example",
      "https:///no-host",
      "tel:10086",
      ""
    )

    for (url in hostile) {
      assertEquals(url, Outcome.REJECTED, TellomiExplicitBrowser.open(application, url))
      assertNull(url, shadowOf(application).nextStartedActivity)
    }
    assertFalse((application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).hasPrimaryClip())
  }

  @Test
  fun `without a Custom Tabs browser the link goes through the CATEGORY_APP_BROWSER selector`() {
    assertEquals(Outcome.BROWSER, TellomiExplicitBrowser.open(application, "https://item.taobao.com/item.htm?id=1"))

    val started = shadowOf(application).nextStartedActivity
    assertEquals(Intent.ACTION_VIEW, started.action)
    assertEquals(Uri.parse("https://item.taobao.com/item.htm?id=1"), started.data)
    assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
    assertNull(started.`package`)
    val selector = started.selector
    assertNotNull(selector)
    assertEquals(Intent.ACTION_MAIN, selector!!.action)
    assertTrue(selector.hasCategory(Intent.CATEGORY_APP_BROWSER))
    assertNull(selector.selector)
  }

  @Test
  fun `a default browser with Custom Tabs gets the link pinned to its package`() {
    registerDefaultBrowser("com.example.browser", customTabs = true)

    assertEquals("com.example.browser", TellomiExplicitBrowser.customTabsBrowser(application.packageManager))
    assertEquals(Outcome.CUSTOM_TAB, TellomiExplicitBrowser.open(application, "https://mclient.alipay.com/pay?x=1"))

    val started = shadowOf(application).nextStartedActivity
    assertEquals(Intent.ACTION_VIEW, started.action)
    assertEquals("com.example.browser", started.`package`)
    assertEquals(Uri.parse("https://mclient.alipay.com/pay?x=1"), started.data)
    assertTrue(started.extras!!.containsKey("android.support.customtabs.extra.SESSION"))
    assertNull(started.selector)
  }

  @Test
  fun `a default browser without Custom Tabs falls back to the selector`() {
    registerDefaultBrowser("com.example.plain", customTabs = false)

    assertNull(TellomiExplicitBrowser.customTabsBrowser(application.packageManager))
    assertEquals(Outcome.BROWSER, TellomiExplicitBrowser.open(application, "https://example.org/"))
    assertNotNull(shadowOf(application).nextStartedActivity.selector)
  }

  @Test
  fun `when no browser can open the link it is copied instead`() {
    shadowOf(application).checkActivities(true)

    assertEquals(Outcome.COPIED, TellomiExplicitBrowser.open(application, "https://example.org/a#frag"))

    val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    assertEquals("https://example.org/a#frag", clipboard.primaryClip!!.getItemAt(0).text.toString())
  }

  @Test
  fun `web URLs are normalised to a lowercase scheme and keep everything else`() {
    assertEquals(Uri.parse("https://Example.org/A?b=C#d"), TellomiExplicitBrowser.targetUri("HTTPS://Example.org/A?b=C#d"))
    assertEquals(Uri.parse("http://example.org/"), TellomiExplicitBrowser.targetUri("http://example.org/"))
  }

  private fun registerDefaultBrowser(packageName: String, customTabs: Boolean) {
    val packageManager = shadowOf(application.packageManager)
    val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
    packageManager.addResolveInfoForIntent(
      view,
      ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
          this.packageName = packageName
          name = "$packageName.Main"
        }
      }
    )
    if (customTabs) {
      packageManager.addResolveInfoForIntent(
        Intent("android.support.customtabs.action.CustomTabsService").setPackage(packageName),
        ResolveInfo().apply {
          serviceInfo = ServiceInfo().apply {
            this.packageName = packageName
            name = "$packageName.CustomTabs"
          }
        }
      )
    }
  }
}
