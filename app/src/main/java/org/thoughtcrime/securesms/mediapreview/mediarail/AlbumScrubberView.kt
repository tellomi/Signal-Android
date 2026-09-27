/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview.mediarail

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.bumptech.glide.RequestManager
import org.signal.core.models.media.Media
import org.signal.core.util.AccessibilityUtil
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ThumbnailView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tellomi（tellomi/tellomi#1257，owner 2026-09-25 补充）：查看器底部「本组缩略条 + k / N」。
 *
 * - 当前那张展开成方块、略大；其余是窄竖条（和 iOS「照片」、抖音图文的底部条同一种做法）。
 * - 手指按住就选中指下那张；来回拖动时指到哪张就是哪张，每换一张轻触感一次；靠近两端自动往那边滚（32 张也拖得到）。
 * - 拖动时条本身不跟着居中（否则指下的那张会被挪走），松手后才把当前那张滚到中间。
 * - 放在查看器底栏里（说明与按钮之间），和四角按钮一起显隐：owner 2026-09-25 对照 Telegram 定「打开时什么都不显示，轻点才出现」。
 */
class AlbumScrubberView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

  companion object {
    private const val COLLAPSED_WIDTH_DP = 30f
    private const val EXPANDED_WIDTH_DP = 44f
    private const val HEIGHT_DP = 44f
    private const val GAP_DP = 4f
    private const val CORNER_DP = 6f
    private const val EDGE_ZONE_DP = 40f
    private const val MAX_AUTO_SCROLL_DP_PER_FRAME = 14f
    private const val RESIZE_DURATION_MS = 160L
  }

  /** 选中（点或拖）某一张时回调：下标是本组里的位置。 */
  var onItemSelected: ((Int) -> Unit)? = null

  private val scroller = ScrubStrip(context)
  private val strip = LinearLayout(context)
  private val counter = TextView(context)

  private val collapsedWidth = dp(COLLAPSED_WIDTH_DP)
  private val expandedWidth = dp(EXPANDED_WIDTH_DP)
  private val itemHeight = dp(HEIGHT_DP)
  private val gap = dp(GAP_DP)
  private val cornerRadius = dp(CORNER_DP)
  private val edgeZone = dp(EDGE_ZONE_DP)
  private val maxAutoScroll = dp(MAX_AUTO_SCROLL_DP_PER_FRAME)

  private var media: List<Media> = emptyList()
  private var selectedIndex = -1
  private var scrubbing = false
  private var lastTouchX = 0f

  init {
    orientation = VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    clipChildren = false
    clipToPadding = false

    strip.orientation = HORIZONTAL
    strip.gravity = Gravity.CENTER
    strip.clipChildren = false
    strip.clipToPadding = false
    strip.setPadding(dp(16f), 0, dp(16f), 0)

    scroller.isHorizontalScrollBarEnabled = false
    scroller.overScrollMode = OVER_SCROLL_NEVER
    scroller.isFillViewport = true
    scroller.clipChildren = false
    scroller.addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, itemHeight))
    addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, itemHeight))

    counter.setTextColor(Color.WHITE)
    counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    counter.gravity = Gravity.CENTER
    counter.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    addView(counter, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
  }

  /** 换一组（或同一组里换了当前那张）。 */
  fun setItems(requestManager: RequestManager, items: List<Media>, selected: Int) {
    val sameGroup = items.size == media.size && items.indices.all { items[it].uri == media[it].uri }

    if (!sameGroup) {
      media = items
      selectedIndex = -1
      strip.removeAllViews()
      items.forEachIndexed { index, item ->
        val thumbnail = ThumbnailView(context)
        thumbnail.setRadii(cornerRadius, cornerRadius, cornerRadius, cornerRadius)
        thumbnail.contentDescription = context.getString(R.string.AlbumCarouselView__item_d_of_d, index + 1, items.size)
        thumbnail.isFocusable = true
        thumbnail.setOnClickListener { select(index, fromUser = true) }
        strip.addView(thumbnail, LayoutParams(collapsedWidth, itemHeight).apply { marginStart = gap / 2; marginEnd = gap / 2 })
        thumbnail.setImageResource(requestManager, item.uri, expandedWidth, itemHeight, false, null)
      }
    }

    setSelected(selected, animate = sameGroup)
  }

  /** 查看器翻页时同步（不回调、不震动）。 */
  fun setSelected(index: Int, animate: Boolean) {
    if (index !in media.indices || index == selectedIndex) {
      updateCounter()
      return
    }

    val previous = selectedIndex
    selectedIndex = index
    resize(previous, collapsedWidth, animate)
    resize(index, expandedWidth, animate)
    for (i in 0 until strip.childCount) {
      strip.getChildAt(i).isSelected = i == index
    }
    updateCounter()

    if (!scrubbing) {
      post { centerOnSelected(animate) }
    }
  }

  @VisibleForTesting
  fun selectedIndexForTesting(): Int = selectedIndex

  private fun select(index: Int, fromUser: Boolean) {
    if (index == selectedIndex || index !in media.indices) return
    setSelected(index, animate = true)
    if (fromUser) {
      performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
      onItemSelected?.invoke(index)
    }
  }

  private fun updateCounter() {
    if (media.isEmpty() || selectedIndex < 0) {
      counter.text = ""
      return
    }
    val current = (selectedIndex + 1).toString()
    val text = SpannableStringBuilder(current).append("  /  ").append(media.size.toString())
    text.setSpan(ForegroundColorSpan(0x99FFFFFF.toInt()), current.length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    counter.text = text
  }

  private fun resize(index: Int, width: Int, animate: Boolean) {
    val child = strip.getChildAt(index) ?: return
    val params = child.layoutParams
    if (params.width == width) return

    if (!animate || AccessibilityUtil.areAnimationsDisabled(context)) {
      params.width = width
      child.layoutParams = params
      return
    }

    ValueAnimator.ofInt(params.width, width).apply {
      duration = RESIZE_DURATION_MS
      interpolator = DecelerateInterpolator()
      addUpdateListener {
        params.width = it.animatedValue as Int
        child.layoutParams = params
      }
      start()
    }
  }

  private fun centerOnSelected(animate: Boolean) {
    val child = strip.getChildAt(selectedIndex) ?: return
    val childCenter = child.left + expandedWidth / 2
    val target = (childCenter - scroller.width / 2).coerceAtLeast(0)
    if (animate && !AccessibilityUtil.areAnimationsDisabled(context)) {
      scroller.smoothScrollTo(target, 0)
    } else {
      scroller.scrollTo(target, 0)
    }
  }

  /** 指下那一张（按条里的实际位置算，含两侧间隙）；没有返回 -1。 */
  private fun indexUnder(xInScroller: Float): Int {
    val x = xInScroller + scroller.scrollX
    for (i in 0 until strip.childCount) {
      val child = strip.getChildAt(i)
      if (x >= child.left - gap / 2f && x < child.right + gap / 2f) return i
    }
    return when {
      strip.childCount == 0 -> -1
      x < strip.getChildAt(0).left -> 0
      else -> strip.childCount - 1
    }
  }

  private val autoScroll = object : Runnable {
    override fun run() {
      if (!scrubbing) return
      val speed = when {
        lastTouchX < edgeZone -> -((edgeZone - lastTouchX) / edgeZone * maxAutoScroll)
        lastTouchX > scroller.width - edgeZone -> (lastTouchX - (scroller.width - edgeZone)) / edgeZone * maxAutoScroll
        else -> 0f
      }
      if (abs(speed) >= 1f && scroller.canScrollHorizontally(if (speed > 0) 1 else -1)) {
        scroller.scrollBy(speed.roundToInt(), 0)
        val index = indexUnder(lastTouchX)
        if (index >= 0) select(index, fromUser = true)
      }
      postOnAnimation(this)
    }
  }

  private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

  /** 按住即接管：不让查看器的翻页和条自己的惯性滚动抢这次拖动。 */
  private inner class ScrubStrip(context: Context) : HorizontalScrollView(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = media.size > 1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
      if (media.size <= 1) return false

      lastTouchX = ev.x
      when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          parent.requestDisallowInterceptTouchEvent(true)
          scrubbing = true
          val index = indexUnder(ev.x)
          if (index >= 0) select(index, fromUser = true)
          postOnAnimation(autoScroll)
        }
        MotionEvent.ACTION_MOVE -> {
          val index = indexUnder(ev.x)
          if (index >= 0) select(index, fromUser = true)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          scrubbing = false
          removeCallbacks(autoScroll)
          post { centerOnSelected(animate = true) }
        }
      }
      return true
    }
  }

  override fun onDetachedFromWindow() {
    scrubbing = false
    removeCallbacks(autoScroll)
    super.onDetachedFromWindow()
  }
}
