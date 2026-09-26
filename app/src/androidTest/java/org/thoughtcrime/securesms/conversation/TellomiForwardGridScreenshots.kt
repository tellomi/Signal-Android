/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.UriAttachmentBuilder
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mediapreview.MediaIntentFactory
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.MessageTableTestUtils
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tellomi（tellomi/tellomi#1259，判据「转发面板」1–6）：在真实会话页里端到端走转发网格。
 *
 * - 长按消息 →「转发」打开网格：第一格「我的收藏」，4 列；选两个 → 副标题是名字，底部附言 + 带数量的「发送」；
 *   发出后提示「已转发给 X 和 Y」，每个会话里附言在转发内容之前；
 * - 搜索：空查询是最近联系人一排；搜到的聊天勾上后回到网格，排在「我的收藏」之后并保持选中；
 * - 查看器里点转发：打开的网格是深色（「这张 / 全部 N 张」的选择在 #1257 查看器里做）。
 *
 * 只在 instrumentation 参数 `tellomiShots=1` 时跑；截图写在 `<app 外部文件目录>/tellomi-shots/<屏宽>dp/forward-*.png`。
 */
@RunWith(AndroidJUnit4::class)
class TellomiForwardGridScreenshots {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 8)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val report = StringBuilder()
  private lateinit var outDir: File

  private val names = listOf("小林", "王小明", "妈妈", "Alice", "陈老师", "Bob Chen", "阿杰", "老周")
  private val forwardedText = "周末一起去爬山吗"

  @Before
  fun onlyWhenAsked() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("tellomiShots") == "1")

    val widthDp = harness.context.resources.configuration.screenWidthDp
    outDir = File(harness.context.getExternalFilesDir(null), "tellomi-shots/${widthDp}dp").apply { mkdirs() }
    report.appendLine("screenWidthDp=$widthDp")

    val info = instrumentation.uiAutomation.serviceInfo
    info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    instrumentation.uiAutomation.serviceInfo = info

    SignalDatabase.threads.deleteAllConversations()
    harness.others.forEachIndexed { index, id ->
      SignalDatabase.recipients.setProfileName(id, ProfileName.fromParts(names[index], null))
      Recipient.live(id).refresh()
    }
    // 和每个人都聊过：老周最新，小林最旧
    val now = System.currentTimeMillis()
    harness.others.forEachIndexed { index, id -> sendText(id, "hi $index", at = now - (names.size - index) * 60_000L) }
  }

  /** 判据 1–4：长按转发 → 网格；我的收藏第一、4 列；选两个 + 附言发出；提示写名字；附言在前。 */
  @Test
  fun longPressForwardOpensTheGridAndSendsTheMessageFirst() {
    val source = harness.others[0]
    val conversation = openForwardGrid(source)
    try {
      shot("forward-1-grid")
      // 网格顺序：我的收藏 → 按最后一条消息新到旧（刚放了要转发的那条，小林最新；其余 老周 … 王小明）
      val saved = cellNode(string(R.string.note_to_self))
      val firstRowLast = cellNode(names[6])
      val secondRow = cellNode(names[5])
      report.appendLine("saved=${saved.boundsInScreen()} row1Last=${firstRowLast.boundsInScreen()} row2First=${secondRow.boundsInScreen()}")
      val gridTop = saved.boundsInScreen().top
      assertTrue("我的收藏在第一行最左", names.none { name -> cells(name).any { it.boundsInScreen().top < gridTop - 10 } })
      assertEquals("每行 4 个：第 4 格还在第一行", gridTop, firstRowLast.boundsInScreen().top)
      assertTrue("第 5 格换到第二行", secondRow.boundsInScreen().top > gridTop + 20)
      assertEquals("第 5 格在最左", saved.boundsInScreen().left, secondRow.boundsInScreen().left)

      click(cellNode(names[1]))
      click(cellNode(names[2]))
      textNode("${names[1]}, ${names[2]}")
      setText(editableWithHint(string(R.string.TellomiForwardGrid__add_a_message)), "附言")
      textNode("2")
      settle()
      shot("forward-2-selected")

      val before = harness.others.subList(1, 3).associateWith { outgoingIds(it) }
      click(textNode(string(R.string.ShareActivity__send)))
      val toast = string(R.string.TellomiForwardGrid__forwarded_to_s_and_s, names[1], names[2])
      textNode(toast)
      shot("forward-3-sent")
      report.appendLine("toast=$toast")

      harness.others.subList(1, 3).forEach { id ->
        val sent = waitForNewOutgoing(id, before.getValue(id), expected = 2)
        report.appendLine("${Recipient.resolved(id).getDisplayName(harness.context)} got ${sent.map { it.body }}")
        assertEquals("附言在转发内容之前", listOf("附言", forwardedText), sent.map { it.body })
      }
    } finally {
      File(outDir, "metrics-forward-send.txt").writeText(report.toString())
      conversation.close()
    }
  }

  /** 判据（F-9）：空查询是最近联系人一排；搜到的聊天勾上后回到网格，排在「我的收藏」之后并保持选中。 */
  @Test
  fun searchPicksAChatThatLandsRightAfterSavedMessages() {
    val conversation = openForwardGrid(harness.others[0])
    try {
      click(editableWithHint(string(R.string.TellomiForwardGrid__search)))
      textNode(string(R.string.TellomiForwardGrid__recent))
      waitFor("搜索时键盘弹起") { keyboardShown() }
      settle()
      shot("forward-4-search-recent")

      setText(editableWithHint(string(R.string.TellomiForwardGrid__search)), names[0])
      textNode(string(R.string.TellomiForwardGrid__chats))
      settle()
      shot("forward-5-search-results")
      dumpTree("search-results")
      val result = cellNode(names[0]).boundsInScreen()
      report.appendLine("searchResult=$result")

      click(cellNode(names[0]))
      waitFor("回到网格") { nodes { clean(it.text) == string(R.string.TellomiForwardGrid__chats) }.isEmpty() }
      // 同 Telegram：在搜索里勾上后清空搜索词、收起键盘
      waitFor("键盘收起") { !keyboardShown() }
      settle()
      textNode(names[0])
      val saved = cellNode(string(R.string.note_to_self)).boundsInScreen()
      val picked = cellNode(names[0]).boundsInScreen()
      report.appendLine("saved=$saved picked=$picked")
      // 搜索态的格子要整格露出来：被键盘 / sheet 裁掉的格子在无障碍树里照样在、照样点得到，只能比高度
      assertEquals("搜索结果整格可见：和网格里同一格一样高", picked.height(), result.height())
      assertEquals("排在「我的收藏」之后：同一行", saved.top, picked.top)
      assertTrue("排在「我的收藏」之后：右边一格", picked.left > saved.left && picked.left < saved.left + saved.width() * 2)
      // Compose 的 selectable（非 Tab 角色）在无障碍树里是 checkable + checked，不是 isSelected
      assertTrue("保持选中", cellNode(names[0]).isChecked)
      shot("forward-6-picked-from-search")
    } finally {
      File(outDir, "metrics-forward-search.txt").writeText(report.toString())
      conversation.close()
    }
  }

  /** 判据 1 / 6：查看器的转发也打开网格，一律深色。 */
  @Test
  fun viewerForwardOpensADarkGrid() {
    val other = harness.others[0]
    val threadId = insertAlbum(other, count = 3)
    val record = SignalDatabase.media.getGalleryMediaForThread(threadId, MediaTable.Sorting.Newest).use { cursor ->
      assertTrue("相册里有媒体", cursor.moveToFirst())
      MediaTable.MediaRecord.from(cursor)
    }
    val intent = MediaIntentFactory.intentFromMediaRecord(harness.context, record, leftIsRecent = false, allMediaInRail = false)
      .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    val viewer = instrumentation.startActivitySync(intent)
    try {
      settle(1500)
      // #1257 以后相册里的一张先问「这张 / 全部 N 张」：两条路打开的都是网格、都是深色
      val choices = listOf(
        "this" to string(R.string.MediaPreviewFragment__forward_this_photo),
        "all" to harness.context.resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_photos, 3, 3)
      )
      for ((name, choice) in choices) {
        click(byResourceId("exo_forward"))
        click(textNode(choice))
        textNode(string(R.string.MultiselectForwardFragment__forward_to))
        settle(800)
        shot(if (name == "this") "forward-7-viewer-dark-grid" else "forward-7b-viewer-dark-grid-all")
        val title = textNode(string(R.string.MultiselectForwardFragment__forward_to)).boundsInScreen()
        val background = sampleScreen(title.left - 24, title.centerY())
        report.appendLine("$name: sheet background near title=#${Integer.toHexString(background)}")
        assertTrue("从查看器打开的网格是深色（$name）", luminance(background) < 0.25)
        textNode(string(R.string.note_to_self))

        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        settle()
      }
    } finally {
      File(outDir, "metrics-forward-viewer.txt").writeText(report.toString())
      instrumentation.runOnMainSync { viewer.finish() }
      settle()
    }
  }

  // region 造数据

  private fun sendText(id: RecipientId, body: String, at: Long) {
    val recipient = Recipient.resolved(id)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val messageId = SignalDatabase.messages.insertMessageOutbox(OutgoingMessage(recipient = recipient, body = body, timestamp = at, isSecure = true), threadId, false, null).messageId
    SignalDatabase.messages.markAsSent(messageId)
    SignalDatabase.threads.update(threadId, false)
  }

  private fun insertAlbum(id: RecipientId, count: Int): Long {
    val recipient = Recipient.resolved(id)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val now = System.currentTimeMillis()
    val attachments = (0 until count).map { index ->
      val bytes = png(color = listOf(Color.rgb(255, 159, 67), Color.rgb(84, 160, 255), Color.rgb(29, 209, 161))[index % 3])
      val blob = AppDependencies.blobs.forData(bytes).withMimeType("image/png").withFileName("photo-$index.png").createForSingleSessionInMemory()
      UriAttachmentBuilder.build(id = now + index, uri = blob, contentType = "image/png", transferState = AttachmentTable.TRANSFER_PROGRESS_DONE, size = bytes.size.toLong(), fileName = "photo-$index.png")
    }
    val message = OutgoingMessage(recipient = recipient, body = "", timestamp = now, attachments = attachments, isSecure = true)
    val messageId = SignalDatabase.messages.insertMessageOutbox(message, threadId, false, null).messageId
    SignalDatabase.messages.markAsSent(messageId)
    SignalDatabase.threads.update(threadId, false)
    return threadId
  }

  private fun png(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(600, 450, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(color)
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
      out.toByteArray()
    }
  }

  private fun outgoingIds(id: RecipientId): Set<Long> {
    val threadId = SignalDatabase.threads.getThreadIdFor(id) ?: return emptySet()
    return MessageTableTestUtils.getMessages(threadId).filter { it.isOutgoing }.map { it.id }.toSet()
  }

  private fun waitForNewOutgoing(id: RecipientId, before: Set<Long>, expected: Int): List<org.thoughtcrime.securesms.database.model.MessageRecord> {
    val deadline = SystemClock.uptimeMillis() + 20_000
    var found: List<org.thoughtcrime.securesms.database.model.MessageRecord> = emptyList()
    while (SystemClock.uptimeMillis() < deadline) {
      val threadId = SignalDatabase.threads.getThreadIdFor(id)
      if (threadId != null) {
        found = MessageTableTestUtils.getMessages(threadId).filter { it.id !in before && it.isOutgoing }.sortedWith(compareBy({ it.dateSent }, { it.id }))
      }
      if (found.size >= expected) break
      SystemClock.sleep(200)
    }
    assertEquals("新发出的消息条数", expected, found.size)
    return found
  }

  // endregion

  // region 打开网格

  /** 在 [source] 的会话里放一条消息，长按它 →「转发」，等网格出来。 */
  private fun openForwardGrid(source: RecipientId): OpenedConversation {
    sendText(source, forwardedText, at = System.currentTimeMillis())
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(source))
    val conversation = OpenedConversation(instrumentation.startActivitySync(ConversationIntents.createBuilderSync(harness.context, source, threadId).build()))
    settle(1500)
    assertTrue("长按消息", longClick(textNode(forwardedText)))
    click(textNode(string(R.string.conversation_selection__menu_forward)))
    textNode(string(R.string.MultiselectForwardFragment__forward_to))
    cellNode(string(R.string.note_to_self))
    settle(800)
    return conversation
  }

  // endregion

  // region 读屏树

  private fun string(id: Int, vararg args: Any): String = harness.context.getString(id, *args)

  /** 网格里的一格：它合并后的读屏描述是全名（Compose `semantics(mergeDescendants)`）。 */
  private fun dumpTree(label: String) {
    File(outDir, "tree-$label.txt").writeText(
      nodes { true }.joinToString("\n") { "${it.className} id=${it.viewIdResourceName} text=${it.text} desc=${it.contentDescription} click=${it.isClickable} ${it.boundsInScreen()}" }
    )
  }

  private fun cellNode(name: String): AccessibilityNodeInfo {
    try {
      waitFor("「$name」那一格") { cells(name).isNotEmpty() }
    } catch (e: AssertionError) {
      File(outDir, "tree-${System.currentTimeMillis()}.txt").writeText(
        nodes { true }.joinToString("\n") { "${it.className} text=${it.text} desc=${it.contentDescription} click=${it.isClickable} sel=${it.isSelected} chk=${it.isChecked} ${it.boundsInScreen()}" }
      )
      throw e
    }
    return cells(name).first()
  }

  /** 一格 = 名字那行字往上第一层可点的节点（Compose 的 selectable；名字在它里面单独的文字节点上） */
  private fun cells(name: String): List<AccessibilityNodeInfo> {
    return nodes { !it.isClickable && clean(it.text) == name }
      .mapNotNull { clickable(it) }
      .distinctBy { it.boundsInScreen().flattenToString() }
  }

  /** 名字外面包着双向隔离符（⁨…⁩，Signal 显示名的做法），比较前去掉 */
  private fun clean(text: CharSequence?): String? = text?.toString()?.filterNot { it in "\u2066\u2067\u2068\u2069\u200E\u200F\u202A\u202B\u202C\u202D\u202E" }

  private fun textNode(text: String): AccessibilityNodeInfo {
    waitFor("「$text」") { nodes { clean(it.text) == text }.isNotEmpty() }
    return nodes { clean(it.text) == text }.first()
  }

  private fun byResourceId(id: String): AccessibilityNodeInfo {
    waitFor("「$id」") { nodes { it.viewIdResourceName?.endsWith(":id/$id") == true }.isNotEmpty() }
    return nodes { it.viewIdResourceName?.endsWith(":id/$id") == true }.first()
  }

  private fun editableWithHint(hint: String): AccessibilityNodeInfo {
    waitFor("输入框「$hint」") { editables(hint).isNotEmpty() }
    return editables(hint).first()
  }

  private fun editables(hint: String): List<AccessibilityNodeInfo> {
    return nodes { it.isEditable && (it.hintText?.toString() == hint || it.text?.toString() == hint || hasChildText(it, hint)) }
  }

  private fun hasChildText(node: AccessibilityNodeInfo, text: String): Boolean {
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      if (child.text?.toString() == text || hasChildText(child, text)) {
        return true
      }
    }
    return false
  }

  private fun nodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
    val automation = instrumentation.uiAutomation
    if (Build.VERSION.SDK_INT >= 34) {
      automation.clearCache()
    }
    val roots = automation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
    val found = mutableListOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo) {
      if (predicate(node)) {
        found += node
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(::walk)
      }
    }
    roots.forEach(::walk)
    return found.sortedWith(compareBy({ it.boundsInScreen().top }, { it.boundsInScreen().left }))
  }

  private fun AccessibilityNodeInfo.boundsInScreen(): Rect = Rect().also { getBoundsInScreen(it) }

  private fun keyboardShown(): Boolean = instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

  private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var target: AccessibilityNodeInfo? = node
    while (target != null && !target.isClickable && !target.isLongClickable) {
      target = target.parent
    }
    return target
  }

  private fun click(node: AccessibilityNodeInfo) {
    assertTrue("「${node.contentDescription ?: node.text}」没有可点的一层", clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    settle(400)
  }

  private fun longClick(node: AccessibilityNodeInfo): Boolean {
    var target: AccessibilityNodeInfo? = node
    while (target != null && !target.isLongClickable) {
      target = target.parent
    }
    return target?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
  }

  private fun setText(node: AccessibilityNodeInfo, text: String) {
    val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
    assertTrue("输入「$text」失败", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
    settle(400)
  }

  private fun waitFor(what: String, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + 20_000
    while (SystemClock.uptimeMillis() < deadline) {
      if (condition()) {
        return
      }
      SystemClock.sleep(200)
    }
    assertTrue("等不到：$what", condition())
  }

  // endregion

  // region 截图

  private fun sampleScreen(x: Int, y: Int): Int {
    instrumentation.waitForIdleSync()
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return Color.WHITE
    return bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
  }

  private fun luminance(color: Int): Double {
    return (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
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

  // endregion

  private inner class OpenedConversation(private val activity: Activity) {
    fun close() {
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
      SystemClock.sleep(500)
    }
  }

  @Suppress("unused")
  private fun resumedActivity(): Activity? {
    var activity: Activity? = null
    instrumentation.runOnMainSync {
      activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    return activity
  }
}
