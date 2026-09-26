/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2.items

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.emoji.EmojiDependencies
import org.signal.emoji.EmojiSource
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationItem
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import kotlin.math.roundToInt

/**
 * Tellomi：气泡在尾巴那一侧多留 e = 6 的外边距，尾巴不贴屏幕边、不压头像
 * （规范 #1204 第 2 节最后一条，owner 2026-09-26 定照规范加）。
 *
 * 我发的：气泡离屏幕边 16 + 6；对方发的：单聊离屏幕边 16 + 6，群里离头像 8 + 6。
 * 两套渲染（V2、旧版 ConversationItem）各排一次版，量气泡的实际位置；「正在输入」气泡也带尾巴（#93），一样量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class TellomiBubbleTailMarginTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class))

  private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Signal_DayNight)
  private val density = context.resources.displayMetrics.density

  @Before
  fun setUp() {
    // 两套布局里都有 EmojiTextView，构造时就要表情库：单测里没人去装，EmojiSource.latest 会一直等。按包里自带的装一份
    // EmojiDependencies.init 先到先得：同一个测试 JVM 里前面的用例（MockAppDependenciesRule → AppDependencies.init）可能已经
    // 装上了 App 自己的 EmojiDependenciesProvider，下面这次 init 就不算数，它去读 SignalStore.internal.forceBuiltInEmoji——一起钉成 true
    every { signalStore.internal.forceBuiltInEmoji } returns true
    EmojiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      mockk(relaxed = true) { every { provideForceBuiltInEmoji() } returns true }
    )
    EmojiSource.refresh()
  }

  @Test
  fun `V2 - my bubble ends 16 + 6 from the screen edge, and 6 before the failed-send icon`() {
    for (layout in listOf(R.layout.v2_conversation_item_text_only_outgoing, R.layout.v2_conversation_item_media_outgoing)) {
      val root = inflate(layout)
      val body = root.findViewById<View>(R.id.conversation_item_body_wrapper)

      // 发送失败 / 被限流才露出这个图标，平常绑定时是 setNone() → GONE
      val alert = root.findViewById<View>(R.id.conversation_item_alert)
      alert.visibility = View.GONE
      layOut(root)
      assertThat(root.width - body.right, name = name(layout)).isEqualTo(dp(16 + 6))

      alert.visibility = View.VISIBLE
      layOut(root)
      assertThat(alert.left - body.right, name = name(layout) + " with the alert icon").isEqualTo(dp(6))
    }
  }

  @Test
  fun `V2 - their bubble starts 16 + 6 from the screen edge in a 1-1 chat, and 8 + 6 after the avatar in a group`() {
    for (layout in listOf(R.layout.v2_conversation_item_text_only_incoming, R.layout.v2_conversation_item_media_incoming)) {
      val root = inflate(layout)
      val body = root.findViewById<View>(R.id.conversation_item_body_wrapper)
      val avatar = root.findViewById<View>(R.id.contact_photo)

      avatar.visibility = View.GONE
      layOut(root)
      assertThat(body.left, name = name(layout) + " in a 1:1 chat").isEqualTo(dp(16 + 6))

      avatar.visibility = View.VISIBLE
      layOut(root)
      assertThat(body.left - avatar.right, name = name(layout) + " in a group").isEqualTo(dp(8 + 6))
    }
  }

  /*
   * 旧版 ConversationItem 的 onMeasure 要读绑定后的消息（messageRecord、conversationMessage、footer…），
   * 不绑定量不了；这里断言决定位置的那几个值：setGutterSizes 定的内边距 + 气泡自己的外边距。
   */

  @Test
  fun `legacy - my bubble keeps 16 + 6 from the screen edge, in a 1-1 chat and in a group`() {
    for (layout in listOf(R.layout.conversation_item_sent_text_only, R.layout.conversation_item_sent_multimedia)) {
      for (isGroupThread in listOf(false, true)) {
        val item = inflate(layout) as ConversationItem
        setGutterSizes(item, outgoing = true, isGroupThread = isGroupThread)
        // 气泡在右边的指示区（发送失败的图标，平常宽 0）左边：离屏幕边 = 整条的右内边距 + 气泡的右外边距
        val bubble = item.findViewById<View>(R.id.body_bubble).layoutParams as ViewGroup.MarginLayoutParams
        assertThat(item.paddingEnd + bubble.marginEnd, name = name(layout) + " group=$isGroupThread").isEqualTo(dp(16 + 6))
      }
    }
  }

  @Test
  fun `legacy - their bubble starts 16 + 6 from the screen edge in a 1-1 chat, and 8 + 6 after the avatar in a group`() {
    for (layout in listOf(R.layout.conversation_item_received_text_only, R.layout.conversation_item_received_multimedia)) {
      val item = inflate(layout) as ConversationItem
      setGutterSizes(item, outgoing = false, isGroupThread = false)
      // 气泡接在头像后面（单聊头像 GONE，就从左内边距开始）：单聊离屏幕边 = 左内边距 + 气泡左外边距，群里离头像 = 气泡左外边距
      val bubble = item.findViewById<View>(R.id.body_bubble).layoutParams as ViewGroup.MarginLayoutParams
      assertThat(item.paddingStart + bubble.marginStart, name = name(layout) + " in a 1:1 chat").isEqualTo(dp(16 + 6))
      assertThat(bubble.marginStart, name = name(layout) + " in a group").isEqualTo(dp(8 + 6))

      // 气泡上方「收藏自某人」那一行也接在头像后面，跟着气泡一起挪，左边对齐
      val starredSource = item.findViewById<View>(R.id.conversation_item_starred_source_stub).layoutParams as ViewGroup.MarginLayoutParams
      assertThat(starredSource.marginStart, name = name(layout) + " starred source").isEqualTo(bubble.marginStart)
    }
  }

  @Test
  fun `typing - their typing bubble starts 16 + 6 from the screen edge in a 1-1 chat, and 8 + 6 after the avatar in a group`() {
    val root = inflate(R.layout.conversation_typing_view)
    val card = root.findViewById<View>(R.id.indicator_card)
    val avatar = root.findViewById<View>(R.id.typing_avatar_1)

    // setTypists：单聊不露头像，群里露第一个打字的人的头像
    avatar.visibility = View.GONE
    layOut(root)
    assertThat(card.left, name = "typing bubble in a 1:1 chat").isEqualTo(dp(16 + 6))

    avatar.visibility = View.VISIBLE
    layOut(root)
    assertThat(card.left - avatar.right, name = "typing bubble in a group").isEqualTo(dp(8 + 6))
  }

  private fun dp(value: Int): Int = (value * density).roundToInt()

  private fun name(@LayoutRes layout: Int): String = context.resources.getResourceEntryName(layout)

  private fun inflate(@LayoutRes layout: Int): View {
    val parent = FrameLayout(context)
    return LayoutInflater.from(context).inflate(layout, parent, false)
  }

  private fun layOut(root: View): View {
    val width = dp(400)
    root.measure(
      View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    root.layout(0, 0, width, root.measuredHeight)
    return root
  }

  /** 旧版渲染的左右内边距是绑定时由私有的 setGutterSizes 定的，布局文件里写的会被它盖掉；这里照它走一遍 */
  private fun setGutterSizes(item: ConversationItem, outgoing: Boolean, isGroupThread: Boolean) {
    val record = mockk<MessageRecord>(relaxed = true) {
      every { isOutgoing } returns outgoing
    }
    ConversationItem::class.java
      .getDeclaredMethod("setGutterSizes", MessageRecord::class.java, Boolean::class.javaPrimitiveType)
      .apply { isAccessible = true }
      .invoke(item, record, isGroupThread)
  }
}
