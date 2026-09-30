/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.internal.platform.PlatformRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import org.signal.libsignal.links.LinkRegistry
import org.signal.ringrtc.CallLinkRootKey
import org.thoughtcrime.securesms.BindableConversationItem
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.calls.links.CallLinks
import org.thoughtcrime.securesms.components.ConversationItemThumbnail
import org.thoughtcrime.securesms.components.LinkPreviewView
import org.thoughtcrime.securesms.conversation.colors.ColorizerV2
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.TellomiBrandIcons
import org.thoughtcrime.securesms.linkpreview.TellomiFirstPartyCard
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCard
import org.thoughtcrime.securesms.linkpreview.TellomiLinkOnly
import org.thoughtcrime.securesms.linkpreview.TellomiLinkRegistry
import org.thoughtcrime.securesms.linkpreview.TellomiLinkVisual
import org.thoughtcrime.securesms.recipients.LiveRecipient
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.whispersystems.signalservice.internal.push.Attr
import org.whispersystems.signalservice.internal.push.RichContent
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.Optional

/**
 * card-visual §3.11 (ADR-0063 §8.1 row 6): the acceptance pictures of the link cards in a message bubble. Every row of §3.7's
 * table, each with all its fields and with only the required ones, in the light theme and in the dark one. The real
 * [ConversationItem] is bound (the legacy renderer, which draws every message that has a preview), with the real rust/links and
 * the registry that ships with the app: what a card is (level, kind), its shape and its colours are what `classify`, `layout` and
 * `tint` answer for the picture it is given; the test hands them pictures it draws itself and the icons that ship with the app.
 * Nothing is fetched.
 *
 * The test asserts what each card should be, and writes one PNG per card and one contact sheet per theme to
 * `app/build/tellomi-cards/<light|dark>/`. The rows of a sheet are kinds and its two columns are "all fields" and "required only":
 * `contact-<theme>.png` is the whole table and `rows/<theme>-<kind>.png` one row of it. So the next change to a layout can be
 * looked at by running it again:
 *
 * ```
 * ./gradlew --offline :Signal-Android:testPlayProdDebugUnitTest --tests 'org.thoughtcrime.securesms.conversation.TellomiLinkCardShotsTest'
 * ```
 *
 * `TELLOMI_CARDS_DIR` moves the output.
 *
 * The width of the bubble. The card is `match_parent` in a bubble that is `wrap_content`, and a `LinearLayout` counts only the
 * margins of a `match_parent` child when it works out its own width. So a bubble with nothing but a card in it (the text is the
 * link and is not shown, card-visual §3.5) is as wide as its widest `wrap_content` child: the large image of a large-image card,
 * otherwise only the footer. The card's title and lines are `0dp` and do not widen it either, so the title wraps into a column.
 * That is how the layout is today; here every bubble is given the width of a large-image card ([BUBBLE_DP]) so that the cards
 * are seen as designed, and `TELLOMI_CARDS_BUBBLE=natural` leaves the bubble as the layout makes it (a number is a width in dp).
 *
 * Two things stand in for what a unit test cannot run: RingRTC's parsing of a call link's key (its native library is not loadable
 * on the host; only the colour of the avatar depends on the key), and the pictures, which Glide would have put on the views.
 * Skipped where the native rust/links library is not available.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiLinkCardShotsTest {

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

  private val requestManager = stubbedGlide()
  private val listener = mockk<BindableConversationItem.EventListener>(relaxed = true)
  private val recipient = mockk<Recipient>(relaxed = true)

  /** The icons that ship with the app, read from its assets. */
  private val icons by lazy { TellomiBrandIcons { path -> ApplicationProvider.getApplicationContext<Application>().assets.open(path) } }

  private val density get() = activity.resources.displayMetrics.density
  private fun dp(value: Int) = (value * density).toInt()

  // ---------------------------------------------------------------------------------------------------------------------------
  // The table: §3.7, row by row
  // ---------------------------------------------------------------------------------------------------------------------------

  /** What rust/links and the layout are expected to make of a shot. */
  private class Expect(val level: TellomiLinkCard.Level, val kind: String? = null, val layout: TellomiLinkVisual.Layout?, val tinted: Boolean = false)

  /** A picture the test draws: [top] to [bottom] is the gradient (the bottom tenth is what a large-image card is tinted by). */
  private class Artwork(val width: Int, val height: Int, val top: Int, val bottom: Int, val label: String)

  private class Shot(
    val url: String,
    val title: String = "",
    val image: Artwork? = null,
    val rich: ByteArray? = null,
    /** A link sent without a preview: the card is drawn from the link alone (§3.5). */
    val noPreview: Boolean = false,
    val local: TellomiFirstPartyCard.Local? = null,
    /** The icon the registry names for a brand shell: the pinned library does not report it yet (card-visual §3.9). */
    val icon: String? = null,
    val expect: Expect
  )

  private class Row(val id: String, val label: String, val full: Shot, val required: Shot)

  private fun rich(kind: String, provider: String, vararg attrs: Pair<String, String>, level: Int? = null): ByteArray {
    return RichContent(kind = kind, provider = provider, schema = 1, attrs = attrs.map { Attr(key = it.first, value_ = it.second) }, level = level).encode()
  }

  private val news = "https://www.163.com/news/article/K1234.html"
  private val blue = Color.parseColor("#3B6FD8")
  private val teal = Color.parseColor("#0E8C9E")
  private val orange = Color.parseColor("#FE7500")
  private val violet = Color.parseColor("#7B4BC9")
  private val charcoal = Color.parseColor("#2B2F36")
  private val green = Color.parseColor("#2E9E5B")

  private val rows: List<Row> by lazy {
    listOf(
      Row(
        "plain-link",
        "纯链接 · plain link",
        full = Shot(news, expect = Expect(TellomiLinkCard.Level.PLAIN_LINK, layout = TellomiLinkVisual.Layout.NO_IMAGE)),
        required = Shot(news, noPreview = true, expect = Expect(TellomiLinkCard.Level.PLAIN_LINK, layout = TellomiLinkVisual.Layout.NO_IMAGE))
      ),
      Row(
        "generic",
        "generic",
        full = Shot(news, "网易新闻：今天的头条", Artwork(1200, 630, blue, orange, "1200 × 630"), expect = Expect(TellomiLinkCard.Level.GENERIC, layout = TellomiLinkVisual.Layout.LARGE_IMAGE, tinted = true)),
        required = Shot(news, "网易新闻：今天的头条", expect = Expect(TellomiLinkCard.Level.GENERIC, layout = TellomiLinkVisual.Layout.NO_IMAGE))
      ),
      Row(
        "brand-icon",
        "品牌壳 · 有随包图标",
        full = Shot(
          "https://item.taobao.com/item.htm?id=100032608854",
          "淘宝",
          rich = rich("product", "taobao"),
          icon = "taobao.png",
          expect = Expect(TellomiLinkCard.Level.BRAND, "product", TellomiLinkVisual.Layout.ICON, tinted = true)
        ),
        required = Shot(
          "https://www.taobao.com/",
          "淘宝",
          rich = rich("web", "taobao"),
          icon = "taobao.png",
          expect = Expect(TellomiLinkCard.Level.BRAND, "web", TellomiLinkVisual.Layout.ICON, tinted = true)
        )
      ),
      Row(
        "brand-no-icon",
        "品牌壳 · 无图标",
        full = Shot(
          "https://item.jd.com/100012043978.html",
          "京东",
          rich = rich("product", "jd"),
          expect = Expect(TellomiLinkCard.Level.BRAND, "product", TellomiLinkVisual.Layout.NO_IMAGE)
        ),
        required = Shot(
          "https://www.jd.com/",
          "京东",
          rich = rich("web", "jd"),
          expect = Expect(TellomiLinkCard.Level.BRAND, "web", TellomiLinkVisual.Layout.NO_IMAGE)
        )
      ),
      Row(
        "brand-payment",
        "品牌壳 · 支付（不染色）",
        full = Shot(
          "https://render.alipay.com/p/f/fd-j5rqp49m/index.html",
          "支付宝",
          rich = rich("web", "alipay", level = 1),
          expect = Expect(TellomiLinkCard.Level.BRAND, "web", TellomiLinkVisual.Layout.NO_IMAGE)
        ),
        required = Shot(
          "https://render.alipay.com/p/f/fd-j5rqp49m/index.html",
          "支付宝",
          rich = rich("web", "alipay"),
          expect = Expect(TellomiLinkCard.Level.BRAND, "web", TellomiLinkVisual.Layout.NO_IMAGE)
        )
      ),
      Row(
        "video",
        "video",
        full = Shot(
          "https://www.bilibili.com/video/BV1YDhJ6ZEL6",
          "《柯洁围棋入门课》",
          Artwork(1200, 630, teal, teal, "1200 × 630"),
          rich = rich("video", "bilibili", "author" to "柯洁", "duration_ms" to "3723000", "published_at" to "2026-09-03T08:00:00+08:00", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "video", TellomiLinkVisual.Layout.LARGE_IMAGE, tinted = true)
        ),
        required = Shot(
          "https://www.bilibili.com/video/BV1YDhJ6ZEL6",
          "《柯洁围棋入门课》",
          Artwork(400, 400, teal, teal, "400"),
          rich = rich("video", "bilibili", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "video", TellomiLinkVisual.Layout.ICON, tinted = true)
        )
      ),
      Row(
        "channel",
        "channel",
        full = Shot(
          "https://www.youtube.com/@YouTube",
          "YouTube",
          Artwork(400, 400, orange, orange, "400"),
          rich = rich("channel", "youtube", "author" to "YouTube", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "channel", TellomiLinkVisual.Layout.ICON, tinted = true)
        ),
        required = Shot(
          "https://www.youtube.com/@YouTube",
          "YouTube",
          rich = rich("channel", "youtube", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "channel", TellomiLinkVisual.Layout.NO_IMAGE)
        )
      ),
      Row(
        "music-track",
        "music.track",
        full = Shot(
          "https://music.163.com/song?id=3342319503",
          "晴天",
          Artwork(640, 640, violet, violet, "640 × 640"),
          rich = rich("music.track", "netease-music", "artist" to "周杰伦", "album" to "叶惠美", "duration_ms" to "269000", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "music.track", TellomiLinkVisual.Layout.LARGE_IMAGE, tinted = true)
        ),
        required = Shot(
          "https://music.163.com/song?id=3342319503",
          "晴天",
          rich = rich("music.track", "netease-music", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "music.track", TellomiLinkVisual.Layout.NO_IMAGE)
        )
      ),
      Row(
        "music-album",
        "music.album",
        full = Shot(
          "https://open.spotify.com/album/2Xoteh7uEpea4TohMxjtaq",
          "Abbey Road",
          Artwork(640, 640, green, green, "640 × 640"),
          rich = rich("music.album", "spotify", "artist" to "The Beatles", "track_count" to "17", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "music.album", TellomiLinkVisual.Layout.LARGE_IMAGE, tinted = true)
        ),
        required = Shot(
          "https://open.spotify.com/album/2Xoteh7uEpea4TohMxjtaq",
          "Abbey Road",
          rich = rich("music.album", "spotify", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "music.album", TellomiLinkVisual.Layout.NO_IMAGE)
        )
      ),
      Row(
        "music-playlist",
        "music.playlist",
        full = Shot(
          "https://music.163.com/playlist?id=3778678",
          "云音乐热歌榜",
          Artwork(400, 400, orange, orange, "400"),
          rich = rich("music.playlist", "netease-music", "author" to "网易云音乐", "track_count" to "200", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "music.playlist", TellomiLinkVisual.Layout.ICON, tinted = true)
        ),
        required = Shot(
          "https://music.163.com/playlist?id=3778678",
          "云音乐热歌榜",
          rich = rich("music.playlist", "netease-music", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "music.playlist", TellomiLinkVisual.Layout.NO_IMAGE)
        )
      ),
      Row(
        "place",
        "place",
        full = Shot(
          "https://uri.amap.com/marker?position=116.47,39.99",
          "外滩",
          rich = rich("place", "amap", "name" to "外滩", "address" to "上海市黄浦区中山东一路", "lat" to "39.99", "lng" to "116.47", "coord_sys" to "gcj02", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "place", TellomiLinkVisual.Layout.NO_IMAGE)
        ),
        required = Shot(
          "https://uri.amap.com/marker?position=116.47,39.99",
          "高德地图",
          rich = rich("place", "amap", "lat" to "39.99", "lng" to "116.47", "coord_sys" to "gcj02", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "place", TellomiLinkVisual.Layout.NO_IMAGE)
        )
      ),
      Row(
        "app",
        "app",
        full = Shot(
          "https://apps.apple.com/cn/app/wechat/id414478124",
          "微信",
          Artwork(512, 512, green, green, "512"),
          rich = rich("app", "app-store", "developer" to "Tencent Technology", "platform" to "ios", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "app", TellomiLinkVisual.Layout.ICON, tinted = true)
        ),
        required = Shot(
          "https://apps.apple.com/cn/app/wechat/id414478124",
          "微信",
          Artwork(512, 512, green, green, "512"),
          rich = rich("app", "app-store", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "app", TellomiLinkVisual.Layout.ICON, tinted = true)
        )
      ),
      Row(
        "repo",
        "repo",
        full = Shot(
          "https://github.com/signalapp/Signal-Android",
          "signalapp/Signal-Android",
          Artwork(1200, 630, charcoal, charcoal, "1200 × 630"),
          rich = rich("repo", "github", "owner" to "signalapp", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "repo", TellomiLinkVisual.Layout.LARGE_IMAGE)
        ),
        required = Shot(
          "https://github.com/signalapp/Signal-Android",
          "signalapp/Signal-Android",
          rich = rich("repo", "github", level = 2),
          expect = Expect(TellomiLinkCard.Level.STRUCTURED, "repo", TellomiLinkVisual.Layout.NO_IMAGE)
        )
      ),
      Row(
        "tellomi-user",
        "第一方 · user",
        full = Shot(
          "https://tell.cc/kaixin.57",
          "@kaixin",
          local = TellomiFirstPartyCard.Local(knownUserName = "凯欣"),
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.user", TellomiLinkVisual.Layout.FIRST_PARTY)
        ),
        required = Shot(
          "https://tell.cc/u#eu/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          "@kefu",
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.user", TellomiLinkVisual.Layout.FIRST_PARTY)
        )
      ),
      Row(
        "tellomi-group",
        "第一方 · group",
        full = Shot(
          "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          "周末爬山群",
          Artwork(512, 512, blue, blue, "512"),
          rich = rich("tellomi.group", "tellomi", "member_count" to "12", level = 2),
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.group", TellomiLinkVisual.Layout.FIRST_PARTY)
        ),
        required = Shot(
          "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          "周末爬山群",
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.group", TellomiLinkVisual.Layout.FIRST_PARTY)
        )
      ),
      Row(
        "tellomi-call",
        "第一方 · call",
        full = Shot(
          "https://tell.cc/call/#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz",
          "露营筹备",
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.call", TellomiLinkVisual.Layout.FIRST_PARTY)
        ),
        required = Shot(
          "https://tell.cc/call/#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz",
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.call", TellomiLinkVisual.Layout.FIRST_PARTY)
        )
      ),
      Row(
        "tellomi-sticker",
        "第一方 · sticker",
        full = Shot(
          "https://tell.cc/s#pack_id=00000000000000000000000000000000&pack_key=0000000000000000000000000000000000000000000000000000000000000000",
          "猫猫",
          Artwork(512, 512, orange, orange, "512"),
          rich = rich("tellomi.sticker", "tellomi", "sticker_count" to "24", level = 2),
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.sticker", TellomiLinkVisual.Layout.FIRST_PARTY)
        ),
        required = Shot(
          "https://tell.cc/s#pack_id=00000000000000000000000000000000&pack_key=0000000000000000000000000000000000000000000000000000000000000000",
          "猫猫",
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.sticker", TellomiLinkVisual.Layout.FIRST_PARTY)
        )
      ),
      Row(
        "tellomi-official",
        "第一方 · official",
        full = Shot(
          "https://tellomi.app/download/",
          "Tellomi 官网",
          rich = rich("tellomi.official", "tellomi", level = 2),
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.official", TellomiLinkVisual.Layout.FIRST_PARTY)
        ),
        required = Shot(
          "https://tellomi.app/",
          "Tellomi 官网",
          expect = Expect(TellomiLinkCard.Level.FIRST_PARTY, "tellomi.official", TellomiLinkVisual.Layout.FIRST_PARTY)
        )
      ),
      Row(
        "unknown-kind",
        "不认识的 kind",
        full = Shot(
          "https://www.bilibili.com/video/BV1YDhJ6ZEL6",
          "《柯洁围棋入门课》",
          Artwork(1200, 630, teal, teal, "1200 × 630"),
          rich = rich("hologram", "bilibili", "author" to "柯洁", level = 2),
          expect = Expect(TellomiLinkCard.Level.GENERIC, layout = TellomiLinkVisual.Layout.LARGE_IMAGE, tinted = true)
        ),
        required = Shot(
          "https://www.bilibili.com/video/BV1YDhJ6ZEL6",
          "《柯洁围棋入门课》",
          rich = rich("hologram", "bilibili", level = 2),
          expect = Expect(TellomiLinkCard.Level.GENERIC, layout = TellomiLinkVisual.Layout.NO_IMAGE)
        )
      )
    )
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // Setup
  // ---------------------------------------------------------------------------------------------------------------------------

  /** A Glide whose requests all lead nowhere: the pictures are put on the views by hand below. */
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
    unmockkStatic(CallLinks::class)
    TellomiLinkRegistry.setForTesting(null)
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // The test
  // ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun `light theme`() {
    every { signalStore.settings.theme } returns SettingsValues.Theme.LIGHT
    renderAll("light")
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp-night-xhdpi")
  fun `dark theme`() {
    // The app's own setting decides which colours a tinted card takes (DynamicTheme.isDarkTheme); the qualifier draws the rest dark.
    every { signalStore.settings.theme } returns SettingsValues.Theme.DARK
    renderAll("dark")
  }

  private class Rendered(val bitmap: Bitmap, val caption: String)

  private fun renderAll(theme: String) {
    assumeTrue("the native rust/links library is needed for layout and tint", nativeAvailable())

    // RingRTC's native library is not loadable in a unit test, and a call link's key is parsed with it. Nothing on the card depends
    // on the key but the colour of the avatar, so only the parsing is stood in for; the card and the join button are the real ones.
    mockkStatic(CallLinks::class)
    every { CallLinks.parseUrl(any()) } returns mockk<CallLinkRootKey>(relaxed = true) { every { keyBytes } returns ByteArray(16) { (it * 7).toByte() } }

    val dir = File(System.getenv("TELLOMI_CARDS_DIR") ?: "build/tellomi-cards", theme).apply { mkdirs() }
    val rendered = rows.map { row ->
      row to listOf("full" to row.full, "required" to row.required).map { (variant, shot) ->
        val card = render(shot, "${row.id}/$variant")
        File(dir, "${row.id}-$variant.png").outputStream().use { card.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        card
      }
    }

    val sheet = contactSheet(theme, rendered)
    File(dir.parentFile, "contact-$theme.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    assertTrue(sheet.width > 0 && sheet.height > 0)

    // The same sheet one row at a time, for looking at a single kind without the 7,500-pixel-high one.
    val strips = File(dir.parentFile, "rows").apply { mkdirs() }
    for (entry in rendered) {
      File(strips, "$theme-${entry.first.id}.png").outputStream().use { contactSheet(theme, listOf(entry)).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }

  private fun nativeAvailable(): Boolean {
    return try {
      TellomiLinkVisual.Native.layout(1200, 630, "", "generic") == "large_image"
    } catch (e: Throwable) {
      false
    }
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // One card
  // ---------------------------------------------------------------------------------------------------------------------------

  private var artworkCounter = 0

  private fun artwork(art: Artwork): Bitmap {
    val bitmap = Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val w = art.width.toFloat()
    val h = art.height.toFloat()

    paint.shader = LinearGradient(0f, 0f, 0f, h, art.top, art.bottom, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w, h, paint)
    paint.shader = null

    // A frame and the two diagonals, so that a crop shows.
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = h / 100f
    paint.color = Color.argb(110, 255, 255, 255)
    val inset = h / 25f
    canvas.drawRect(inset, inset, w - inset, h - inset, paint)
    canvas.drawLine(0f, 0f, w, h, paint)
    canvas.drawLine(0f, h, w, 0f, paint)

    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = h / 8f
    canvas.drawText(art.label, w / 2f, h / 2f + paint.textSize / 3f, paint)
    return bitmap
  }

  private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

  private fun attachment(art: Artwork, bytes: Int): Attachment {
    return UriAttachment(
      dataUri = Uri.parse("content://tellomi-card-art/${artworkCounter++}"),
      contentType = "image/png",
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      size = bytes.toLong(),
      width = art.width,
      height = art.height,
      fileName = "art.png",
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
  }

  private fun render(shot: Shot, name: String): Rendered {
    val art = shot.image?.let { artwork(it) }
    val artBytes = art?.let { png(it) }
    val previews = if (shot.noPreview) {
      emptyList()
    } else {
      listOf(LinkPreview(shot.url, shot.title, "The sender's description is never shown", 1_790_000_000_000L, Optional.ofNullable(shot.image?.let { attachment(it, artBytes!!.size) }), shot.rich))
    }
    val record = FakeMessageRecords.buildMediaMmsMessageRecord(body = shot.url, linkPreviews = previews, dateSent = 1_790_000_000_000L, dateReceived = 1_790_000_000_000L)

    // What the data layer decides, off the main thread, as ConversationMessage does (TellomiLinkVisual.forMessage reads the picture
    // from the device; here it is in hand).
    var decision = TellomiLinkOnly.decide(record, false)
    val decided = requireNotNull(decision.card) { "$name: rust/links decided nothing" }
    if (shot.icon != null) {
      decision = decision.copy(card = decided.copy(icon = shot.icon))
    }
    val card = decision.card!!
    val visual = if (card.level == TellomiLinkCard.Level.BRAND && !card.showImage) {
      TellomiLinkVisual.decideBrand(TellomiLinkVisual.Native, icons, card)
    } else {
      val image = if (card.showImage) shot.image else null
      TellomiLinkVisual.decide(card, image?.width ?: 0, image?.height ?: 0, image?.let { "$name/${it.width}x${it.height}" }) { artBytes }
    }

    assertEquals("$name: level", shot.expect.level, card.level)
    assertEquals("$name: kind", shot.expect.kind, card.kind ?: card.firstParty?.let { "tellomi.${it.type}" })
    assertEquals("$name: layout", shot.expect.layout, visual.layout)
    assertEquals("$name: tinted", shot.expect.tinted, visual.tint?.tinted == true)

    val item = bind(record, decision, visual, shot.local)
    if (art != null) {
      putPictures(item, art)
    }
    return Rendered(draw(item), "${card.level.name.lowercase()} · ${visual.layout?.wire ?: "-"}${if (visual.tint?.tinted == true) " · tinted" else ""}")
  }

  /** What Glide would have done: the picture is on the image view of the card (or of a large image) by hand. */
  private fun putPictures(item: ConversationItem, art: Bitmap) {
    val large = item.findViewById<ConversationItemThumbnail>(R.id.image_view)
    if (large != null && large.visibility == View.VISIBLE) {
      large.findViewById<ImageView>(R.id.thumbnail_image)?.setImageBitmap(art)
    }
    val card = item.findViewById<LinkPreviewView>(R.id.link_preview)
    val small = card.findViewById<View>(R.id.linkpreview_thumbnail)
    if (small != null && small.visibility == View.VISIBLE) {
      small.findViewById<ImageView>(R.id.thumbnail_image)?.setImageBitmap(art)
    }
  }

  private fun bind(record: org.thoughtcrime.securesms.database.model.MmsMessageRecord, decision: TellomiLinkOnly.Decision, visual: TellomiLinkVisual.Visual, local: TellomiFirstPartyCard.Local?): ConversationItem {
    val root = FrameLayout(activity)
    activity.setContentView(root)
    val item = LayoutInflater.from(activity).inflate(R.layout.conversation_item_received_multimedia, root, false) as ConversationItem
    root.addView(item)
    ConversationItem::class.java.getDeclaredField("contactPhoto").apply { isAccessible = true }.set(item, null)
    item.setEventListener(listener)

    val properties = ConversationMessage.ComputedProperties(mockk(relaxed = true), decision, local, visual, null)
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
    // The adapter does this after a bind: a large image is drawn (it starts out transparent, for the video player to take its place).
    item.showProjectionArea()
    // TELLOMI_CARDS_BUBBLE=natural leaves the bubble as the layout makes it; a number is a width in dp.
    val requested = System.getenv("TELLOMI_CARDS_BUBBLE")
    if (requested != "natural") {
      item.findViewById<View>(R.id.body_bubble).layoutParams.width = dp(requested?.toIntOrNull() ?: BUBBLE_DP)
    }
    return item
  }

  private fun draw(item: ConversationItem): Bitmap {
    item.measure(View.MeasureSpec.makeMeasureSpec(dp(PHONE_DP), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    item.layout(0, 0, item.measuredWidth, item.measuredHeight)

    // The item is drawn on a clear bitmap and that on the chat's background: the rounded corners of a card are cleared, not painted.
    val drawn = Bitmap.createBitmap(item.measuredWidth, item.measuredHeight, Bitmap.Config.ARGB_8888)
    item.draw(Canvas(drawn))
    val bitmap = Bitmap.createBitmap(item.measuredWidth, item.measuredHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(androidx.core.content.ContextCompat.getColor(activity, org.signal.core.ui.R.color.signal_colorBackground))
    canvas.drawBitmap(drawn, 0f, 0f, null)
    return bitmap
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // The contact sheet
  // ---------------------------------------------------------------------------------------------------------------------------

  private fun contactSheet(theme: String, rendered: List<Pair<Row, List<Rendered>>>): Bitmap {
    val margin = dp(8)
    val labelWidth = dp(118)
    val column = dp(PHONE_DP)
    val header = dp(28)
    val captionHeight = dp(14)
    val footnote = dp(16)
    val heights = rendered.map { (_, cards) -> cards.maxOf { it.bitmap.height } + captionHeight + margin }
    val width = labelWidth + 2 * (column + margin) + margin
    val height = header + heights.sum() + footnote + margin

    val background = androidx.core.content.ContextCompat.getColor(activity, org.signal.core.ui.R.color.signal_colorBackground)
    val ink = androidx.core.content.ContextCompat.getColor(activity, org.signal.core.ui.R.color.signal_colorOnSurface)
    val faint = androidx.core.content.ContextCompat.getColor(activity, org.signal.core.ui.R.color.signal_colorOnSurfaceVariant)
    val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet)
    canvas.drawColor(background)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = ink
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = dp(12).toFloat()
    canvas.drawText("§3.7 · $theme", margin.toFloat(), (header - margin).toFloat(), paint)
    canvas.drawText("字段全 · all fields", (labelWidth + margin).toFloat(), (header - margin).toFloat(), paint)
    canvas.drawText("只有必填 · required only", (labelWidth + column + 2 * margin).toFloat(), (header - margin).toFloat(), paint)

    var y = header
    rendered.forEachIndexed { index, (row, cards) ->
      paint.color = ink
      paint.typeface = Typeface.DEFAULT_BOLD
      paint.textSize = dp(11).toFloat()
      canvas.drawText(row.id, margin.toFloat(), (y + dp(18)).toFloat(), paint)
      paint.typeface = Typeface.DEFAULT
      paint.color = faint
      paint.textSize = dp(10).toFloat()
      canvas.drawText(row.label, margin.toFloat(), (y + dp(32)).toFloat(), paint)

      cards.forEachIndexed { column_, card ->
        val x = labelWidth + margin + column_ * (column + margin)
        canvas.drawBitmap(card.bitmap, x.toFloat(), y.toFloat(), null)
        paint.color = faint
        paint.textSize = dp(9).toFloat()
        canvas.drawText(card.caption, (x + margin).toFloat(), (y + card.bitmap.height + dp(10)).toFloat(), paint)
      }
      y += heights[index]
    }

    paint.color = faint
    paint.textSize = dp(9).toFloat()
    canvas.drawText(
      "Robolectric has no ICU date patterns, so a date reads \"Sep3\" here and \"Sep 3\" on a device (card-visual §3.9). The pictures on the cards are drawn by the test.",
      margin.toFloat(),
      (y + dp(11)).toFloat(),
      paint
    )
    return sheet
  }

  companion object {
    /** A phone's width, and the width of a large-image card (`media_bubble_max_width`), which every bubble is given here. */
    private const val PHONE_DP = 360
    private const val BUBBLE_DP = 240
  }
}
