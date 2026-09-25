/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
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
import org.signal.core.util.bytes
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.UriAttachmentBuilder
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mediasend.v3.MediaSendAttachmentSheetActivity
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.PushMediaConstraints
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.MessageTableTestUtils
import java.io.File
import org.signal.mediasend.R as MediaSendR

/**
 * Tellomi（tellomi/tellomi#1121，判据 §2.5）：在真实会话页里点「+」→ dock「文件」，端到端走附件 Sheet 的「文件」页：
 *
 * - 空态：一句说明，上限是服务端配置（`PushMediaConstraints.documentMaxSize`）；「从文件中选择」打开系统选择器，返回后还在这一页；
 *   dock「相册」换回选图页，Sheet 一直没收；
 * - 最近发送的文件是所有会话里我发出的文件，新的在前，同一份文件发给两个人只列一次；点一行 = 立即发到当前会话、Sheet 收起；
 * - 长按进多选，按勾的顺序每个一条发出，说明挂在最后一条；
 * - 搜索：大小写不敏感，超过 4 条先 3 条 +「显示更多」，搜不到写「没有找到」。
 *
 * 只在 instrumentation 参数 `tellomiShots=1` 时跑；截图写在 `<app 外部文件目录>/tellomi-shots/<屏宽>dp/files-*.png`。
 */
@RunWith(AndroidJUnit4::class)
class AttachmentFilesScreenshots {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 2)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val report = StringBuilder()
  private lateinit var outDir: File

  @Before
  fun onlyWhenAsked() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("tellomiShots") == "1")

    val widthDp = harness.context.resources.configuration.screenWidthDp
    outDir = File(harness.context.getExternalFilesDir(null), "tellomi-shots/${widthDp}dp").apply { mkdirs() }
    report.appendLine("screenWidthDp=$widthDp")

    val info = instrumentation.uiAutomation.serviceInfo
    info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    instrumentation.uiAutomation.serviceInfo = info

    // 同一次运行里的用例共用一个库：上一条发出去的文件会出现在下一条的「最近发送的文件」里。每条从空的会话开始。
    SignalDatabase.threads.deleteAllConversations()
  }

  @Test
  fun emptyPageShowsTheServerLimitAndTheSystemPickerComesBackToIt() {
    val (conversation, _) = openFilesPage()
    try {
      val limit = PushMediaConstraints(null).documentMaxSize.bytes.toUnitString()
      val empty = string(MediaSendR.string.AttachmentFilesScreen__empty, limit)
      report.appendLine("empty text=$empty")
      textNode(empty)
      assertTrue("一条都没发过时没有 🔍", inSheet { it.contentDescription?.toString() == string(MediaSendR.string.AttachmentFilesScreen__search) }.isEmpty())
      shot("files-1-empty")

      click(textNode(string(MediaSendR.string.AttachmentFilesScreen__select_from_files)))
      waitFor("系统文件选择器") { nodes { it.packageName?.toString()?.contains("documentsui") == true }.isNotEmpty() }
      report.appendLine("file picker package=${nodes { it.packageName?.toString()?.contains("documentsui") == true }.first().packageName}")
      settle(800)
      shot("files-7-system-picker")
      instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
      waitFor("回到「文件」页") { resumedActivity() is MediaSendAttachmentSheetActivity && nodes { it.text?.toString() == empty }.isNotEmpty() }

      click(dockNode(string(R.string.AttachmentKeyboard_gallery)))
      waitFor("换回选图页") { nodes { it.text?.toString() == "Recents" }.isNotEmpty() }
      assertTrue("换页时 Sheet 一直在", resumedActivity() is MediaSendAttachmentSheetActivity)
    } finally {
      File(outDir, "metrics-files-empty.txt").writeText(report.toString())
      closeSheetIfOpen()
      conversation.close()
    }
  }

  @Test
  fun recentFilesComeFromEveryChatAndTappingOneSendsItRightAway() {
    val elsewhere = harness.others[1]
    val now = System.currentTimeMillis()
    (0 until 5).forEach { index -> insertSentFile(elsewhere, "report-$index.pdf", content = "report $index", at = now - index * 60_000L) }
    // 同一份文件早些时候也发给过当前会话：最近列表里只列一次
    insertSentFile(harness.others[0], "report-0.pdf", content = "report 0", at = now - 3_600_000L)

    val (conversation, threadId) = openFilesPage()
    try {
      textNode(string(MediaSendR.string.AttachmentFilesScreen__recently_sent_files))
      shot("files-2-five")
      expandByRetappingFiles()
      val rows = (0 until 5).map { index -> rowNode("report-$index.pdf") }
      val tops = rows.map { it.boundsInScreen().top }
      report.appendLine("row tops=$tops")
      assertTrue("新的在前", tops.zipWithNext().all { (a, b) -> a < b })
      assertEquals("同一份文件只列一次", 1, rows("report-0.pdf").size)
      assertTrue("5 条时没有 🔍", inSheet { it.contentDescription?.toString() == string(MediaSendR.string.AttachmentFilesScreen__search) }.isEmpty())

      val before = MessageTableTestUtils.getMessages(threadId).map { it.id }.toSet()
      click(rowNode("report-2.pdf"))
      val sent = waitForNewOutgoing(threadId, before, 1)
      waitFor("Sheet 收起") { resumedActivity() != null && resumedActivity() !is MediaSendAttachmentSheetActivity }
      val attachments = waitForAttachments(sent[0])
      report.appendLine("sent=${sent.map { it.id }} attachments=${attachments.map { it.fileName to it.size }}")
      assertEquals("发出去的是点的那一份", listOf("report-2.pdf"), attachments.map { it.fileName })
      assertEquals("按文件发（不压缩）", "application/pdf", attachments[0].contentType)
      assertEquals("大小不变", "report 2".toByteArray().size.toLong(), attachments[0].size)
      settle(1000)
      shot("files-8-sent")
    } finally {
      File(outDir, "metrics-files-recent.txt").writeText(report.toString())
      closeSheetIfOpen()
      conversation.close()
    }
  }

  @Test
  fun longPressPicksSeveralAndTheyGoOutInOrderWithTheCaptionOnTheLast() {
    val elsewhere = harness.others[1]
    val now = System.currentTimeMillis()
    (0 until 20).forEach { index -> insertSentFile(elsewhere, "plan-$index.pdf", content = "plan $index", at = now - index * 60_000L) }

    val (conversation, threadId) = openFilesPage()
    try {
      byDescriptionNode(string(MediaSendR.string.AttachmentFilesScreen__search))
      shot("files-3-twenty")
      expandByRetappingFiles()

      assertTrue("长按进多选", longClick(rowNode("plan-0.pdf")))
      waitFor("「已选 1 个」") { nodes { it.text?.toString() == "1 selected" }.isNotEmpty() }
      click(rowNode("plan-3.pdf"))
      click(rowNode("plan-1.pdf"))
      waitFor("「已选 3 个」") { nodes { it.text?.toString() == "3 selected" }.isNotEmpty() }
      assertTrue("多选时藏 🔍", inSheet { it.contentDescription?.toString() == string(MediaSendR.string.AttachmentFilesScreen__search) }.isEmpty())
      assertTrue("多选时 dock 让位", inSheet { it.text?.toString() == DOCK_POLL }.isEmpty())

      setText(editableInSheet(), "three files")
      settle(600)
      shot("files-4-selecting")

      val before = MessageTableTestUtils.getMessages(threadId).map { it.id }.toSet()
      click(sheetNodeByDescription(string(MediaSendR.string.AttachmentFilesScreen__send)))
      val sent = waitForNewOutgoing(threadId, before, 3)
      val names = sent.map { record -> waitForAttachments(record).map { it.fileName } }
      report.appendLine("sent=${sent.map { it.id }} names=$names bodies=${sent.map { it.body }}")
      assertEquals("按勾的顺序每个一条", listOf(listOf("plan-0.pdf"), listOf("plan-3.pdf"), listOf("plan-1.pdf")), names)
      assertEquals("说明挂在最后一条", listOf("", "", "three files"), sent.map { it.body })
      waitFor("Sheet 收起") { resumedActivity() != null && resumedActivity() !is MediaSendAttachmentSheetActivity }
    } finally {
      File(outDir, "metrics-files-select.txt").writeText(report.toString())
      closeSheetIfOpen()
      conversation.close()
    }
  }

  @Test
  fun searchFindsFilesByNameShowsMoreAndSaysWhenNothingMatches() {
    val elsewhere = harness.others[1]
    val now = System.currentTimeMillis()
    (0 until 5).forEach { index -> insertSentFile(elsewhere, "Report-$index.pdf", content = "report $index", at = now - index * 60_000L) }
    (0 until 7).forEach { index -> insertSentFile(elsewhere, "notes-$index.txt", content = "notes $index", at = now - (10 + index) * 60_000L) }

    val (conversation, _) = openFilesPage()
    try {
      click(byDescriptionNode(string(MediaSendR.string.AttachmentFilesScreen__search)))
      // F-10：搜索框自动聚焦、键盘弹出，框贴在键盘上方
      waitFor("键盘弹出") { imeTop() != null }
      settle(800)
      val field = editableInSheet()
      val fieldBottom = field.boundsInScreen().bottom
      val keyboardTop = imeTop()!!
      report.appendLine("search field bottom=$fieldBottom keyboard top=$keyboardTop")
      assertTrue("搜索框在键盘上方（框底 $fieldBottom，键盘顶 $keyboardTop）", fieldBottom <= keyboardTop)
      shot("files-5a-search-keyboard")
      setText(field, "REPORT")
      waitFor("搜索结果") { nodes { it.text?.toString() == string(MediaSendR.string.AttachmentFilesScreen__files_you_sent) }.isNotEmpty() }
      hideKeyboard()
      val showMore = textNode(string(MediaSendR.string.AttachmentFilesScreen__show_more))
      assertEquals("超过 4 条先显示 3 条", 3, resultRows("report").size)
      assertTrue("搜索时顶栏收起", nodes { it.text?.toString() == string(MediaSendR.string.AttachmentFilesScreen__files) }.isEmpty())
      shot("files-5-search")

      click(showMore)
      waitFor("显示全部 5 条") { resultRows("report").size == 5 }

      setText(editableInSheet(), "nothing like this")
      hideKeyboard()
      val noResults = string(MediaSendR.string.AttachmentFilesScreen__no_results, "nothing like this")
      textNode(noResults)
      shot("files-6-no-results")

      click(sheetNodeByDescription(string(MediaSendR.string.MediaSelectScreen__close)))
      textNode(string(MediaSendR.string.AttachmentFilesScreen__select_from_files))
    } finally {
      File(outDir, "metrics-files-search.txt").writeText(report.toString())
      closeSheetIfOpen()
      conversation.close()
    }
  }

  // region helpers

  /** 打开和 others[0] 的会话 →「+」→ dock「文件」，停在「文件」页。 */
  private fun openFilesPage(): Pair<OpenedConversation, Long> {
    val other = harness.others[0]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val conversation = OpenedConversation(instrumentation.startActivitySync(ConversationIntents.createBuilderSync(harness.context, other, threadId).build()))
    settle(1500)
    conversation.onActivity { it.findViewById<View>(R.id.attach_button).performClick() }
    waitFor("附件 Sheet") { resumedActivity() is MediaSendAttachmentSheetActivity }
    settle(1200)
    click(dockNode(string(R.string.AttachmentKeyboard_file)))
    textNode(string(MediaSendR.string.AttachmentFilesScreen__select_from_files))
    assertTrue("「文件」在同一个 Sheet 里", resumedActivity() is MediaSendAttachmentSheetActivity)
    settle(600)
    return conversation to threadId
  }

  /** 重复点 dock 里选中的「文件」：回到顶部、展开到全屏。选中的 Tab 在读屏树里不报可点，按坐标点它（同 #1115 的「相册」）。 */
  private fun expandByRetappingFiles() {
    val file = textNode(string(R.string.AttachmentKeyboard_file)).boundsInScreen()
    tap(file.exactCenterX(), file.exactCenterY())
    settle(1000)
  }

  private fun tap(x: Float, y: Float) {
    val downTime = SystemClock.uptimeMillis()
    for ((action, delay) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 50L)) {
      SystemClock.sleep(delay)
      val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
      assertTrue("注入触摸失败", instrumentation.uiAutomation.injectInputEvent(event, true))
      event.recycle()
    }
  }

  /** 输入法窗口的顶边；没弹出时是 null。 */
  private fun imeTop(): Int? {
    val window = instrumentation.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
    return Rect().also { window.getBoundsInScreen(it) }.top
  }

  /** 收起键盘但不按返回（返回键在搜索里是「退出搜索」）。 */
  private fun hideKeyboard() {
    val activity = resumedActivity() ?: return
    instrumentation.runOnMainSync {
      activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    }
    settle(800)
  }

  /** 在 [recipient] 的会话里放一条我已经发出的、带一个文件的消息。 */
  private fun insertSentFile(recipient: RecipientId, name: String, content: String, at: Long) {
    val data = content.toByteArray()
    val blob = AppDependencies.blobs.forData(data).withMimeType("application/pdf").withFileName(name).createForSingleSessionInMemory()
    val attachment = UriAttachmentBuilder.build(id = at, uri = blob, contentType = "application/pdf", transferState = AttachmentTable.TRANSFER_PROGRESS_DONE, size = data.size.toLong(), fileName = name)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipient))
    val message = OutgoingMessage(recipient = Recipient.resolved(recipient), body = "", timestamp = at, attachments = listOf(attachment), isSecure = true)
    val messageId = SignalDatabase.messages.insertMessageOutbox(message, threadId, false, null).messageId
    SignalDatabase.messages.markAsSent(messageId)
  }

  private fun string(id: Int, vararg args: Any): String = harness.context.getString(id, *args)

  /** 一行文件：读屏树里它的字以文件名开头（后面是「大小 · 日期」）。只在 Sheet 的窗口里找：后面聊天里的文件消息也叫这个名字。 */
  private fun rowNode(fileName: String): AccessibilityNodeInfo {
    waitFor("「$fileName」那一行") { rows(fileName).isNotEmpty() }
    return rows(fileName).first()
  }

  private fun rows(fileName: String): List<AccessibilityNodeInfo> = inSheet { !it.isEditable && it.text?.toString()?.startsWith(fileName) == true }

  /** 搜索结果的行（不算搜索框自己：框里的字也以 [needle] 开头）。 */
  private fun resultRows(needle: String): List<AccessibilityNodeInfo> = inSheet { !it.isEditable && it.text?.toString()?.lowercase()?.startsWith(needle) == true }

  private fun dockNode(label: String): AccessibilityNodeInfo {
    var node: AccessibilityNodeInfo? = textNode(label)
    while (node != null && !node.isClickable) {
      node = node.parent
    }
    assertTrue("dock 里「$label」没有可点的一层", node != null)
    return node!!
  }

  private fun textNode(text: String): AccessibilityNodeInfo {
    waitFor("「$text」") { nodes { it.text?.toString() == text }.isNotEmpty() }
    return nodes { it.text?.toString() == text }.first()
  }

  private fun byDescriptionNode(description: String): AccessibilityNodeInfo {
    waitFor("「$description」") { byDescription(description).isNotEmpty() }
    return byDescription(description).first()
  }

  private fun byDescription(description: String): List<AccessibilityNodeInfo> = nodes { it.contentDescription?.toString() == description }

  /** 只在 Sheet 自己的窗口里找（后面会话页也可能有同名的键）。 */
  private fun sheetNodeByDescription(description: String): AccessibilityNodeInfo {
    waitFor("Sheet 里的「$description」") { inSheet { it.contentDescription?.toString() == description }.isNotEmpty() }
    return inSheet { it.contentDescription?.toString() == description }.first()
  }

  private fun editableInSheet(): AccessibilityNodeInfo {
    waitFor("Sheet 里的输入框") { inSheet { it.isEditable }.isNotEmpty() }
    return inSheet { it.isEditable }.first()
  }

  private fun inSheet(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
    val found = mutableListOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo) {
      if (predicate(node)) {
        found += node
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(::walk)
      }
    }
    walk(root)
    return found
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

  private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var target: AccessibilityNodeInfo? = node
    while (target != null && !target.isClickable) {
      target = target.parent
    }
    return target
  }

  private fun click(node: AccessibilityNodeInfo) {
    assertTrue("「${node.contentDescription ?: node.text}」没有可点的一层", clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
  }

  private fun longClick(node: AccessibilityNodeInfo): Boolean {
    return clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
  }

  private fun setText(node: AccessibilityNodeInfo, text: String) {
    val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
    assertTrue("输入「$text」失败", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
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

  private fun waitForNewOutgoing(threadId: Long, before: Set<Long>, expected: Int): List<MessageRecord> {
    val deadline = SystemClock.uptimeMillis() + 20_000
    var found: List<MessageRecord> = emptyList()
    while (SystemClock.uptimeMillis() < deadline) {
      found = MessageTableTestUtils.getMessages(threadId).filter { it.id !in before && it.isOutgoing }.sortedBy { it.dateSent }
      if (found.size >= expected) break
      SystemClock.sleep(200)
    }
    assertEquals("新发出的消息条数", expected, found.size)
    return found
  }

  private fun waitForAttachments(record: MessageRecord): List<org.thoughtcrime.securesms.attachments.DatabaseAttachment> {
    val deadline = SystemClock.uptimeMillis() + 20_000
    var attachments = SignalDatabase.attachments.getAttachmentsForMessage(record.id)
    while (SystemClock.uptimeMillis() < deadline && attachments.isEmpty()) {
      SystemClock.sleep(200)
      attachments = SignalDatabase.attachments.getAttachmentsForMessage(record.id)
    }
    return attachments
  }

  private fun closeSheetIfOpen() {
    val activity = resumedActivity()
    if (activity is MediaSendAttachmentSheetActivity) {
      instrumentation.runOnMainSync { activity.finish() }
      settle(600)
    }
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

  private fun resumedActivity(): Activity? {
    var activity: Activity? = null
    instrumentation.runOnMainSync {
      activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    return activity
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

  private companion object {
    private const val DOCK_POLL = "Poll"
  }
}
