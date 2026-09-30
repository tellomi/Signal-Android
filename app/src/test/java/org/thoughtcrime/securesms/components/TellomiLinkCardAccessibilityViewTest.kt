/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestManager
import io.mockk.every
import io.mockk.mockk
import okhttp3.internal.platform.PlatformRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependencies
import org.signal.emoji.EmojiDependencies
import org.signal.emoji.EmojiSource
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.TellomiFirstPartyCard
import org.thoughtcrime.securesms.linkpreview.TellomiLinkDisplay
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.Optional

/**
 * card-visual §3.6 (audit F1, F2): a link card in a bubble is one accessibility node that says the whole card in one sentence, and
 * the action of a Tellomi object's card is a button. What TalkBack is given: the node's description and class, and no children.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkCardAccessibilityViewTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class))

  /** A view only tells a screen reader about itself once it is attached to a window. */
  private lateinit var activity: AppCompatActivity
  private lateinit var view: LinkPreviewView
  private val requestManager = mockk<RequestManager>(relaxed = true)

  private val container get() = view.findViewById<View>(R.id.linkpreview_container)

  @Before
  fun setUp() {
    every { signalStore.internal.forceBuiltInEmoji } returns true
    EmojiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      mockk(relaxed = true) { every { provideForceBuiltInEmoji() } returns true }
    )
    EmojiSource.refresh()
    // The undecided card's domain line asks OkHttp for the registrable domain, whose suffix list it reads from the app's assets.
    PlatformRegistry.applicationContext = ApplicationProvider.getApplicationContext()

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
    activity = controller.setup().get()
    val root = FrameLayout(activity)
    activity.setContentView(root)
    view = LinkPreviewView(activity)
    root.addView(view)
  }

  /** What the framework hands an accessibility service for [target]. */
  private fun nodeInfo(target: View = view): AccessibilityNodeInfo {
    val info = AccessibilityNodeInfo.obtain()
    target.onInitializeAccessibilityNodeInfo(info)
    return info
  }

  private fun text(id: Int) = view.context.getString(id)

  // ---- a link to a website ----

  @Test
  fun `a card is read as link, title, domain, in one sentence`() {
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Ke Jie Go Course", "Ke Jie · 1:02:03", "bilibili.com", false), true)

    assertEquals("Link, Ke Jie Go Course, bilibili.com", nodeInfo().contentDescription.toString())
  }

  @Test
  fun `the publish date on the domain line is not read`() {
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Ke Jie Go Course", null, "bilibili.com ⋅ Sep 3", false), true)

    assertEquals("Link, Ke Jie Go Course, bilibili.com", nodeInfo().contentDescription.toString())
  }

  @Test
  fun `a plain-link card, and the domain-only card of a message request, are link and domain`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("163.com", null, null, false, plainLink = true), true)
    assertEquals("Link, 163.com", nodeInfo().contentDescription.toString())

    view.setDomainOnly("tell.cc", false)
    assertEquals("Link, tell.cc", nodeInfo().contentDescription.toString())
  }

  @Test
  fun `a card nothing decided on is read from the preview the way it shows`() {
    view.setLinkPreview(requestManager, LinkPreview("https://www.bilibili.com/video/BV1", "Sender title", "Sender description", 0, Optional.empty()), false)

    assertEquals("Link, Sender title, bilibili.com", nodeInfo().contentDescription.toString())
  }

  @Test
  fun `a card is one node, and its parts are not read again`() {
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Ke Jie Go Course", "Ke Jie · 1:02:03", "bilibili.com", false), true)

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, view.importantForAccessibility)
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, container.importantForAccessibility)
    val tree = view.createAccessibilityNodeInfo()
    assertEquals("no child of the card is offered to a screen reader", 0, tree.childCount)
  }

  @Test
  fun `a link to a website is not a button`() {
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Ke Jie Go Course", null, "bilibili.com", false), true)

    assertNotEquals(Button::class.java.name, nodeInfo().className.toString())
  }

  // ---- a Tellomi object ----

  private fun group() = TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.GROUP, "Book Club", "12 members", "Join Group", false)

  @Test
  fun `a group card is read as kind, name, members and button, and its action is a button`() {
    // The bubble makes the card clickable; that click is what activating the button does.
    view.setOnClickListener { }
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    view.applyTellomiFirstParty(requestManager, group(), null, false)

    val info = nodeInfo()
    assertEquals("Tellomi group, Book Club, 12 members, button: Join Group", info.contentDescription.toString())
    assertEquals(Button::class.java.name, info.className.toString())
    assertTrue("it can be activated", info.isClickable && info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, container.importantForAccessibility)
    assertEquals(0, view.createAccessibilityNodeInfo().childCount)
  }

  @Test
  fun `the other Tellomi objects read the same way`() {
    val cases = listOf(
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.USER, "@kaixin", "Tellomi user", "Message", false) to "Tellomi user, @kaixin, button: Message",
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.CALL, "Camping Prep", null, "Join Call", false) to "Tellomi call, Camping Prep, button: Join Call",
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.STICKER, "Bandit", "24 stickers", "Add", false) to "Tellomi sticker pack, Bandit, 24 stickers, button: Add",
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.OFFICIAL, "Tellomi website", "/download", "Open", true) to "Tellomi website, /download, button: Open"
    )
    for ((display, sentence) in cases) {
      view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
      view.applyTellomiFirstParty(requestManager, display, null, false)

      assertEquals(sentence, nodeInfo().contentDescription.toString())
      assertEquals(sentence, Button::class.java.name, nodeInfo().className.toString())
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `simplified chinese, with the real labels`() {
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("柯洁围棋入门课", null, "bilibili.com", false), true)
    assertEquals("链接，柯洁围棋入门课，bilibili.com", nodeInfo().contentDescription.toString())

    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    view.applyTellomiFirstParty(
      requestManager,
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.GROUP, "读书会", "12 位成员", text(R.string.TellomiLinkCard__action_join_group), false),
      null,
      false
    )
    assertEquals("Tellomi 群组，读书会，12 位成员，按钮：加入群聊", nodeInfo().contentDescription.toString())
  }

  @Test
  @Config(qualifiers = "zh-rTW")
  fun `traditional chinese, with the real labels`() {
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    view.applyTellomiFirstParty(
      requestManager,
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.USER, "@kaixin", text(R.string.TellomiLinkCard__tellomi_user), text(R.string.TellomiLinkCard__action_message), false),
      null,
      false
    )

    assertEquals("Tellomi 用戶，@kaixin，按鈕：傳送訊息", nodeInfo().contentDescription.toString())
  }

  // ---- a view that is reused, and the box for a message being written ----

  @Test
  fun `a recycled view drops the sentence and the button of the card before`() {
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    view.applyTellomiFirstParty(requestManager, group(), null, false)

    view.setLinkPreview(requestManager, LinkPreview("https://www.bilibili.com/video/BV1", "Sender title", "", 0, Optional.empty()), false)

    val info = nodeInfo()
    assertEquals("Link, Sender title, bilibili.com", info.contentDescription.toString())
    assertNotEquals(Button::class.java.name, info.className.toString())
  }

  @Test
  fun `the box for a message being written keeps its parts, and the close button stays reachable`() {
    val compose = LayoutInflater.from(activity).inflate(R.layout.conversation_input_link_preview_view, view.parent as FrameLayout, false) as LinkPreviewView
    (view.parent as FrameLayout).addView(compose)

    assertNull(nodeInfo(compose).contentDescription)
    assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, compose.findViewById<View>(R.id.linkpreview_container).importantForAccessibility)
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, compose.importantForAccessibility)
  }
}
