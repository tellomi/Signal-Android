/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.internal.platform.PlatformRegistry
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.BindableConversationItem
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.components.ConversationItemThumbnail
import org.thoughtcrime.securesms.components.LinkPreviewView
import org.thoughtcrime.securesms.conversation.colors.ColorizerV2
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.TellomiLinkOnly
import org.thoughtcrime.securesms.linkpreview.TellomiLinkRegistry
import org.thoughtcrime.securesms.linkpreview.TellomiLinkVisual
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.recipients.LiveRecipient
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.Locale
import java.util.Optional

/**
 * card-visual §3.6 (audit F1, F2): what TalkBack is given for a bubble that carries a link card. The real [ConversationItem] is
 * bound (the legacy renderer, which draws every message that has a preview) with the real rust/links and the registry that ships
 * with the app: the card is one node with the sentence, and a large image is not a second stop for the same card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class TellomiLinkCardAccessibilityItemTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class))

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  /** The bubble is on a real window: the image's transfer controls are Compose, which needs one. */
  private val activity: AppCompatActivity by lazy {
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.Signal_DayNight)
    controller.setup().get()
  }

  private val url = "https://www.163.com/news/article/K1234.html"
  private val group = "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  private val requestManager = stubbedGlide()
  private val listener = mockk<BindableConversationItem.EventListener>(relaxed = true)
  private val recipient = mockk<Recipient>(relaxed = true)

  /** A Glide whose requests all lead nowhere: a relaxed mock answers a chain of builder calls with the wrong type. */
  private fun stubbedGlide(): RequestManager {
    val builder = mockk<RequestBuilder<Drawable>>(relaxed = true)
    every { builder.diskCacheStrategy(any()) } returns builder
    every { builder.downsample(any()) } returns builder
    every { builder.transition(any()) } returns builder
    every { builder.override(any(), any()) } returns builder
    every { builder.apply(any()) } returns builder
    return mockk(relaxed = true) { every { load(any<Any>()) } returns builder }
  }

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  @Before
  fun setUp() {
    PlainTextKeyValueStore.init(ApplicationProvider.getApplicationContext())
    PlatformRegistry.applicationContext = ApplicationProvider.getApplicationContext()
    every { signalStore.internal.forceBuiltInEmoji } returns true
    EmojiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      mockk(relaxed = true) { every { provideForceBuiltInEmoji() } returns true }
    )
    EmojiSource.refresh()
    CoreUiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      object : CoreUiDependencies.Provider {
        override fun providePackageId() = "test"
        override fun provideIsIncognitoKeyboardEnabled() = false
        override fun provideIsScreenSecurityEnabled() = false
      }
    )

    val live = mockk<LiveRecipient>(relaxed = true)
    every { recipient.resolve() } returns recipient
    every { recipient.live() } returns live
    every { live.get() } returns recipient

    val golden = Json.parseToJsonElement(String(resource("classify-golden.json"))).jsonObject
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(resource(golden["registry"]!!.jsonPrimitive.content)))
  }

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
  }

  /** A downloaded image of [width] × [height]. */
  private fun image(width: Int, height: Int, id: Int = 7) = UriAttachment(
    dataUri = Uri.parse("content://$id"),
    contentType = "image/jpeg",
    transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
    size = 1000,
    width = width,
    height = height,
    fileName = "preview.jpg",
    fastPreflightId = null,
    voiceNote = false,
    borderless = false,
    videoGif = false,
    quote = false,
    quoteTargetContentType = null,
    caption = null,
    stickerLocator = null,
    blurHash = null,
    audioHash = null,
    transformProperties = null
  )

  private fun preview(link: String, title: String, image: UriAttachment? = null) = LinkPreview(link, title, "Sender description", 0, Optional.ofNullable(image))

  private fun newItem(): ConversationItem {
    val root = FrameLayout(activity)
    activity.setContentView(root)
    val item = LayoutInflater.from(activity).inflate(R.layout.conversation_item_received_multimedia, root, false) as ConversationItem
    root.addView(item)
    ConversationItem::class.java.getDeclaredField("contactPhoto").apply { isAccessible = true }.set(item, null)
    item.setEventListener(listener)
    return item
  }

  /** Binds [record] in an accepted conversation, the way the adapter does: the data layer decides, the view draws what it is given. */
  private fun bind(record: MmsMessageRecord, visual: TellomiLinkVisual.Visual = TellomiLinkVisual.Visual.NONE, item: ConversationItem = newItem()): ConversationItem {
    val properties = ConversationMessage.ComputedProperties(mockk(relaxed = true), TellomiLinkOnly.decide(record, false), null, visual, null)
    val message = mockk<ConversationMessage>(relaxed = true) {
      every { messageRecord } returns record
      every { computedProperties } returns properties
      every { getDisplayBody(any()) } answers { SpannableString(record.body) }
      every { threadRecipient } returns recipient
      every { deletedByRecipient } returns null
      every { bottomButton } returns null
      every { mentions } returns emptyList()
    }
    val owner = object : LifecycleOwner {
      val registry = LifecycleRegistry(this).also { it.currentState = Lifecycle.State.RESUMED }
      override val lifecycle: Lifecycle get() = registry
    }
    item.bind(owner, message, Optional.empty(), Optional.empty(), requestManager, Locale.US, emptySet(), recipient, null, false, false, true, false, ColorizerV2(), ConversationItemDisplayMode.Standard)
    return item
  }

  private val ConversationItem.card: LinkPreviewView get() = findViewById(R.id.link_preview)
  private val ConversationItem.thumbnail: ConversationItemThumbnail get() = findViewById(R.id.image_view)

  private fun sentence(card: View): String {
    val info = AccessibilityNodeInfo.obtain()
    card.onInitializeAccessibilityNodeInfo(info)
    return info.contentDescription.toString()
  }

  @Test
  fun `a link card in a bubble says link, title, domain, through the real binding`() {
    val item = bind(FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview(url, "网易新闻"))))

    assertEquals("Link, 网易新闻, 163.com", sentence(item.card))
  }

  @Test
  fun `a group invite card says kind, name, members and the button`() {
    val item = bind(FakeMessageRecords.buildMediaMmsMessageRecord(body = group, linkPreviews = listOf(preview(group, "周末爬山群"))))

    assertEquals("Tellomi group, 周末爬山群, button: Join Group", sentence(item.card))
  }

  @Test
  fun `the image of a large-image card is not a second stop for the same card`() {
    val record = FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview(url, "网易新闻", image(1200, 630))))

    val item = bind(record, TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.LARGE_IMAGE, null))

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, item.thumbnail.importantForAccessibility)
    assertEquals("Link, 网易新闻, 163.com", sentence(item.card))
  }

  @Test
  fun `a photo is offered to a screen reader, also on the view that drew a large-image card`() {
    val photo = FakeMessageRecords.buildMediaMmsMessageRecord(body = "", slideDeck = SlideDeck().apply { addSlide(ImageSlide(image(1000, 200, id = 8))) })
    assertEquals("on a fresh view", View.IMPORTANT_FOR_ACCESSIBILITY_YES, bind(photo).thumbnail.importantForAccessibility)

    val item = newItem()
    bind(FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview(url, "网易新闻", image(1200, 630)))), TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.LARGE_IMAGE, null), item)
    bind(photo, TellomiLinkVisual.Visual.NONE, item)

    assertEquals("on a recycled one", View.IMPORTANT_FOR_ACCESSIBILITY_YES, item.thumbnail.importantForAccessibility)
  }
}
