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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager2.widget.ViewPager2
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
import org.thoughtcrime.securesms.components.AlbumThumbnailView
import org.thoughtcrime.securesms.components.InputPanel
import org.thoughtcrime.securesms.components.ThumbnailView
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardBottomSheet
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragment
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.mediapreview.MediaPreviewCenterControlsView
import org.thoughtcrime.securesms.mediapreview.MediaPreviewPlayerControlView
import org.thoughtcrime.securesms.mediapreview.VideoScrubPreviewView
import org.thoughtcrime.securesms.mediapreview.mediarail.AlbumScrubberView
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
 * Tellomi（tellomi/tellomi#1257）：相册 / 视频查看器在**真实会话页**里的判据与截图，以及「聊天里的相册仍是 Signal 原来的宫格」。
 * （owner 2026-09-25：聊天里的一行横滑撤回——会接住横向滑动、挡住返回；横滑留给以后的动态。）
 *
 * 用 [SignalActivityRule] 伪造一个已注册的本机和几个联系人（不连服务端、不建真账号），往数据库里插带真实图片数据的
 * 相册消息，再用正式入口（[ConversationIntents] → MainActivity）打开会话：断言几何与手势，顺手截图。
 * 只在给了 instrumentation 参数 `tellomiShots=1` 时跑，平时跳过（截图写文件、结果依赖屏幕尺寸）。
 *
 * 屏宽用 `adb shell wm size` + `wm density 480` 切（1 dp = 3 px）：402 → 1206x2622，440 → 1320x2868，375 → 1125x2001。
 * 截图与读数写在 `<app 外部文件目录>/tellomi-shots/<屏宽>dp/`。
 */
@RunWith(AndroidJUnit4::class)
class AlbumViewerScreenshots {

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

  /**
   * owner 2026-09-25：聊天里的一行横滑撤回，相册仍是 Signal 原来的宫格（最多露 5 格，多的在第 5 格上显示 +N）。
   * 判据：2 / 5 / 12 张都是宫格、露出的格数对、只有 12 张的有「+7」；会话里没有能横向滚动的视图；
   * 在相册上往右拖，照上游是滑动回复（不会被相册接走）。
   */
  @Test
  fun chatAlbumsUseTheOriginalGrid() {
    for ((index, count) in listOf(2, 5, 12).withIndex()) {
      val other = harness.others[index]
      val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
      insertIncomingAlbum(other, threadId, sizes(count, portraitOnly = false), body = null)

      val conversation = openConversation(other, threadId)
      try {
        waitForAlbums(conversation, 1)
        var dragY = 0f
        var dragFromX = 0f
        conversation.onActivity { activity ->
          val album = liveAlbums(activity).single()
          val cells = gridCells(album)
          val overflow = album.findViewById<TextView>(R.id.album_cell_overflow_text)
          val item = generateSequence(album.parent) { it.parent }.filterIsInstance<ConversationItem>().first()
          val scrollable = horizontallyScrollable(item)
          report.appendLine("grid count=$count cells=${cells.size} overflow=${overflow?.takeIf { it.isShown }?.text} horizontallyScrollable=${scrollable.map { it.javaClass.simpleName }}")
          assertEquals("$count 张：露出的格数", minOf(count, 5), cells.size)
          assertEquals("$count 张：超过 5 张才有 +N", if (count > 5) "+${count - 5}" else null, overflow?.takeIf { it.isShown }?.text?.toString())
          assertTrue("$count 张：会话里不能有横向滚动的视图（会挡住返回）", scrollable.isEmpty())
          dragY = screenY(album) + album.height / 2f
          dragFromX = screenX(album) + album.width * 0.2f
        }
        shot("albums-grid-$count")

        // 在相册上往右拖 110dp：照上游是滑动回复
        drag(dragFromX, dragY, dragFromX + 110f * density)
        settle(900)
        val replied = isReplyQuoteShown(conversation)
        report.appendLine("grid count=$count rightDragOnAlbumReplies=$replied")
        assertTrue("$count 张：在相册上往右拖是滑动回复（同上游）", replied)
      } finally {
        conversation.close()
      }
    }
    File(outDir, "metrics-grid.txt").writeText(report.toString())
  }

  /**
   * owner 2026-09-25 补充的查看器：在宫格里点第 3 张，查看器从第 3 张开始、四角按钮默认隐藏、底部缩略条在；
   * 轻点图片后按钮出现；在缩略条上往右拖，查看器跟着切到后面几张；转发「全部 12 张」是整组；关掉回到会话。
   */
  @Test
  fun viewerOpensAtTappedItemScrubsAndForwardsAll() {
    val other = harness.others[4]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    insertIncomingAlbum(other, threadId, sizes(12, portraitOnly = false), body = null)

    val conversation = openConversation(other, threadId)
    try {
      waitForAlbums(conversation, 1)
      conversation.onActivity { activity -> gridCells(liveAlbums(activity)[0])[2].performClick() }
      settle(2500)

      val viewer = resumedActivity()
      onMain {
        val toolbar = viewer.findViewById<View>(R.id.toolbar_layout)
        val scrubber = viewer.findViewById<AlbumScrubberView>(R.id.media_preview_album_scrubber)
        report.appendLine("viewer: opened toolbarShown=${chromeShown(toolbar)} scrubberShown=${chromeShown(scrubber)} selected=${scrubber.selectedIndexForTesting()}")
        // owner 2026-09-25（对照 Telegram）：打开时什么都不显示——四角按钮、缩略条都不在
        assertFalse("打开时四角按钮不显示", chromeShown(toolbar))
        assertFalse("打开时缩略条不显示", chromeShown(scrubber))
        assertEquals(2, scrubber.selectedIndexForTesting())
      }
      shot("viewer-1-opened-on-3")

      // 轻点图片：四角按钮和缩略条一起出现
      val width = harness.context.resources.displayMetrics.widthPixels.toFloat()
      val height = harness.context.resources.displayMetrics.heightPixels.toFloat()
      tap(width / 2f, height * 0.4f)
      settle(1200)
      var scrubberCenterY = 0f
      onMain {
        val toolbar = viewer.findViewById<View>(R.id.toolbar_layout)
        val scrubber = viewer.findViewById<AlbumScrubberView>(R.id.media_preview_album_scrubber)
        report.appendLine("viewer: afterTap toolbarShown=${chromeShown(toolbar)} scrubberShown=${chromeShown(scrubber)}")
        assertTrue("轻点后四角按钮出现", chromeShown(toolbar))
        assertTrue("轻点后缩略条出现", chromeShown(scrubber))
        scrubberCenterY = screenY(scrubber) + scrubber.paddingTop + 22f * density
      }
      shot("viewer-2-tapped-chrome-and-strip")

      // 在缩略条上从当前那张往右拖 90dp：指到哪张就是哪张
      drag(width / 2f, scrubberCenterY, width / 2f + 90f * density)
      settle(1200)
      var scrubbedTo = -1
      onMain {
        scrubbedTo = viewer.findViewById<AlbumScrubberView>(R.id.media_preview_album_scrubber).selectedIndexForTesting()
        report.appendLine("viewer: afterScrub selected=$scrubbedTo")
        assertTrue("拖缩略条能切到后面的图", scrubbedTo >= 3)
      }
      shot("viewer-3-scrubbed")

      // 转发：相册里的一张先问「这张 / 全部 N 张」；选「全部」后转发面板里是整组 12 张
      onMain { viewer.findViewById<View>(R.id.exo_forward).performClick() }
      settle(800)
      shot("viewer-3b-forward-choice")
      clickText(harness.context.resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_photos, 12, 12))
      settle(1500)
      onMain {
        val sheet = allFragments((viewer as FragmentActivity).supportFragmentManager).filterIsInstance<MultiselectForwardBottomSheet>().firstOrNull()
        val args = sheet?.arguments?.let { BundleCompat.getParcelable(it, MultiselectForwardFragment.ARGS, MultiselectForwardFragmentArgs::class.java) }
        val mediaCount = args?.multiShareArgs?.sumOf { it.media.size } ?: -1
        report.appendLine("viewer: forwardAll sheetShown=${sheet != null} mediaCount=$mediaCount")
        assertEquals("「全部 12 张」转发的是整组", 12, mediaCount)
      }
      shot("viewer-3c-forward-all-sheet")
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      settle(800)

      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      settle(2000)
      shot("viewer-4-closed")

      conversation.onActivity { activity ->
        report.appendLine("viewer: afterReturn albums=${liveAlbums(activity).size}")
        assertTrue("关掉回到会话，相册仍是宫格", liveAlbums(activity).isNotEmpty())
      }
    } finally {
      conversation.close()
    }

    File(outDir, "metrics-viewer.txt").writeText(report.toString())
  }

  /** owner 2026-09-25：只有一张图时，查看器底部不显示缩略条（也没有 k / N）。 */
  @Test
  fun singleImageViewerHasNoScrubber() {
    val other = harness.others[7]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    insertIncomingAlbum(other, threadId, sizes(1, portraitOnly = false), body = null)

    val conversation = openConversation(other, threadId)
    try {
      settle(2500)
      conversation.onActivity { activity ->
        val thumbnail = collectThumbnails(activity.window.decorView).first { it.isShown && it.width > 0 }
        thumbnail.performClick()
      }
      settle(2500)
      val viewer = resumedActivity()
      onMain {
        val scrubber = viewer.findViewById<AlbumScrubberView>(R.id.media_preview_album_scrubber)
        report.appendLine("single: scrubberVisibility=${scrubber.visibility}")
        assertEquals(View.GONE, scrubber.visibility)
      }
      shot("single-1-viewer-no-scrubber")
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      settle(1500)
    } finally {
      conversation.close()
    }

    File(outDir, "metrics-single.txt").writeText(report.toString())
  }

  /**
   * owner 2026-09-25（对照 Telegram）：查看器「···」里的「回复」回复的是正在看的那一张——回到会话后，
   * 输入框上方的引用是整条相册消息，缩略图是那一张（不是第一张）。
   */
  @Test
  fun viewerReplyQuotesTheItemOnScreen() {
    val other = harness.others[6]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val (sentAt, author) = insertIncomingAlbum(other, threadId, sizes(5, portraitOnly = false), body = null)
    val album = SignalDatabase.messages.getMessageFor(sentAt, author)!!
    val third = SignalDatabase.attachments.getAttachmentsForMessage(album.id).sortedBy { it.displayOrder }[2]

    val conversation = openConversation(other, threadId)
    try {
      waitForAlbums(conversation, 1)
      conversation.onActivity { activity -> gridCells(liveAlbums(activity)[0])[2].performClick() }
      settle(2500)

      val viewer = resumedActivity()
      val width = harness.context.resources.displayMetrics.widthPixels.toFloat()
      val height = harness.context.resources.displayMetrics.heightPixels.toFloat()
      tap(width / 2f, height * 0.4f)
      settle(1200)
      onMain {
        val toolbar = viewer.findViewById<Toolbar>(R.id.toolbar)
        val reply = toolbar.menu.findItem(R.id.reply)
        report.appendLine("reply: menuItemVisible=${reply?.isVisible}")
        assertTrue("从会话打开的相册，「···」里有回复", reply?.isVisible == true)
        toolbar.menu.performIdentifierAction(R.id.reply, 0)
      }
      settle(2500)

      var quotedId = 0L
      var quotedUri: Uri? = null
      conversation.onActivity { activity ->
        val quote = collectInputPanels(activity.window.decorView).first().quote.orElse(null)
        quotedId = quote?.id ?: 0L
        quotedUri = quote?.attachment?.uri
        report.appendLine("reply: quoteAttachment=${quote?.attachment?.javaClass?.simpleName} contentType=${quote?.attachment?.contentType} displayUri=${quote?.attachment?.displayUri} thumbnailUri=${quote?.attachment?.thumbnailUri}")
      }
      report.appendLine("reply: quoteShown=${isReplyQuoteShown(conversation)} quotedId=$quotedId expectedId=$sentAt quotedUri=$quotedUri expectedUri=${third.uri}")
      assertTrue("回到会话后输入框上方出现引用", isReplyQuoteShown(conversation))
      assertEquals("引用的是整条相册消息", sentAt, quotedId)
      assertEquals("引用缩略图是查看器里正在看的第 3 张", third.uri, quotedUri)
      shot("reply-1-quote-is-third-item")
    } finally {
      File(outDir, "metrics-reply.txt").writeText(report.toString())
      conversation.close()
    }
  }

  /**
   * owner 2026-09-25「多个视频点开时完全参考 Telegram 的设计」：两个视频的相册点开后——打开时什么都不显示，轻点后中间播放 / 暂停、
   * 进度胶囊（已播 / 总时长）、本组缩略条一起出现；倍速 1.5x 生效、齿轮上有角标；拖进度条时拇指上方出现那个位置的一帧、松手消失；
   * 转发 / 删除都问「这个视频 / 全部 2 个视频」；30 秒以内循环。翻到第二个（32 秒）：沿用 1.5x、两侧有 ±15、不循环；
   * 放完把控件叫出来、正中换成播放键（同 iOS 的 testVideoViewerTelegramControls）。
   */
  @Test
  fun videoViewerTelegramControls() {
    val other = harness.others[3]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    insertIncomingVideoAlbum(other, threadId, listOf(makeVideo(seconds = 6, hue = 200f), makeVideo(seconds = 32, hue = 20f, fps = 2)))

    val conversation = openConversation(other, threadId)
    try {
      waitForAlbums(conversation, 1)
      conversation.onActivity { activity -> gridCells(liveAlbums(activity)[0])[0].performClick() }
      settle(3000)

      val viewer = resumedActivity()
      val controls = viewer.findViewById<MediaPreviewPlayerControlView>(R.id.media_preview_playback_controls)
      val center = viewer.findViewById<MediaPreviewCenterControlsView>(R.id.media_preview_center_controls)
      onMain {
        report.appendLine("video: opened centerShown=${chromeShown(center)} toolbarShown=${chromeShown(viewer.findViewById(R.id.toolbar_layout))}")
        assertFalse("打开时中间的播放键不显示", chromeShown(center))
        assertFalse("打开时四角按钮不显示", chromeShown(viewer.findViewById(R.id.toolbar_layout)))
      }
      shot("video-1-opened")

      val width = harness.context.resources.displayMetrics.widthPixels.toFloat()
      val height = harness.context.resources.displayMetrics.heightPixels.toFloat()
      tap(width / 2f, height * 0.3f)
      settle(1200)
      onMain {
        val player = controls.player!!
        val (elapsed, total) = controls.timeLabelsForTesting()
        report.appendLine("video: afterTap centerShown=${chromeShown(center)} pause=${center.isShowingPauseForTesting()} elapsed=$elapsed total=$total repeatMode=${player.repeatMode} duration=${player.duration}")
        assertTrue("轻点后中间的播放键出现", chromeShown(center))
        // 设备上现编的视频实际时长约 6–7 秒（按投帧的真实时间），右边要等于总时长、不是剩余时间
        assertEquals("进度胶囊右边是总时长", MediaPreviewPlayerControlView.formatPlaybackTime(player.duration), total.toString())
        assertEquals("30 秒以内循环播放", Player.REPEAT_MODE_ONE, player.repeatMode)
      }
      shot("video-2-tapped-controls")

      // 倍速：面板里选 1.5x → 播放器 1.5 倍、齿轮角标 1.5x
      onMain { viewer.findViewById<View>(R.id.media_preview_speed_button).performClick() }
      settle(800)
      shot("video-3-speed-menu")
      onMain {
        val popupShown = controls.findViewById<View>(R.id.media_preview_speed_button).isShown
        report.appendLine("video: speedButtonShown=$popupShown")
      }
      clickText(harness.context.getString(R.string.MediaPreviewFragment__speed_normal))
      settle(400)
      onMain { viewer.findViewById<View>(R.id.media_preview_speed_button).performClick() }
      settle(800)
      clickText("1.5x")
      settle(600)
      onMain {
        val speed = controls.player!!.playbackParameters.speed
        report.appendLine("video: speed=$speed badge=${controls.speedBadgeForTesting()}")
        assertEquals(1.5f, speed, 0.001f)
        assertEquals("1.5x", controls.speedBadgeForTesting()?.toString())
      }

      // 拖进度条：按住、拖到 60%，停住等预览帧；松手后预览消失
      var barLeft = 0f
      var barRight = 0f
      var barY = 0f
      onMain {
        val bar = controls.timeBarForTesting()
        val location = IntArray(2).also { bar.getLocationOnScreen(it) }
        barLeft = location[0].toFloat()
        barRight = (location[0] + bar.width).toFloat()
        barY = location[1] + bar.height / 2f
      }
      val down = SystemClock.uptimeMillis()
      val startX = barLeft + (barRight - barLeft) * 0.2f
      instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, startX, barY, 0))
      for (step in 1..10) {
        val x = startX + (barRight - barLeft) * 0.4f * step / 10f
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + step * 30L, MotionEvent.ACTION_MOVE, x, barY, 0))
      }
      settle(1500)
      val preview = viewer.findViewById<VideoScrubPreviewView>(R.id.media_preview_scrub_preview)
      onMain {
        report.appendLine("video: scrubbing previewHasFrame=${preview.hasFrameForTesting()} previewTop=${IntArray(2).also { preview.getLocationOnScreen(it) }[1]} barY=$barY")
        assertTrue("拖动时拇指上方有那个位置的一帧", preview.hasFrameForTesting())
      }
      shot("video-4-scrub-preview")
      val endX = startX + (barRight - barLeft) * 0.4f
      instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, endX, barY, 0))
      settle(800)
      onMain {
        report.appendLine("video: afterRelease previewVisible=${preview.visibility == View.VISIBLE}")
        assertEquals("松手后预览消失", View.GONE, preview.visibility)
      }

      // 转发 / 删除：先问「这个视频 / 全部 2 个视频」
      onMain { viewer.findViewById<View>(R.id.exo_forward).performClick() }
      settle(800)
      assertTrue(isTextOnScreen(harness.context.resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_videos, 2, 2)))
      shot("video-5-forward-choice")
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      settle(600)
      onMain { viewer.findViewById<View>(R.id.media_preview_delete_button).performClick() }
      settle(800)
      assertTrue(isTextOnScreen(harness.context.resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_videos, 2, 2)))
      shot("video-6-delete-choice")
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      settle(600)

      // 翻到 32 秒的那个：沿用 1.5x、两侧有 ±15、不循环、右边是它的总时长
      onMain { viewer.findViewById<ViewPager2>(R.id.media_pager).setCurrentItem(1, false) }
      settle(2500)
      onMain {
        val player = controls.player!!
        val (_, total) = controls.timeLabelsForTesting()
        report.appendLine("video: long speed=${player.playbackParameters.speed} skip=${center.showsSkipButtonsForTesting()} total=$total duration=${player.duration} repeatMode=${player.repeatMode}")
        assertEquals("翻到下一个视频沿用倍速", 1.5f, player.playbackParameters.speed, 0.001f)
        assertTrue("30 秒以上两侧有 ±15", center.showsSkipButtonsForTesting())
        assertEquals("进度胶囊右边是总时长", MediaPreviewPlayerControlView.formatPlaybackTime(player.duration), total.toString())
        assertEquals("30 秒以上不循环", Player.REPEAT_MODE_OFF, player.repeatMode)
      }

      // 放完：把控件叫出来，正中是播放键
      var toolbarShown = false
      onMain { toolbarShown = chromeShown(viewer.findViewById(R.id.toolbar_layout)) }
      if (toolbarShown) {
        tap(width / 2f, height * 0.3f)
        settle(1000)
      }
      onMain {
        report.appendLine("video: beforeEnd toolbarShown=${chromeShown(viewer.findViewById(R.id.toolbar_layout))} centerShown=${chromeShown(center)}")
        assertFalse("放完之前先收起控件", chromeShown(viewer.findViewById(R.id.toolbar_layout)))
        val player = controls.player!!
        player.seekTo(player.duration - 1200)
        player.play()
      }
      var chromeBack = false
      for (attempt in 0 until 50) {
        settle(100)
        onMain { chromeBack = chromeShown(center) && chromeShown(viewer.findViewById(R.id.toolbar_layout)) }
        if (chromeBack) break
      }
      settle(500)
      onMain {
        report.appendLine("video: ended chromeBack=$chromeBack pause=${center.isShowingPauseForTesting()} state=${controls.player?.playbackState}")
        assertTrue("放完把控件叫出来", chromeBack)
        assertFalse("正中换成播放键", center.isShowingPauseForTesting())
      }
      shot("video-7-long-ended")
    } finally {
      File(outDir, "metrics-video.txt").writeText(report.toString())
      conversation.close()
    }
  }

  // ---- 读数 ------------------------------------------------------------------------------

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

  /** 视图真的在屏幕上可见（自己和所有上层都可见、不透明度不为 0）。 */
  private fun chromeShown(view: View): Boolean {
    var v: View? = view
    while (v != null) {
      if (v.visibility != View.VISIBLE || v.alpha == 0f) return false
      v = v.parent as? View
    }
    return true
  }

  private fun tap(x: Float, y: Float) {
    val down = SystemClock.uptimeMillis()
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0))
  }

  private fun onMain(block: () -> Unit) {
    var error: Throwable? = null
    instrumentation.runOnMainSync {
      try {
        block()
      } catch (t: Throwable) {
        error = t
      }
    }
    error?.let { throw it }
  }

  /** 当前在前台的 Activity（查看器是另一个 Activity）。 */
  private fun resumedActivity(): Activity {
    var activity: Activity? = null
    instrumentation.runOnMainSync {
      activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    return activity!!
  }

  private fun isTextOnScreen(text: String): Boolean {
    return instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true
  }

  /**
   * 在设备上现编一个 H.264 小视频（每秒换一个颜色、画上秒数），[seconds] 秒、10 fps。输入走编码器的 Surface，
   * 时间戳按投帧的真实时间，所以每帧之间等 1/10 秒。
   */
  private fun makeVideo(seconds: Int, hue: Float, width: Int = 320, height: Int = 568, fps: Int = 10): ByteArray {
    val file = File.createTempFile("tellomi-video", ".mp4", harness.context.cacheDir)
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, 800_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val surface = codec.createInputSurface()
    codec.start()
    val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var track = -1
    val info = MediaCodec.BufferInfo()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textAlign = Paint.Align.CENTER
      textSize = width * 0.5f
    }

    fun drain(endOfStream: Boolean) {
      while (true) {
        val index = codec.dequeueOutputBuffer(info, 10_000)
        when {
          index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
          index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            track = muxer.addTrack(codec.outputFormat)
            muxer.start()
          }
          index >= 0 -> {
            val buffer = codec.getOutputBuffer(index)!!
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
            if (info.size > 0 && track >= 0) {
              buffer.position(info.offset)
              buffer.limit(info.offset + info.size)
              muxer.writeSampleData(track, buffer, info)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
          }
        }
      }
    }

    for (frame in 0 until seconds * fps) {
      val second = frame / fps
      val canvas = surface.lockHardwareCanvas()
      canvas.drawColor(Color.HSVToColor(floatArrayOf((hue + second * 40f) % 360f, 0.6f, 0.85f)))
      canvas.drawText("${second + 1}", width / 2f, height / 2f + paint.textSize * 0.35f, paint)
      surface.unlockCanvasAndPost(canvas)
      drain(endOfStream = false)
      SystemClock.sleep(1000L / fps)
    }
    codec.signalEndOfInputStream()
    drain(endOfStream = true)
    codec.stop()
    codec.release()
    surface.release()
    muxer.stop()
    muxer.release()
    return file.readBytes().also { file.delete() }
  }

  private fun insertIncomingVideoAlbum(from: RecipientId, threadId: Long, videos: List<ByteArray>) {
    val now = System.currentTimeMillis()
    val pointers = videos.map { videoPointer(320, 568) }
    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = from,
      sentTimeMillis = now,
      serverTimeMillis = now,
      receivedTimeMillis = now,
      body = null,
      attachments = PointerAttachment.forPointers(Optional.of(pointers))
    )
    val insert = SignalDatabase.messages.insertMessageInbox(message, threadId).get()
    SignalDatabase.attachments.getAttachmentsForMessage(insert.messageId).sortedBy { it.displayOrder }.forEachIndexed { i, attachment ->
      SignalDatabase.attachments.finalizeAttachmentAfterDownload(insert.messageId, attachment.attachmentId, ByteArrayInputStream(videos[i]))
    }
    SystemClock.sleep(5)
  }

  private fun videoPointer(width: Int, height: Int): SignalServiceAttachmentPointer {
    return SignalServiceAttachmentPointer(
      Cdn.CDN_3.cdnNumber,
      SignalServiceAttachmentRemoteId.from("", Cdn.CDN_3.cdnNumber),
      "video/mp4",
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

  private fun allFragments(manager: FragmentManager): List<Fragment> {
    return manager.fragments.flatMap { listOf(it) + allFragments(it.childFragmentManager) }
  }

  /** 按文字点一下（对话框的列表项在另一个窗口里，走无障碍节点点它可点的那一层）。 */
  private fun clickText(text: String) {
    val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
    assertTrue("屏幕上找不到「$text」", node != null)
    var target = node
    while (target != null && !target.isClickable) {
      target = target.parent
    }
    assertTrue("「$text」没有可点的一层", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
  }

  private fun collectInputPanels(view: View): List<InputPanel> {
    if (view is InputPanel) return listOf(view)
    if (view !is ViewGroup) return emptyList()
    return (0 until view.childCount).flatMap { collectInputPanels(view.getChildAt(it)) }
  }

  private fun collectThumbnails(view: View): List<ThumbnailView> {
    if (view is ThumbnailView) return listOf(view)
    if (view !is ViewGroup) return emptyList()
    return (0 until view.childCount).flatMap { collectThumbnails(view.getChildAt(it)) }
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

  private fun waitForAlbums(conversation: OpenedConversation, expected: Int) {
    val deadline = SystemClock.uptimeMillis() + 20_000
    var found = 0
    while (SystemClock.uptimeMillis() < deadline && found < expected) {
      conversation.onActivity { activity -> found = liveAlbums(activity).size }
      if (found < expected) SystemClock.sleep(250)
    }
    settle(1500)
  }

  /** 会话列表进来后还会重绑几次（标已读等），每次都重新找当前在屏上的相册宫格。 */
  private fun liveAlbums(activity: Activity): List<AlbumThumbnailView> {
    return collectAlbums(activity.window.decorView)
      .filter { it.visibility == View.VISIBLE && it.isAttachedToWindow && it.height > 0 }
      .sortedBy { screenY(it) }
  }

  private fun collectAlbums(view: View): List<AlbumThumbnailView> {
    if (view is AlbumThumbnailView) return listOf(view)
    if (view !is ViewGroup) return emptyList()
    return (0 until view.childCount).flatMap { collectAlbums(view.getChildAt(it)) }
  }

  /** 宫格里露出来的格子，按 album_cell_1…5 的顺序。 */
  private fun gridCells(album: AlbumThumbnailView): List<ThumbnailView> {
    return listOf(R.id.album_cell_1, R.id.album_cell_2, R.id.album_cell_3, R.id.album_cell_4, R.id.album_cell_5)
      .mapNotNull { album.findViewById<ThumbnailView>(it) }
      .filter { it.isShown }
  }

  /** 能横向滚动的视图（往左或往右还能滚）。 */
  private fun horizontallyScrollable(view: View): List<View> {
    val self = if (view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)) listOf(view) else emptyList()
    if (view !is ViewGroup) return self
    return self + (0 until view.childCount).flatMap { horizontallyScrollable(view.getChildAt(it)) }
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
