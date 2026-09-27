/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.testutil.SignalStoreRule

/**
 * ADR-0063 §8.1 第 9 行（owner 2026-09-27）：「展开短链接」默认开、只存本机、不跨设备同步；
 * 「生成链接预览」的说明写清发送端会访问网站。四种语言的文案钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkValuesTest {

  @get:Rule
  val signalStore = SignalStoreRule()

  @Test
  fun `expand short links is on by default and can be switched off`() {
    assertTrue(SignalStore.tellomiLinks.expandShortLinks)

    SignalStore.tellomiLinks.expandShortLinks = false

    assertFalse(SignalStore.tellomiLinks.expandShortLinks)
  }

  @Test
  fun `expand short links stays on this device`() {
    assertFalse(SignalStore.keysToIncludeInBackup.contains(TellomiLinkValues.EXPAND_SHORT_LINKS))
    assertTrue(SignalStore.tellomiLinks.keysToIncludeInBackup.isEmpty())
  }

  @Test
  fun `english wording`() {
    assertWording(
      "When on, your device visits the website when you send a link.",
      "Expand short links",
      "Link copied"
    )
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `simplified chinese wording`() {
    assertWording("开启后，发送链接时你的设备会访问该网站。", "展开短链接", "已复制链接")
  }

  @Test
  @Config(qualifiers = "zh-rHK")
  fun `hong kong wording`() {
    assertWording("開啟後，傳送連結時你的裝置會存取該網站。", "展開短連結", "已複製連結")
  }

  @Test
  @Config(qualifiers = "zh-rTW")
  fun `taiwan wording`() {
    assertWording("開啟後，傳送連結時你的裝置會存取該網站。", "展開短連結", "已複製連結")
  }

  private fun assertWording(previewDescription: String, expandShortLinks: String, linkCopied: String) {
    val context = ApplicationProvider.getApplicationContext<Application>()
    assertEquals(previewDescription, context.getString(R.string.TellomiLinks__generate_link_previews_description))
    assertEquals(expandShortLinks, context.getString(R.string.TellomiLinks__expand_short_links))
    assertEquals(linkCopied, context.getString(R.string.TellomiLinks__link_copied))
  }
}
