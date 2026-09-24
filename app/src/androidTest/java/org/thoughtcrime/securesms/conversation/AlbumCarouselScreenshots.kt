/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.PointerAttachment
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.components.albumcarousel.AlbumCarouselGeometry
import org.thoughtcrime.securesms.components.albumcarousel.AlbumCarouselView
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.Optional
import kotlin.math.roundToInt

/**
 * Tellomi（tellomi/tellomi#1257）：横滑相册在**真实会话页**里的判据（需求第七节「多图横滑」1–7）与截图。
 *
 * 用 [SignalActivityRule] 伪造一个已注册的本机和几个联系人（不连服务端、不建真账号），往数据库里插带真实图片数据的
 * 相册消息，再用正式入口（[ConversationIntents] → MainActivity）打开会话：断言几何与手势，顺手截图。
 * 只在给了 instrumentation 参数 `tellomiShots=1` 时跑，平时跳过（截图写文件、结果依赖屏幕尺寸）。
 *
 * 屏宽用 `adb shell wm size` + `wm density 480` 切（1 dp = 3 px）：402 → 1206x2622，440 → 1320x2868，375 → 1125x2001。
 * 截图与读数写在 `<app 外部文件目录>/tellomi-shots/<屏宽>dp/`。
 */
@RunWith(AndroidJUnit4::class)
class AlbumCarouselScreenshots {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 8, createGroup = true)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val report = StringBuilder()
  private lateinit var outDir: File
  private var widthDp = 0
  private var density = 1f

  @Before
  fun onlyWhenAsked() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("tellomiShots") == "1")

    val config = harness.context.resources.configuration
    widthDp = config.screenWidthDp
    density = harness.context.resources.displayMetrics.density
    outDir = File(harness.context.getExternalFilesDir(null), "tellomi-shots/${widthDp}dp").apply { mkdirs() }
    report.appendLine("screenWidthDp=$widthDp screenHeightDp=${config.screenHeightDp} density=$density")
  }

  /** 判据 1–4：2 / 5 / 12 / 32 张，对方与自己各一条；行高、起点、滑到底、吸附、滑动回复。 */
  @Test
  fun albumsByCount() {
    for ((index, count) in listOf(2, 5, 12, 32).withIndex()) {
      val other = harness.others[index]
      val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))

      insertIncomingText(other, threadId, "对方发来 $count 张")
      insertIncomingAlbum(other, threadId, sizes(count, portraitOnly = false), body = null)
      insertOutgoingAlbum(Recipient.resolved(other), threadId, sizes(count, portraitOnly = count == 2), body = null)

      val conversation = openConversation(other, threadId)
      try {
        waitForCarousels(conversation, 2)
        val metrics = record("count=$count", conversation)
        shot("count$count-1-rest")

        // C-2 行高；C-5 起点（对方对齐对方气泡起点 16，自己对齐自己气泡列起点）
        val expectedRow = AlbumCarouselGeometry.rowHeightDp(widthDp.toFloat(), 0f, false)
        metrics.forEach { assertEquals("row height at $widthDp dp", expectedRow, it.rowHeightDp, 1f) }
        val incoming = metrics.first { !it.outgoing }
        val outgoing = metrics.first { it.outgoing }
        assertEquals(16f, incoming.startDp, 0.5f)
        assertTrue(incoming.scrollable)
        if (count == 2) {
          // 判据 3：两张 9:16 竖图放得下 → 整组靠右、不能滑，可以照常滑动回复
          assertFalse(outgoing.scrollable)
          assertEquals(widthDp - 16f, outgoing.endDp, 0.5f)
          assertFalse(outgoing.disallowSwipe)
        } else {
          assertEquals(48f, outgoing.startDp, 0.5f)
          assertTrue(outgoing.scrollable)
        }
        metrics.filter { it.scrollable }.forEach { assertTrue("C-11 scrollable album claims the drag", it.disallowSwipe) }

        // 判据 1–2：滑到底能看到最后一张，最后一张右边对齐右边距 16
        conversation.onActivity { activity -> liveCarousels(activity).forEach { it.revealItem(it.itemCount - 1) } }
        settle()
        conversation.onActivity { activity ->
          for (carousel in liveCarousels(activity)) {
            val last = carousel.findItemView(carousel.itemCount - 1)!!
            val right = dp(screenX(last) + last.width)
            report.appendLine("  end: items=${carousel.itemCount} lastItemRightOnScreenDp=$right")
            assertEquals(widthDp - 16f, right, 0.5f)
          }
        }
        shot("count$count-2-end")

        if (count == 5) {
          // 判据 2：松手停在「某一张左边对齐起点」，下一张露出 ≥ 48
          conversation.onActivity { activity -> liveCarousels(activity).forEach { it.revealItem(0) } }
          settle()
          dragOnAlbum(conversation, 0, fromFraction = 0.7f, toFraction = 0.35f)
          conversation.onActivity { activity ->
            val carousel = liveCarousels(activity)[0]
            val current = carousel.currentItemIndexForTesting()
            val item = carousel.findItemView(current)!!
            val next = carousel.findItemView(current + 1)!!
            report.appendLine("  afterLeftDrag: index=$current itemLeftDp=${dp(screenX(item))} nextLeftDp=${dp(screenX(next))}")
            assertTrue(current >= 1)
            assertEquals(16f, dp(screenX(item)), 0.5f)
            assertTrue(dp(screenX(next)) <= widthDp - 48f)
          }
          shot("count$count-3-after-left-drag-snapped")

          // 判据 4：能滑的相册上往右拖只翻图，不出现回复
          dragOnAlbum(conversation, 0, fromFraction = 0.2f, toFraction = 0.9f)
          val replyShown = isReplyQuoteShown(conversation)
          report.appendLine("  replyQuoteShownAfterRightDragOnAlbum=$replyShown")
          assertFalse("C-11 right drag on a scrollable album must not reply", replyShown)
          shot("count$count-4-after-right-drag-no-reply")

          // C-13：读屏整组一个节点「相册，共 N 项，第 k 项」+ 自定义动作「下一项 / 上一项」
          conversation.onActivity { activity ->
            val carousel = liveCarousels(activity)[0]
            carousel.revealItem(0)
            val info = AccessibilityNodeInfo()
            carousel.onInitializeAccessibilityNodeInfo(info)
            val labels = info.actionList.mapNotNull { it.label?.toString() }
            report.appendLine("  a11y: description=\"${info.contentDescription}\" actions=$labels")
            assertTrue(info.contentDescription.toString().contains("5"))
            assertTrue(info.actionList.any { it.id == R.id.accessibility_action_album_next })
            assertTrue(carousel.performAccessibilityAction(R.id.accessibility_action_album_next, null))
          }
          settle()
          conversation.onActivity { activity ->
            val current = liveCarousels(activity)[0].currentItemIndexForTesting()
            report.appendLine("  a11y: afterNextAction index=$current")
            assertEquals(1, current)
          }
        }
      } finally {
        conversation.close()
      }
    }

    File(outDir, "metrics-by-count.txt").writeText(report.toString())
  }

  /** C-8、C-11：群昵称在上、说明在下、引用在上；说明气泡上照常滑动回复。 */
  @Test
  fun captionQuoteAndGroupName() {
    val group = harness.group!!
    val groupRecipient = Recipient.resolved(group.recipientId)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(groupRecipient)
    val sender = harness.others[0]

    insertIncomingText(sender, threadId, "群聊里带昵称与说明", group.groupId)
    val quoted = insertIncomingAlbum(sender, threadId, sizes(6, portraitOnly = false), body = "这是说明文字：昨天下午在公园拍的", groupId = group.groupId)
    insertOutgoingAlbum(groupRecipient, threadId, sizes(4, portraitOnly = false), body = "回复一下，也带说明", quoteOf = quoted)

    val conversation = openConversation(group.recipientId, threadId)
    try {
      waitForCarousels(conversation, 1)
      val metrics = record("group-caption-quote", conversation)
      metrics.filter { !it.outgoing }.forEach { assertEquals("group incoming starts after the avatar", 48f, it.startDp, 0.5f) }
      shot("group-1-caption-quote-name")

      dragOnCaption(conversation, 0)
      val replyShown = isReplyQuoteShown(conversation)
      report.appendLine("  replyQuoteShownAfterRightDragOnCaption=$replyShown")
      assertTrue("C-11 right drag on the caption bubble still replies", replyShown)
      shot("group-2-right-drag-on-caption-replies")

      scrollConversationToBottom(conversation)
      record("group-bottom", conversation).filter { it.outgoing }.forEach { assertEquals(84f, it.startDp, 0.5f) }
      shot("group-3-outgoing-quote-caption")
    } finally {
      conversation.close()
    }

    File(outDir, "metrics-group.txt").writeText(report.toString())
  }

  /** 判据 5（C-9）：点第 7 张，查看器从第 7 张开始；在查看器里翻一张再关，相册已滚到那一张、它整张露出。 */
  @Test
  fun viewerOpensAtTappedItemAndReturnsToCurrentOne() {
    val other = harness.others[4]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    insertIncomingAlbum(other, threadId, sizes(12, portraitOnly = false), body = null)

    val conversation = openConversation(other, threadId)
    try {
      waitForCarousels(conversation, 1)
      // 先把第 7 张滚出来、等图加载完再点（没加载完时上游不做共享元素转场）
      conversation.onActivity { activity -> liveCarousels(activity)[0].revealItem(6) }
      settle(1500)
      conversation.onActivity { activity -> liveCarousels(activity)[0].findItemView(6)!!.performClick() }
      settle(2500)
      shot("viewer-1-opened-on-7")

      // 查看器里横向滑一张，再返回
      val width = harness.context.resources.displayMetrics.widthPixels.toFloat()
      val height = harness.context.resources.displayMetrics.heightPixels.toFloat()
      drag(width * 0.85f, height * 0.5f, width * 0.15f)
      settle(1500)
      shot("viewer-2-swiped")
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      settle(2000)
      shot("viewer-3-closed")

      conversation.onActivity { activity ->
        val carousel = liveCarousels(activity)[0]
        val current = carousel.currentItemIndexForTesting()
        val item = carousel.findItemView(current)!!
        report.appendLine("viewer: afterReturn currentIndex=$current itemLeftDp=${dp(screenX(item))} itemRightDp=${dp(screenX(item) + item.width)}")
        // 查看器里从第 7 张（下标 6）翻到了相邻的一张：关掉后相册停在那一张，而且它整张在屏幕里
        assertTrue(current == 5 || current == 7)
        assertTrue(screenX(item) >= 0 && screenX(item) + item.width <= activity.resources.displayMetrics.widthPixels)
      }
    } finally {
      conversation.close()
    }

    File(outDir, "metrics-viewer.txt").writeText(report.toString())
  }

  /** C-12、长按、渐变聊天色：一张都没下载 / 下了一部分；长按快照带上整行相册；渐变色只画在上下两段。 */
  @Test
  fun downloadStatesLongPressAndGradient() {
    val other = harness.others[5]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))

    insertIncomingAlbum(other, threadId, sizes(5, portraitOnly = false), body = null, downloaded = emptySet())
    insertIncomingAlbum(other, threadId, sizes(5, portraitOnly = false), body = null, downloaded = setOf(0, 2))

    val conversation = openConversation(other, threadId)
    try {
      waitForCarousels(conversation, 2)
      shot("download-1-none-and-partial")
    } finally {
      conversation.close()
    }

    val gradientOther = harness.others[6]
    SignalDatabase.recipients.setColor(gradientOther, ChatColorsPalette.Bubbles.gradients.first())
    val gradientRecipient = Recipient.resolved(gradientOther)
    val gradientThread = SignalDatabase.threads.getOrCreateThreadIdFor(gradientRecipient)
    val quoted = insertIncomingAlbum(gradientOther, gradientThread, sizes(3, portraitOnly = false), body = "对方的说明")
    insertOutgoingAlbum(gradientRecipient, gradientThread, sizes(4, portraitOnly = false), body = "渐变聊天色下的说明气泡", quoteOf = quoted)

    val gradientConversation = openConversation(gradientOther, gradientThread)
    try {
      waitForCarousels(gradientConversation, 1)
      scrollConversationToBottom(gradientConversation)
      shot("gradient-1-quote-album-caption")

      // 长按相册里的一张：弹出回应条与菜单，快照里要有整行相册
      var x = 0f
      var y = 0f
      gradientConversation.onActivity { activity ->
        val item = liveCarousels(activity).last().findItemView(0)!!
        x = screenX(item) + item.width / 2f
        y = screenY(item) + item.height / 2f
      }
      val down = SystemClock.uptimeMillis()
      instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
      SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 1200L)
      shot("gradient-2-long-press-overlay")
      instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
      settle(800)
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      settle(800)
    } finally {
      gradientConversation.close()
    }
  }

  // ---- 读数 ------------------------------------------------------------------------------

  private data class CarouselMetrics(
    val outgoing: Boolean,
    val rowHeightDp: Float,
    val startDp: Float,
    val endDp: Float,
    val scrollable: Boolean,
    val disallowSwipe: Boolean
  )

  private fun record(label: String, conversation: OpenedConversation): List<CarouselMetrics> {
    val metrics = mutableListOf<CarouselMetrics>()
    conversation.onActivity { activity ->
      report.appendLine(label)
      for (carousel in liveCarousels(activity)) {
        val geometry = carousel.geometryForTesting() ?: continue
        val item = carousel.parent as ConversationItem
        val cx = screenX(carousel) + carousel.width * 0.5f
        val cy = screenY(carousel) + carousel.height * 0.5f
        val m = CarouselMetrics(
          outgoing = item.isOutgoing,
          rowHeightDp = dp(carousel.height),
          startDp = dp(geometry.itemLefts[0]),
          endDp = dp(geometry.itemLefts.last() + geometry.itemWidths.last() - geometry.maxScroll),
          scrollable = geometry.isScrollable,
          disallowSwipe = item.disallowSwipe(cx, cy)
        )
        metrics += m
        report.appendLine(
          "  ${if (m.outgoing) "outgoing" else "incoming"} items=${geometry.itemCount} rowHeightDp=${m.rowHeightDp} startDp=${m.startDp} endDp=${m.endDp}" +
            " scrollable=${m.scrollable} disallowSwipeOnAlbum=${m.disallowSwipe}" +
            " itemWidthsDp=${geometry.itemWidths.joinToString(",") { dp(it).toString() }}"
        )
      }
    }
    return metrics
  }

  private fun dp(px: Int): Float = ((px / density) * 10f).roundToInt() / 10f

  private fun screenX(view: View): Int = IntArray(2).also { view.getLocationOnScreen(it) }[0]

  private fun screenY(view: View): Int = IntArray(2).also { view.getLocationOnScreen(it) }[1]

  private fun isReplyQuoteShown(conversation: OpenedConversation): Boolean {
    var shown = false
    conversation.onActivity { activity ->
      shown = findQuoteInComposer(activity.window.decorView as ViewGroup)?.isShown == true
    }
    return shown
  }

  private fun findQuoteInComposer(group: ViewGroup): View? {
    for (i in 0 until group.childCount) {
      val child = group.getChildAt(i)
      if (child is ConversationItem) continue
      if (child.id == R.id.quote_view) return child
      if (child is ViewGroup) {
        findQuoteInComposer(child)?.let { return it }
      }
    }
    return null
  }

  // ---- 打开会话、手势与截图 ----------------------------------------------------------------

  /**
   * MainActivity 会改写自己的 intent，ActivityScenario 按 intent 认 Activity 就对不上号；这里直接用
   * Instrumentation.startActivitySync 拿到实例。
   */
  private fun openConversation(recipientId: RecipientId, threadId: Long): OpenedConversation {
    val intent = ConversationIntents.createBuilderSync(harness.context, recipientId, threadId).build()
    return OpenedConversation(instrumentation.startActivitySync(intent))
  }

  private inner class OpenedConversation(private val activity: Activity) {
    fun onActivity(block: (Activity) -> Unit) {
      var error: Throwable? = null
      instrumentation.runOnMainSync {
        try {
          block(activity)
        } catch (t: Throwable) {
          error = t
        }
      }
      error?.let { throw it }
    }

    fun close() {
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
      SystemClock.sleep(500)
    }
  }

  private fun scrollConversationToBottom(conversation: OpenedConversation) {
    conversation.onActivity { activity ->
      activity.findViewById<RecyclerView>(R.id.conversation_item_recycler)?.scrollToPosition(0)
    }
    settle(1200)
  }

  private fun dragOnAlbum(conversation: OpenedConversation, carouselIndex: Int, fromFraction: Float, toFraction: Float) {
    var y = 0f
    var fromX = 0f
    var toX = 0f
    conversation.onActivity { activity ->
      val carousel = liveCarousels(activity)[carouselIndex]
      y = screenY(carousel) + carousel.height / 2f
      fromX = screenX(carousel) + carousel.width * fromFraction
      toX = screenX(carousel) + carousel.width * toFraction
    }
    drag(fromX, y, toX)
    settle(900)
  }

  /** 在相册下方的说明气泡上往右拖 110dp。 */
  private fun dragOnCaption(conversation: OpenedConversation, carouselIndex: Int) {
    var y = 0f
    var fromX = 0f
    conversation.onActivity { activity ->
      val item = liveCarousels(activity)[carouselIndex].parent as ConversationItem
      val bubble = item.bodyBubble!!
      y = screenY(bubble) + bubble.height - 20f * density
      fromX = screenX(bubble) + 12f * density
    }
    drag(fromX, y, fromX + 110f * density)
    settle(900)
  }

  private fun drag(fromX: Float, y: Float, toX: Float) {
    val down = SystemClock.uptimeMillis()
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, fromX, y, 0))
    val steps = 24
    for (i in 1..steps) {
      val x = fromX + (toX - fromX) * i / steps
      instrumentation.sendPointerSync(MotionEvent.obtain(down, down + i * 12L, MotionEvent.ACTION_MOVE, x, y, 0))
    }
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down + steps * 12L + 10, MotionEvent.ACTION_UP, toX, y, 0))
  }

  private fun shot(name: String) {
    instrumentation.waitForIdleSync()
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
    File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun settle(millis: Long = 600) {
    instrumentation.waitForIdleSync()
    SystemClock.sleep(millis)
    instrumentation.waitForIdleSync()
  }

  private fun waitForCarousels(conversation: OpenedConversation, expected: Int) {
    val deadline = SystemClock.uptimeMillis() + 20_000
    var found = 0
    while (SystemClock.uptimeMillis() < deadline && found < expected) {
      conversation.onActivity { activity -> found = liveCarousels(activity).size }
      if (found < expected) SystemClock.sleep(250)
    }
    settle(1500)
  }

  /** 会话列表进来后还会重绑几次（标已读等），每次都重新找当前在屏上的相册视图。 */
  private fun liveCarousels(activity: Activity): List<AlbumCarouselView> {
    return collect(activity.window.decorView)
      .filter { it.visibility == View.VISIBLE && it.isAttachedToWindow && it.height > 0 }
      .sortedBy { screenY(it) }
  }

  private fun collect(view: View): List<AlbumCarouselView> {
    if (view is AlbumCarouselView) return listOf(view)
    if (view !is ViewGroup) return emptyList()
    return (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
  }

  // ---- 造数据 ------------------------------------------------------------------------------

  /** 宽高轮换：竖 3:4、横 4:3、16:9、9:16、1:1；「只要竖图」时全是 9:16（验证自己发的放得下时靠右）。 */
  private fun sizes(count: Int, portraitOnly: Boolean): List<Pair<Int, Int>> {
    val cycle = listOf(1200 to 1600, 1600 to 1200, 1920 to 1080, 1080 to 1920, 1200 to 1200)
    return List(count) { i -> if (portraitOnly) 1080 to 1920 else cycle[i % cycle.size] }
  }

  private fun image(width: Int, height: Int, number: Int): ByteArray {
    val w = width / 4
    val h = height / 4
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val hue = (number * 47) % 360f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
      0f,
      0f,
      w.toFloat(),
      h.toFloat(),
      Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f)),
      Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.75f, 0.65f)),
      Shader.TileMode.CLAMP
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = minOf(w, h) * 0.42f
    canvas.drawText(number.toString(), w / 2f, h / 2f + paint.textSize * 0.35f, paint)
    paint.textSize = minOf(w, h) * 0.09f
    canvas.drawText("$width×$height", w / 2f, h - paint.textSize * 1.2f, paint)
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
      out.toByteArray()
    }
  }

  private fun insertIncomingText(from: RecipientId, threadId: Long, body: String, groupId: GroupId? = null) {
    val now = System.currentTimeMillis()
    SignalDatabase.messages.insertMessageInbox(
      IncomingMessage(type = MessageType.NORMAL, from = from, sentTimeMillis = now, serverTimeMillis = now, receivedTimeMillis = now, groupId = groupId, body = body),
      threadId
    ).get()
    SystemClock.sleep(5)
  }

  private fun insertIncomingAlbum(
    from: RecipientId,
    threadId: Long,
    sizes: List<Pair<Int, Int>>,
    body: String?,
    groupId: GroupId? = null,
    downloaded: Set<Int> = sizes.indices.toSet()
  ): Pair<Long, RecipientId> {
    val now = System.currentTimeMillis()
    val pointers = sizes.map { (w, h) -> pointer(w, h) }
    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = from,
      sentTimeMillis = now,
      serverTimeMillis = now,
      receivedTimeMillis = now,
      groupId = groupId,
      body = body,
      attachments = PointerAttachment.forPointers(Optional.of(pointers))
    )
    val insert = SignalDatabase.messages.insertMessageInbox(message, threadId).get()
    SignalDatabase.attachments.getAttachmentsForMessage(insert.messageId).forEachIndexed { i, attachment ->
      if (i in downloaded) {
        val (w, h) = sizes[i]
        SignalDatabase.attachments.finalizeAttachmentAfterDownload(insert.messageId, attachment.attachmentId, ByteArrayInputStream(image(w, h, i + 1)))
      }
    }
    SystemClock.sleep(5)
    return now to from
  }

  private fun insertOutgoingAlbum(to: Recipient, threadId: Long, sizes: List<Pair<Int, Int>>, body: String?, quoteOf: Pair<Long, RecipientId>? = null) {
    val now = System.currentTimeMillis()
    val attachments = sizes.mapIndexed { i, (w, h) ->
      val bytes = image(w, h, i + 1)
      val uri = AppDependencies.blobs.forData(bytes).createForSingleSessionInMemory()
      UriAttachment(
        dataUri = uri,
        contentType = "image/jpeg",
        transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
        size = bytes.size.toLong(),
        width = w,
        height = h,
        fileName = null,
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
        transformProperties = null,
        uuid = null
      )
    }
    val quote = quoteOf?.let { (sentAt, author) -> QuoteModel(sentAt, author, "图片", false, null, null, QuoteModel.Type.NORMAL, null) }
    val message = OutgoingMessage(
      threadRecipient = to,
      sentTimeMillis = now,
      body = body ?: "",
      isSecure = true,
      attachments = attachments,
      outgoingQuote = quote
    )
    val messageId = SignalDatabase.messages.insertMessageOutbox(message, threadId, false, null).messageId
    SignalDatabase.attachments.getAttachmentsForMessage(messageId).forEach {
      SignalDatabase.attachments.setTransferState(messageId, it.attachmentId, AttachmentTable.TRANSFER_PROGRESS_DONE)
    }
    SignalDatabase.messages.markAsSent(messageId, true)
    SystemClock.sleep(5)
  }

  private fun pointer(width: Int, height: Int): SignalServiceAttachmentPointer {
    return SignalServiceAttachmentPointer(
      Cdn.CDN_3.cdnNumber,
      SignalServiceAttachmentRemoteId.from("", Cdn.CDN_3.cdnNumber),
      "image/jpeg",
      ByteArray(64).also { SecureRandom().nextBytes(it) },
      Optional.empty(),
      Optional.empty(),
      width,
      height,
      Optional.empty(),
      Optional.empty(),
      0,
      Optional.empty(),
      false,
      false,
      false,
      Optional.empty(),
      Optional.empty(),
      System.currentTimeMillis(),
      null
    )
  }
}
