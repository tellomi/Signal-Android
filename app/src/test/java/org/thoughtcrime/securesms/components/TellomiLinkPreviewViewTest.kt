/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Application
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.emoji.EmojiDependencies
import org.signal.emoji.EmojiSource
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.linkpreview.TellomiLinkDisplay
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

/**
 * ADR-0063 §4.8 / §5.1 (tellomi/tellomi#1422): what [LinkPreviewView.applyTellomiDisplay] puts on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkPreviewViewTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class))

  private lateinit var view: LinkPreviewView

  private val title get() = view.findViewById<TextView>(R.id.linkpreview_title)
  private val description get() = view.findViewById<TextView>(R.id.linkpreview_description)
  private val site get() = view.findViewById<TextView>(R.id.linkpreview_site)

  @Before
  fun setUp() {
    // The layout's EmojiTextViews wait for the emoji library at construction; nothing installs it in unit
    // tests, so install the bundled one (same setup as TellomiBubbleTailMarginTest).
    every { signalStore.internal.forceBuiltInEmoji } returns true
    EmojiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      mockk(relaxed = true) { every { provideForceBuiltInEmoji() } returns true }
    )
    EmojiSource.refresh()

    val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Signal_DayNight)
    view = LinkPreviewView(context)
    title.text = "Sender title"
    description.text = "Sender description"
    description.visibility = View.VISIBLE
    site.text = "www.example.com"
  }

  @Test
  fun `the official card shows the fixed title with the badge, the path and the domain`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("Tellomi website", "/download", "tellomi.app", true), true)

    val badge = view.context.getString(R.string.TellomiLinkCard__official_badge)
    assertEquals("Tellomi website  $badge", title.text.toString())
    val spans = (title.text as Spanned).getSpans(0, title.text.length, ForegroundColorSpan::class.java)
    assertEquals(1, spans.size)
    assertEquals(title.text.length - badge.length, (title.text as Spanned).getSpanStart(spans[0]))
    assertEquals("/download", description.text.toString())
    assertEquals(View.VISIBLE, description.visibility)
    assertEquals("tellomi.app", site.text.toString())
  }

  @Test
  fun `a brand shell hides the sender's description`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("Taobao", null, "taobao.com", false), true)

    assertEquals("Taobao", title.text.toString())
    // No badge: EmojiTextView may add its own spans, but never the badge colour.
    assertTrue(title.text !is Spanned || (title.text as Spanned).getSpans(0, title.text.length, ForegroundColorSpan::class.java).isEmpty())
    assertEquals(View.GONE, description.visibility)
    assertEquals("taobao.com", site.text.toString())
  }

  @Test
  fun `a condensed bubble keeps the description hidden`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("《柯洁围棋入门课》", "柯洁 · 1:02:03", "bilibili.com", false), false)

    assertEquals("《柯洁围棋入门课》", title.text.toString())
    assertEquals(View.GONE, description.visibility)
  }

  @Test
  fun `no display leaves Signal's text alone`() {
    view.applyTellomiDisplay(null, true)

    assertEquals("Sender title", title.text.toString())
    assertEquals("Sender description", description.text.toString())
    assertEquals("www.example.com", site.text.toString())
  }
}
