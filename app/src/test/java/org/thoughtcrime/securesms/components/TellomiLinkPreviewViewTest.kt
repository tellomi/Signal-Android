/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestManager
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
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.TellomiFirstPartyCard
import org.thoughtcrime.securesms.linkpreview.TellomiLinkDisplay
import org.thoughtcrime.securesms.linkpreview.TellomiLinkVisual
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.Optional

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
  private val linkIcon get() = view.findViewById<View>(R.id.linkpreview_link_icon)
  private val action get() = view.findViewById<TextView>(R.id.linkpreview_action)
  private val divider get() = view.findViewById<View>(R.id.linkpreview_divider)
  private val avatar get() = view.findViewById<View>(R.id.linkpreview_first_party_avatar)
  private val container get() = view.findViewById<View>(R.id.linkpreview_container)
  private val thumbnail get() = view.findViewById<View>(R.id.linkpreview_thumbnail)

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

  @Test
  fun `a plain-link card shows the domain as the title and the link icon, nothing else`() {
    val titleColor = title.currentTextColor
    site.visibility = View.VISIBLE

    view.applyTellomiDisplay(TellomiLinkDisplay("163.com", null, null, false, plainLink = true, lookalike = false), true)

    assertEquals("163.com", title.text.toString())
    assertEquals(View.VISIBLE, title.visibility)
    assertEquals(View.GONE, description.visibility)
    assertEquals(View.GONE, site.visibility)
    assertEquals(View.VISIBLE, linkIcon.visibility)
    assertEquals(titleColor, title.currentTextColor)
  }

  @Test
  fun `a domain that imitates a well-known one is red`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("bi1ibili.com", null, null, false, plainLink = true, lookalike = true), true)

    assertEquals(ContextCompat.getColor(view.context, org.signal.core.ui.R.color.signal_colorError), title.currentTextColor)
  }

  @Test
  fun `a recycled view drops the plain-link look`() {
    val titleColor = title.currentTextColor
    view.applyTellomiDisplay(TellomiLinkDisplay("bi1ibili.com", null, null, false, plainLink = true, lookalike = true), true)

    view.applyTellomiDisplay(TellomiLinkDisplay("Taobao", null, "taobao.com", false), true)
    assertEquals(View.GONE, linkIcon.visibility)
    assertEquals(titleColor, title.currentTextColor)
    assertEquals(View.VISIBLE, site.visibility)

    view.applyTellomiDisplay(TellomiLinkDisplay("bi1ibili.com", null, null, false, plainLink = true, lookalike = true), true)
    view.applyTellomiDisplay(null, true)
    assertEquals(View.GONE, linkIcon.visibility)
    assertEquals(titleColor, title.currentTextColor)
  }

  @Test
  fun `a first-party card shows the title, one subtitle, no domain, and the action under a hairline`() {
    view.applyTellomiFirstParty(
      mockk<RequestManager>(relaxed = true),
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.GROUP, "周末爬山群", "12 members", "Join Group", false),
      null,
      false
    )

    assertEquals("周末爬山群", title.text.toString())
    assertEquals("12 members", description.text.toString())
    assertEquals(View.VISIBLE, description.visibility)
    assertEquals(View.GONE, site.visibility)
    assertEquals("Join Group", action.text.toString())
    assertEquals(View.VISIBLE, action.visibility)
    assertEquals(View.VISIBLE, divider.visibility)
    assertEquals(View.GONE, avatar.visibility)
  }

  @Test
  fun `a call card keeps Signal's own join button, so it has no action row`() {
    view.applyTellomiFirstParty(
      mockk<RequestManager>(relaxed = true),
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.CALL, "Tellomi call", null, "Join Call", false),
      null,
      false
    )

    assertEquals("Tellomi call", title.text.toString())
    assertEquals(View.GONE, description.visibility)
    assertEquals(View.GONE, action.visibility)
    assertEquals(View.GONE, divider.visibility)
  }

  @Test
  fun `the official card keeps its badge on the title`() {
    view.applyTellomiFirstParty(
      mockk<RequestManager>(relaxed = true),
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.OFFICIAL, "Tellomi website", "/download", "Open", true),
      null,
      false
    )

    val badge = view.context.getString(R.string.TellomiLinkCard__official_badge)
    assertEquals("Tellomi website  $badge", title.text.toString())
  }

  @Test
  fun `a recycled view drops the first-party look`() {
    val requestManager = mockk<RequestManager>(relaxed = true)
    view.applyTellomiFirstParty(
      requestManager,
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.STICKER, "Bandit", "24 stickers", "Add", false),
      null,
      false
    )

    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), true)

    assertEquals(View.GONE, action.visibility)
    assertEquals(View.GONE, divider.visibility)
    assertEquals(View.GONE, avatar.visibility)
    assertEquals("Sender title", title.text.toString())
  }

  private val orange = TellomiLinkVisual.Colors(Color.parseColor("#FE7500"), Color.parseColor("#000000"))

  /** Makes the image on the card visible without going through Glide's request chain, which a mock cannot answer. */
  private fun showImage() {
    view.applyTellomiFirstParty(
      mockk<RequestManager>(relaxed = true),
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.OFFICIAL, "Tellomi", null, "Open", false),
      null,
      false
    )
  }

  private fun params(id: Int) = view.findViewById<View>(id).layoutParams as ConstraintLayout.LayoutParams

  @Test
  fun `a tinted card is drawn in the colours of its image, and a recycled view drops them`() {
    val background = (container.background as ColorDrawable).color
    val titleColor = title.currentTextColor

    view.applyTellomiTint(orange)

    assertEquals(orange.background, (container.background as ColorDrawable).color)
    assertEquals(orange.text, title.currentTextColor)
    assertEquals(0xB3, Color.alpha(description.currentTextColor))
    assertEquals(orange.text and 0xFFFFFF, description.currentTextColor and 0xFFFFFF)

    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), true)
    assertEquals(background, (container.background as ColorDrawable).color)
    assertEquals(titleColor, title.currentTextColor)
  }

  @Test
  fun `no colours leave the card as it is`() {
    val background = (container.background as ColorDrawable).color
    view.applyTellomiTint(null)
    assertEquals(background, (container.background as ColorDrawable).color)
  }

  @Test
  fun `the icon card puts a 44 square on the right, the title and the domain on the left, and no description`() {
    showImage()
    view.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", null, "bilibili.com", false), true)

    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)

    val size = (44 * view.resources.displayMetrics.density).toInt()
    assertEquals(size, thumbnail.layoutParams.width)
    assertEquals(size, thumbnail.layoutParams.height)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).endToEnd)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).startToStart)
    assertEquals(R.id.linkpreview_thumbnail, params(R.id.linkpreview_title).endToStart)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_title).startToStart)
    assertEquals(R.id.linkpreview_title, params(R.id.linkpreview_site).topToBottom)
    assertEquals(View.GONE, description.visibility)
    assertEquals("bilibili.com", site.text.toString())
  }

  @Test
  fun `a card that is not an icon card keeps its image where it was`() {
    showImage()
    val startToStart = params(R.id.linkpreview_thumbnail).startToStart

    view.applyTellomiLayout(TellomiLinkVisual.Layout.LARGE_IMAGE)
    view.applyTellomiLayout(null)

    assertEquals(startToStart, params(R.id.linkpreview_thumbnail).startToStart)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).endToEnd)
  }

  @Test
  fun `a recycled view drops the icon layout`() {
    showImage()
    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).endToEnd)

    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), true)

    val size = (72 * view.resources.displayMetrics.density).toInt()
    assertEquals(size, thumbnail.layoutParams.width)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).startToStart)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).endToEnd)
    assertEquals(R.id.linkpreview_link_icon, params(R.id.linkpreview_title).endToStart)
    assertEquals(R.id.linkpreview_description, params(R.id.linkpreview_site).topToBottom)
    assertEquals(View.VISIBLE, description.visibility)
  }
}
