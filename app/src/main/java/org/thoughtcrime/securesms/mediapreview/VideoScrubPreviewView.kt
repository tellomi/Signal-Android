/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.scale
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.util.ViewUtil
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tellomi（tellomi/tellomi#1257，owner 2026-09-25）：拖视频进度条时，拇指上方显示拖到的那一帧——只在拖动时出现，松手淡出
 * （照 Telegram：视频本身松手才跳过去，拖动中不暂停）。长边 150dp、圆角 6dp，离屏幕两边至少 10dp，在进度胶囊上方 6dp。
 */
class VideoScrubPreviewView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

  companion object {
    const val LONG_SIDE_DP = 150
    private const val EDGE_MARGIN_DP = 10
    private const val GAP_ABOVE_PILL_DP = 6
    private const val FADE_MS = 140L
  }

  private val longSide = ViewUtil.dpToPx(LONG_SIDE_DP)
  private val edgeMargin = ViewUtil.dpToPx(EDGE_MARGIN_DP)
  private val gapAbovePill = ViewUtil.dpToPx(GAP_ABOVE_PILL_DP)
  private var lastCenterX = 0f
  private var lastPillTop = 0f

  init {
    scaleType = ScaleType.CENTER_CROP
    setBackgroundColor(Color.BLACK)
    val radius = ViewUtil.dpToPx(6).toFloat()
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
      }
    }
    clipToOutline = true
    visibility = GONE
    alpha = 0f
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  /** 按住进度条：出现在拇指上方（还没有帧时先按 16:9 占位）。 */
  fun showAt(centerXOnScreen: Float, pillTopOnScreen: Float) {
    if (visibility != VISIBLE) {
      if (width == 0) setFrameSize(16f / 9f)
      visibility = VISIBLE
      animate().cancel()
      animate().alpha(1f).setDuration(FADE_MS).start()
    }
    moveTo(centerXOnScreen, pillTopOnScreen)
  }

  fun moveTo(centerXOnScreen: Float, pillTopOnScreen: Float) {
    lastCenterX = centerXOnScreen
    lastPillTop = pillTopOnScreen
    val parentView = parent as? View ?: return
    val parentLocation = IntArray(2).also { parentView.getLocationOnScreen(it) }
    val w = layoutParams?.width?.takeIf { it > 0 } ?: width
    val h = layoutParams?.height?.takeIf { it > 0 } ?: height
    val maxX = (parentView.width - edgeMargin - w).coerceAtLeast(edgeMargin)
    translationX = (centerXOnScreen - parentLocation[0] - w / 2f).coerceIn(edgeMargin.toFloat(), maxX.toFloat())
    translationY = pillTopOnScreen - parentLocation[1] - gapAbovePill - h
  }

  /** 解出来的帧（按它的宽高比调整大小）。 */
  fun setFrame(bitmap: Bitmap) {
    setFrameSize(bitmap.width.toFloat() / max(1, bitmap.height).toFloat())
    setImageBitmap(bitmap)
    moveTo(lastCenterX, lastPillTop)
  }

  fun dismiss() {
    if (visibility != VISIBLE) return
    animate().cancel()
    animate().alpha(0f).setDuration(FADE_MS).withEndAction {
      visibility = GONE
      setImageDrawable(null)
    }.start()
  }

  private fun setFrameSize(aspectRatio: Float) {
    val params = layoutParams ?: return
    if (aspectRatio >= 1f) {
      params.width = longSide
      params.height = (longSide / aspectRatio).roundToInt()
    } else {
      params.width = (longSide * aspectRatio).roundToInt()
      params.height = longSide
    }
    layoutParams = params
  }

  @VisibleForTesting
  fun hasFrameForTesting(): Boolean = drawable != null && visibility == VISIBLE
}

/**
 * 从本地加密附件解某个时间点的关键帧（MediaMetadataRetriever + EncryptedMediaDataSource，不落明文文件）。
 * 一个后台线程；只做最新的请求——拖得快时中间的位置直接跳过。
 */
class VideoFrameExtractor(private val attachmentId: AttachmentId, private val longSidePx: Int) {

  companion object {
    private val TAG = Log.tag(VideoFrameExtractor::class.java)
  }

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val latestRequest = AtomicLong(-1)
  private var retriever: MediaMetadataRetriever? = null

  fun request(positionMs: Long, onFrame: (Bitmap) -> Unit) {
    latestRequest.set(positionMs)
    executor.execute {
      if (latestRequest.get() != positionMs) return@execute
      val frame = frameAt(positionMs) ?: return@execute
      mainHandler.post {
        if (latestRequest.get() == positionMs) onFrame(frame)
      }
    }
  }

  fun release() {
    latestRequest.set(-1)
    executor.execute {
      try {
        retriever?.release()
      } catch (e: Exception) {
        Log.w(TAG, "release failed", e)
      }
      retriever = null
    }
    executor.shutdown()
  }

  private fun frameAt(positionMs: Long): Bitmap? {
    return try {
      val retriever = retriever ?: MediaMetadataRetriever().also { newRetriever ->
        val source = SignalDatabase.attachments.mediaDataSourceFor(attachmentId, false) ?: return null
        newRetriever.setDataSource(source)
        retriever = newRetriever
      }
      val timeUs = positionMs * 1000
      if (Build.VERSION.SDK_INT >= 27) {
        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, longSidePx, longSidePx)
      } else {
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
          val scale = longSidePx.toFloat() / max(full.width, full.height).toFloat()
          if (scale < 1f) full.scale((full.width * scale).roundToInt(), (full.height * scale).roundToInt()) else full
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not extract a frame at $positionMs ms", e)
      null
    }
  }
}
