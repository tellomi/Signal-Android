/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
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
import org.signal.mediasend.SentMediaQuality
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mediasend.v3.MediaSendV3Activity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.MessageTableTestUtils
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tellomi（tellomi/tellomi#1261，需求第三节，判据「选图面板」1、3、4、5）：在真实会话页里从「+ → 相册」打开新的选图网格，
 * 端到端走一遍：
 *
 * - 往系统相册里插几张现画的图（尺寸各不相同，发出去以后按尺寸认是哪一张），授读图权限；
 * - 会话输入框里先打好字 → 打开相册：顶栏只有 ✕ 和「最近 ⌄」；勾 3 张 → 「✓3」和 ⋮ 出现；
 * - ⋮ →「单独发送」：会话里多出 3 条、每条一张，顺序就是勾的顺序，输入框里的字作为说明挂在第 3 条（P-5、P-9、判据 4）；
 * - 另一张大图 ⋮ →「以高清质量发送」：这条的附件按「高」档处理，而「设置 → 发送媒体质量」仍是标准，再打开相册还是「以高清质量发送」（D9）。
 *
 * 只在 instrumentation 参数 `tellomiShots=1` 时跑；截图写在 `<app 外部文件目录>/tellomi-shots/<屏宽>dp/picker-*.png`。
 */
@RunWith(AndroidJUnit4::class)
class MediaPickerScreenshots {

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

    val packageName = harness.context.packageName
    instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.READ_MEDIA_IMAGES)
    instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.READ_MEDIA_VIDEO)

    // 下拉菜单在单独的弹出窗口里，要能取到所有窗口的无障碍节点。
    val info = instrumentation.uiAutomation.serviceInfo
    info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    instrumentation.uiAutomation.serviceInfo = info
  }

  @Test
  fun pickerSendsSeparatelyWithTheTypedCaptionOnTheLast() {
    val run = SystemClock.uptimeMillis()
    // 相册按修改时间（秒）倒序：隔开插，网格里的顺序就确定了——最后插的在第一格。
    val sizes = listOf(300 to 400, 400 to 300, 500 to 500)
    sizes.forEachIndexed { index, (width, height) ->
      insertGalleryImage("sep-$run-$index", width, height, index)
      SystemClock.sleep(1_100)
    }
    val sizeInCell = sizes.reversed()

    val other = harness.others[0]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val before = MessageTableTestUtils.getMessages(threadId).map { it.id }.toSet()

    val conversation = openConversation(other, threadId)
    try {
      settle(1500)
      conversation.onActivity { it.findViewById<EditText>(R.id.embedded_text_editor).setText(CAPTION) }
      openGalleryFromAttachmentKeyboard(conversation)
      waitFor("网格里的勾") { checks().size >= 3 }

      report.appendLine("grid: title=${nodes { it.text?.toString() == "Recents" }.size} pill=${nodes { it.contentDescription?.toString()?.endsWith("selected") == true }.size} more=${byDescription("More options").size}")
      assertTrue("顶栏中间是「最近」", nodes { it.text?.toString() == "Recents" }.isNotEmpty())
      assertTrue("没选时没有「✓N」", nodes { it.contentDescription?.toString()?.endsWith("selected") == true }.isEmpty())
      assertTrue("没选时没有「⋮」", byDescription("More options").isEmpty())
      shot("picker-1-grid")

      // 按第 3、1、2 格的顺序勾（发出去的顺序就是这个）
      val order = listOf(2, 0, 1)
      for (cell in order) {
        val check = checks()[cell]
        report.appendLine("click cell=$cell bounds=${check.boundsInScreen()} checks=${checks().size}")
        click(check)
        settle(600)
        report.appendLine("  pill=${nodes { it.contentDescription?.toString()?.endsWith("selected") == true }.map { it.contentDescription }}")
      }
      try {
        waitFor("「✓3」") { byDescription("3 selected").isNotEmpty() }
      } catch (e: AssertionError) {
        shot("picker-2-debug")
        report.appendLine("nodes: " + nodes { it.contentDescription != null || it.text != null }.take(40).map { "${it.contentDescription}|${it.text}|${it.boundsInScreen()}" })
        throw e
      }
      assertTrue("选了以后有「⋮」", byDescription("More options").isNotEmpty())
      assertTrue("输入框里的字带过来当说明", nodes { it.text?.toString() == CAPTION }.isNotEmpty())
      shot("picker-2-three-selected")

      click(byDescription("More options").first())
      waitFor("⋮ 菜单") { nodes { it.text?.toString() == "Send Separately" }.isNotEmpty() }
      assertTrue("有「以高清质量发送」", nodes { it.text?.toString() == "Send in High Quality" }.isNotEmpty())
      shot("picker-3-more-menu")
      click(nodes { it.text?.toString() == "Send Separately" }.first())

      val sent = waitForNewOutgoing(threadId, before, expected = 3)
      val summary = sent.map { record ->
        val attachments = SignalDatabase.attachments.getAttachmentsForMessage(record.id)
        Triple(record.body, attachments.size, attachments.firstOrNull()?.let { it.width to it.height })
      }
      report.appendLine("separately: $summary")
      assertEquals("一张一条", listOf(1, 1, 1), summary.map { it.second })
      assertEquals("顺序就是勾的顺序", order.map { sizeInCell[it] }, summary.map { it.third })
      assertEquals("说明挂最后一条", listOf("", "", CAPTION), summary.map { it.first })
      settle(1500)
      shot("picker-4-sent-separately")
    } finally {
      File(outDir, "metrics-picker-separately.txt").writeText(report.toString())
      closeMediaSendIfOpen()
      conversation.close()
    }
  }

  @Test
  fun pickerHighQualityIsForThisSendOnly() {
    SignalStore.settings.sentMediaQuality = SentMediaQuality.STANDARD
    val run = SystemClock.uptimeMillis()
    insertGalleryImage("hd-$run", 4000, 3000, 7)
    SystemClock.sleep(1_100)

    val other = harness.others[1]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val before = MessageTableTestUtils.getMessages(threadId).map { it.id }.toSet()

    val conversation = openConversation(other, threadId)
    try {
      settle(1500)
      openGalleryFromAttachmentKeyboard(conversation)
      waitFor("网格里的勾") { checks().isNotEmpty() }
      click(checks()[0])
      waitFor("「✓1」") { byDescription("1 selected").isNotEmpty() }

      click(byDescription("More options").first())
      waitFor("⋮ 菜单") { nodes { it.text?.toString() == "Send in High Quality" }.isNotEmpty() }
      assertTrue("只选一张时没有「单独发送」", nodes { it.text?.toString() == "Send Separately" }.isEmpty())
      shot("picker-5-hd-menu")
      click(nodes { it.text?.toString() == "Send in High Quality" }.first())

      val sent = waitForNewOutgoing(threadId, before, expected = 1).single()
      val attachment = SignalDatabase.attachments.getAttachmentsForMessage(sent.id).single()
      report.appendLine("hd: size=${attachment.width}x${attachment.height} quality=${attachment.transformProperties?.sentMediaQuality} setting=${SignalStore.settings.sentMediaQuality}")
      assertEquals("这一张按「高」档发", SentMediaQuality.HIGH.code, attachment.transformProperties?.sentMediaQuality)
      assertEquals("设置里的发送媒体质量不变", SentMediaQuality.STANDARD, SignalStore.settings.sentMediaQuality)

      // 再打开相册：默认仍是标准，所以「⋮」里还是「以高清质量发送」
      settle(1500)
      openGalleryFromAttachmentKeyboard(conversation)
      waitFor("网格里的勾") { checks().isNotEmpty() }
      click(checks()[0])
      waitFor("「✓1」") { byDescription("1 selected").isNotEmpty() }
      click(byDescription("More options").first())
      waitFor("⋮ 菜单") { nodes { it.text?.toString() == "Send in High Quality" }.isNotEmpty() }
      assertTrue("下次打开不默认高清", nodes { it.text?.toString() == "Send in Standard Quality" }.isEmpty())
      shot("picker-6-next-time-still-standard")
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    } finally {
      File(outDir, "metrics-picker-hd.txt").writeText(report.toString())
      closeMediaSendIfOpen()
      conversation.close()
    }
  }

  /**
   * P-3「只看已选」：勾 3 张竖图 → 点「✓3」→ 聊天背景上的一行预览；真实触摸长按 0.4 秒把第 1 张拖过第 2 张 →
   * 取消一张再撤销 → 发送：这条消息的附件顺序就是拖过之后的顺序。
   */
  @Test
  fun pickerSelectedPreviewReordersBeforeSending() {
    val run = SystemClock.uptimeMillis()
    // 竖图才能在一屏宽里放下两张多（9:16 与 3:4），拖动不碰两端的自动滚。最后插的在网格第一格。
    val sizes = listOf(1200 to 1600, 1080 to 1920, 900 to 1600)
    sizes.forEachIndexed { index, (width, height) ->
      insertGalleryImage("preview-$run-$index", width, height, index + 3)
      SystemClock.sleep(1_100)
    }
    val sizeInCell = sizes.reversed()

    val other = harness.others[1]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val before = MessageTableTestUtils.getMessages(threadId).map { it.id }.toSet()

    val conversation = openConversation(other, threadId)
    try {
      settle(1500)
      openGalleryFromAttachmentKeyboard(conversation)
      waitFor("网格里的勾") { checks().size >= 3 }
      for (cell in listOf(0, 1, 2)) {
        click(checks()[cell])
        settle(500)
      }
      waitFor("「✓3」") { byDescription("3 selected").isNotEmpty() }

      click(byDescription("3 selected").first())
      waitFor("只看已选") { nodes { it.text?.toString() == "Message preview" }.isNotEmpty() }
      assertTrue("≥ 2 张有「拖动可调整顺序」", nodes { it.text?.toString() == "Drag to reorder" }.isNotEmpty())
      assertTrue("✕ 变返回", byDescription("Back").isNotEmpty())
      settle(800)
      shot("picker-5-selected-preview")

      val cards = previewCards()
      report.appendLine("cards: ${cards.map { it.boundsInScreen() }}")
      assertEquals("三张卡片", 3, cards.size)
      val from = cards[0].boundsInScreen()
      val over = cards[1].boundsInScreen()
      longPressDrag(from.centerX().toFloat(), from.centerY().toFloat(), over.centerX() + over.width() / 3f)
      settle(800)
      shot("picker-6-reordered")

      // 取消第 2 张再撤销：撤销条计数，撤销后回到原位（由最后发出去的顺序来判）。
      // 第 3 张一半在屏幕外，它的勾整个在屏外、读屏树里没有，所以只数看得见的。
      val previewChecks = checks()
      assertTrue("看得见的勾至少两个", previewChecks.size >= 2)
      click(previewChecks[1])
      waitFor("撤销条") { nodes { it.text?.toString() == "1 deselected" }.isNotEmpty() }
      settle(300)
      shot("picker-7-undo")
      click(nodes { it.text?.toString() == "Undo" }.first())
      waitFor("撤销条收起") { nodes { it.text?.toString() == "1 deselected" }.isEmpty() && checks().size >= 2 }
      settle(500)

      click(byDescription("Send").first())
      val sent = waitForNewOutgoing(threadId, before, expected = 1)
      val attachments = SignalDatabase.attachments.getAttachmentsForMessage(sent.single().id).sortedBy { it.displayOrder }
      val sentSizes = attachments.map { it.width to it.height }
      report.appendLine("sent sizes: $sentSizes")
      assertEquals("发出去的顺序就是拖过之后的顺序", listOf(sizeInCell[1], sizeInCell[0], sizeInCell[2]), sentSizes)
    } finally {
      File(outDir, "metrics-picker-preview.txt").writeText(report.toString())
      closeMediaSendIfOpen()
      conversation.close()
    }
  }

  /**
   * P-8：给了相机权限，「最近」左上角是一格宽、两行高的实时取景（模拟器的虚拟相机），格子绕开它排；点它进拍照页。
   * 状态里的相机权限是选图页刷新时读的——这条也验了那段接线。
   */
  @Test
  fun pickerRecentsStartsWithTheLiveCamera() {
    instrumentation.uiAutomation.grantRuntimePermission(harness.context.packageName, Manifest.permission.CAMERA)
    val run = SystemClock.uptimeMillis()
    repeat(4) { index ->
      insertGalleryImage("camera-$run-$index", 400, 400, index + 7)
      SystemClock.sleep(1_100)
    }

    val other = harness.others[0]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val conversation = openConversation(other, threadId)
    try {
      settle(1500)
      openGalleryFromAttachmentKeyboard(conversation)
      waitFor("相机格") { byDescription("Go to camera").isNotEmpty() && gridTiles().size >= 4 }
      val camera = byDescription("Go to camera").first().boundsInScreen()
      val tiles = gridTiles().map { it.boundsInScreen() }
      report.appendLine("camera=$camera tiles=${tiles.take(5)}")
      val tile = tiles.first()
      val gap = harness.context.resources.displayMetrics.density * 2
      assertTrue("相机格在最左边", camera.left <= gap)
      assertEquals("一格宽", tile.width().toFloat(), camera.width().toFloat(), 2f)
      assertTrue("第一张在相机右边", tile.left >= camera.right)
      // 读屏树里相机格被裁成一格高（节点范围裁到网格格子里），两行高要从格子的排法看：第二行的第一张也在相机右边。
      val secondRowFirst = tiles[2]
      assertEquals("第 3 张在第二行", (tile.bottom + gap), secondRowFirst.top.toFloat(), 3f)
      assertTrue("第二行的第 0 列也让给了相机", secondRowFirst.left >= camera.right)
      settle(2000)
      shot("picker-8-camera-live")

      click(byDescription("Go to camera").first())
      waitFor("拍照页") { nodes { it.text?.toString() == "Photo" || it.text?.toString() == "Video" }.isNotEmpty() }
      settle(800)
      shot("picker-9-capture")
    } finally {
      File(outDir, "metrics-picker-camera.txt").writeText(report.toString())
      closeMediaSendIfOpen()
      conversation.close()
    }
  }

  // region helpers

  /** 网格里的格子：可点、点了是「Open」的那一层，从上到下、从左到右。 */
  private fun gridTiles(): List<AccessibilityNodeInfo> {
    return nodes { node ->
      node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Open" }
    }
  }

  /** 「只看已选」里的卡片：可点、点了是「Open」的那一层，从左到右。 */
  private fun previewCards(): List<AccessibilityNodeInfo> {
    return nodes { node ->
      node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Open" }
    }.sortedBy { it.boundsInScreen().left }
  }

  /** 真实触摸：按下、停 400 毫秒（过 0.3 秒的长按）、分几步横着挪到 [toX]、抬起。 */
  private fun longPressDrag(x: Float, y: Float, toX: Float) {
    val automation = instrumentation.uiAutomation
    val downTime = SystemClock.uptimeMillis()
    fun inject(action: Int, atX: Float, time: Long) {
      val event = MotionEvent.obtain(downTime, time, action, atX, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
      assertTrue("注入触摸失败", automation.injectInputEvent(event, true))
      event.recycle()
    }
    inject(MotionEvent.ACTION_DOWN, x, downTime)
    SystemClock.sleep(400)
    val steps = 12
    for (i in 1..steps) {
      SystemClock.sleep(16)
      inject(MotionEvent.ACTION_MOVE, x + (toX - x) * i / steps, SystemClock.uptimeMillis())
    }
    SystemClock.sleep(100)
    inject(MotionEvent.ACTION_UP, toX, SystemClock.uptimeMillis())
  }

  /** 所有窗口里的无障碍节点（下拉菜单是单独的弹出窗口），按屏幕位置从上到下、从左到右排好。 */
  private fun nodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
    val automation = instrumentation.uiAutomation
    // 节点会被 UiAutomation 缓存：Compose 改了读屏名称（「2 selected」→「3 selected」）以后照样读到旧的，每次先清掉。
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

  private fun byDescription(description: String): List<AccessibilityNodeInfo> = nodes { it.contentDescription?.toString() == description }

  /** 网格里各格右上角的编号勾（读屏名称「Select」），第 0 个是最新的一张。 */
  private fun checks(): List<AccessibilityNodeInfo> = byDescription("Select")

  private fun click(node: AccessibilityNodeInfo) {
    var target: AccessibilityNodeInfo? = node
    while (target != null && !target.isClickable) {
      target = target.parent
    }
    assertTrue("「${node.contentDescription ?: node.text}」没有可点的一层", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
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

  /** 「+」打开附件面板，再点「相册」，等选图页（MediaSendV3Activity）到前台。 */
  private fun openGalleryFromAttachmentKeyboard(conversation: OpenedConversation) {
    conversation.onActivity { it.findViewById<View>(R.id.attach_button).performClick() }
    settle(1200)
    clickText(harness.context.getString(R.string.AttachmentKeyboard_gallery))
    val deadline = SystemClock.uptimeMillis() + 10_000
    while (SystemClock.uptimeMillis() < deadline && resumedActivity() !is MediaSendV3Activity) {
      SystemClock.sleep(200)
    }
    assertTrue("选图页没有打开", resumedActivity() is MediaSendV3Activity)
    settle(800)
  }

  private fun closeMediaSendIfOpen() {
    val activity = resumedActivity()
    if (activity is MediaSendV3Activity) {
      instrumentation.runOnMainSync { activity.finish() }
      settle(600)
    }
  }

  /** 往系统相册插一张现画的图（纯色 + 编号），返回它在媒体库里的 uri。 */
  private fun insertGalleryImage(name: String, width: Int, height: Int, number: Int): Uri {
    val resolver = harness.context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "tellomi-picker-$name.jpg")
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TellomiPickerTest")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    resolver.openOutputStream(uri)!!.use { it.write(jpeg(width, height, number)) }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    return uri
  }

  private fun jpeg(width: Int, height: Int, number: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.HSVToColor(floatArrayOf((number * 67f) % 360f, 0.55f, 0.9f)))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textAlign = Paint.Align.CENTER
      textSize = minOf(width, height) * 0.4f
    }
    canvas.drawText("${number + 1}", width / 2f, height / 2f + paint.textSize * 0.35f, paint)
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
      bitmap.recycle()
      out.toByteArray()
    }
  }

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

  private fun resumedActivity(): Activity? {
    var activity: Activity? = null
    instrumentation.runOnMainSync {
      activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    return activity
  }

  /** 按文字点一下（附件面板里的按钮），走无障碍节点点它可点的那一层。 */
  private fun clickText(text: String) {
    val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
    assertTrue("屏幕上找不到「$text」", node != null)
    var target = node
    while (target != null && !target.isClickable) {
      target = target.parent
    }
    assertTrue("「$text」没有可点的一层", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
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
    private const val CAPTION = "今天的照片"
  }
}
