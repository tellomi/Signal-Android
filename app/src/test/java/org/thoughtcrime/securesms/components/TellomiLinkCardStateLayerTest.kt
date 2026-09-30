/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependencies
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
 * card-visual §3.6 / §3.8 (audit C5): a pressed or focused card, tinted or neutral, gets a translucent black or white layer over it
 * (the card's own text colour, at Material's 12%), and never another background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiLinkCardStateLayerTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class))

  private lateinit var view: LinkPreviewView
  private val requestManager = mockk<RequestManager>(relaxed = true)

  private val container get() = view.findViewById<View>(R.id.linkpreview_container)
  private val title get() = view.findViewById<TextView>(R.id.linkpreview_title)
  private val containerColor get() = (container.background as ColorDrawable).color

  private val orange = TellomiLinkVisual.Colors(Color.parseColor("#FE7500"), Color.BLACK)
  private val darkOrange = TellomiLinkVisual.Colors(Color.parseColor("#994600"), Color.WHITE)

  /** 12% of 255, rounded: Material 3's pressed and focused state layers. */
  private val twelvePercent = 0x1F

  @Before
  fun setUp() {
    every { signalStore.internal.forceBuiltInEmoji } returns true
    EmojiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      mockk(relaxed = true) { every { provideForceBuiltInEmoji() } returns true }
    )
    EmojiSource.refresh()
    view = LinkPreviewView(ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Signal_DayNight))
    view.setOnClickListener { }
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
  }

  private fun layer(color: Int) = Color.argb(twelvePercent, Color.red(color), Color.green(color), Color.blue(color))

  /** The colour the foreground is drawn in while the view is in [state]. */
  private fun layerWhile(state: IntArray): Int {
    val foreground = view.foreground
    assertNotNull("the card has a foreground", foreground)
    foreground.state = state
    return (foreground.current as ColorDrawable).color
  }

  private val resting = intArrayOf()
  private val pressed = intArrayOf(android.R.attr.state_pressed)
  private val focused = intArrayOf(android.R.attr.state_focused)

  @Test
  fun `a neutral card is clear at rest, and dimmed by its own text colour when pressed or focused`() {
    val text = title.currentTextColor

    assertEquals("clear at rest", Color.TRANSPARENT, layerWhile(resting))
    assertEquals(layer(text), layerWhile(pressed))
    assertEquals(layer(text), layerWhile(focused))
  }

  @Test
  fun `the view's own pressed state gets there`() {
    val text = title.currentTextColor

    view.isPressed = true
    assertEquals(layer(text), (view.foreground.current as ColorDrawable).color)

    view.isPressed = false
    assertEquals(Color.TRANSPARENT, (view.foreground.current as ColorDrawable).color)
  }

  @Test
  fun `a tinted card is darkened by black on a light colour, and lightened by white on a dark one`() {
    view.applyTellomiTint(orange)
    assertEquals(layer(Color.BLACK), layerWhile(pressed))
    assertEquals(layer(Color.BLACK), layerWhile(focused))

    view.applyTellomiTint(darkOrange)
    assertEquals(layer(Color.WHITE), layerWhile(pressed))
    assertEquals(layer(Color.WHITE), layerWhile(focused))
  }

  @Test
  fun `pressing or focusing a tinted card never changes its background`() {
    view.applyTellomiTint(orange)
    val text = title.currentTextColor

    view.isPressed = true
    assertEquals(orange.background, containerColor)
    assertEquals(text, title.currentTextColor)

    view.isPressed = false
    view.isPressed = true
    assertEquals(orange.background, containerColor)
  }

  @Test
  fun `a recycled view goes back to the neutral layer`() {
    val neutral = title.currentTextColor
    view.applyTellomiTint(darkOrange)

    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)

    assertEquals(layer(neutral), layerWhile(pressed))
  }

  @Test
  fun `a first-party card has the neutral layer, action row and all`() {
    val neutral = title.currentTextColor
    view.applyTellomiFirstParty(
      requestManager,
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.GROUP, "周末爬山群", "12 members", "Join Group", false),
      null,
      false
    )

    assertEquals(layer(neutral), layerWhile(pressed))
  }

  @Test
  fun `the domain-only card of a message request has no layer, as it does not open anything`() {
    view.setDomainOnly("163.com", false)
    assertNull(view.foreground)

    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    assertNotNull("the next card on this view has one again", view.foreground)
  }

  @Test
  fun `the box for a message being written has none`() {
    val compose = LayoutInflater.from(view.context).inflate(R.layout.conversation_input_link_preview_view, null) as LinkPreviewView

    assertNull(compose.foreground)
    compose.applyTellomiTint(orange)
    assertNull(compose.foreground)
  }

  // ---- drawn: the layer stops at the bubble's corners ----

  private fun cardInWindow(): LinkPreviewView {
    CoreUiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      object : CoreUiDependencies.Provider {
        override fun providePackageId() = "test"
        override fun provideIsIncognitoKeyboardEnabled() = false
        override fun provideIsScreenSecurityEnabled() = false
      }
    )
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.Signal_DayNight)
    val activity = controller.setup().get()
    val card = LinkPreviewView(activity)
    activity.setContentView(card)
    return card
  }

  private fun draw(card: LinkPreviewView, pressed: Boolean): Bitmap {
    card.isPressed = pressed
    card.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    card.layout(0, 0, card.measuredWidth, card.measuredHeight)
    val bitmap = Bitmap.createBitmap(card.measuredWidth, card.measuredHeight, Bitmap.Config.ARGB_8888)
    card.draw(Canvas(bitmap))
    return bitmap
  }

  @Test
  fun `a pressed card is drawn darker in the middle, and its rounded corners stay clear`() {
    val card = cardInWindow()
    card.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    card.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", null, "bilibili.com", false), true)
    card.applyTellomiTint(orange)
    card.setCorners(40, 40)
    card.setBottomCorners(40, 40)

    val rest = draw(card, pressed = false)
    val down = draw(card, pressed = true)
    val middle = rest.width / 2 to rest.height - 4

    assertEquals("the tint, before it is pressed", orange.background, rest.getPixel(middle.first, middle.second))
    val darker = down.getPixel(middle.first, middle.second)
    assertNotEquals("the tint, pressed", orange.background, darker)
    assertTrue("black over orange: every channel is lower", Color.red(darker) < Color.red(orange.background) && Color.green(darker) < Color.green(orange.background))
    assertEquals("the top-left corner is cut off, pressed or not", 0, Color.alpha(rest.getPixel(0, 0)))
    assertEquals("and stays cut off when pressed", 0, Color.alpha(down.getPixel(0, 0)))
    assertEquals("the bottom-right corner too", 0, Color.alpha(down.getPixel(down.width - 1, down.height - 1)))
  }
}
