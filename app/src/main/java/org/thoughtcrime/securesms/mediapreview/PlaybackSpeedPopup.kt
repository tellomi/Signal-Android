/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.slider.Slider
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.ViewUtil
import kotlin.math.abs
import kotlin.math.round

/**
 * Tellomi（tellomi/tellomi#1257，owner 2026-09-25「完全参考 Telegram 的设计」）：视频查看器的倍速面板。
 * 上面一行「速度」+ 连续滑杆（0.2x–2.5x，步长 0.1，拖动即生效），下面是 0.5x / 正常 / 1.5x / 2x 四个预设（当前项打勾，点了就关）。
 * 只在这次查看器里生效、翻到下一个视频也沿用（照 Telegram，不写全局设置）。独立实现：面板、图标都是 Signal 自己的资源。
 */
class PlaybackSpeedPopup(
  context: Context,
  private var currentSpeed: Float,
  private val onSpeedChanged: (Float) -> Unit
) : PopupWindow() {

  companion object {
    const val MIN_SPEED = 0.2f
    const val MAX_SPEED = 2.5f
    val PRESETS = listOf(0.5f, 1f, 1.5f, 2f)

    fun snap(speed: Float): Float = (round(speed.coerceIn(MIN_SPEED, MAX_SPEED) * 10f) / 10f)
  }

  private val themed = ContextThemeWrapper(context, com.google.android.material.R.style.Theme_Material3_Dark)
  private val valueLabel = TextView(themed)
  private val slider = Slider(themed)
  private val presetRows = mutableListOf<Pair<Float, ImageView>>()

  init {
    val root = LinearLayout(themed).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, ViewUtil.dpToPx(8), 0, ViewUtil.dpToPx(8))
      background = GradientDrawable().apply {
        setColor(Color.parseColor("#F2202022"))
        cornerRadius = ViewUtil.dpToPx(14).toFloat()
      }
    }

    val header = LinearLayout(themed).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(ViewUtil.dpToPx(16), ViewUtil.dpToPx(6), ViewUtil.dpToPx(16), 0)
    }
    header.addView(
      label(themed.getString(R.string.MediaPreviewFragment__speed)),
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    valueLabel.setTextColor(Color.WHITE)
    valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    header.addView(valueLabel)
    root.addView(header)

    slider.valueFrom = MIN_SPEED
    slider.valueTo = MAX_SPEED
    slider.stepSize = 0.1f
    slider.value = snap(currentSpeed)
    slider.setLabelFormatter { MediaPreviewPlayerControlView.formatPlaybackSpeed(it) }
    // 白色滑块、灰色轨道、不画刻度点：和查看器里其它白色控件一致（不用 Material 默认的主题色）
    slider.trackActiveTintList = ColorStateList.valueOf(Color.WHITE)
    slider.trackInactiveTintList = ColorStateList.valueOf(Color.parseColor("#4DFFFFFF"))
    slider.thumbTintList = ColorStateList.valueOf(Color.WHITE)
    slider.haloTintList = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
    slider.isTickVisible = false
    slider.contentDescription = themed.getString(R.string.MediaPreviewFragment__playback_speed)
    slider.addOnChangeListener { _, value, fromUser ->
      if (fromUser) apply(value, dismiss = false)
    }
    val sliderParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    sliderParams.marginStart = ViewUtil.dpToPx(4)
    sliderParams.marginEnd = ViewUtil.dpToPx(4)
    root.addView(slider, sliderParams)

    root.addView(View(themed).apply { setBackgroundColor(Color.parseColor("#33FFFFFF")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

    for (preset in PRESETS) {
      val row = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ViewUtil.dpToPx(44)
        setPadding(ViewUtil.dpToPx(16), 0, ViewUtil.dpToPx(16), 0)
        isClickable = true
        isFocusable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
      }
      val check = ImageView(themed).apply {
        setImageResource(R.drawable.symbol_check_white_24)
        setColorFilter(Color.WHITE)
      }
      row.addView(check, LinearLayout.LayoutParams(ViewUtil.dpToPx(20), ViewUtil.dpToPx(20)).apply { marginEnd = ViewUtil.dpToPx(12) })
      val text = if (preset == 1f) themed.getString(R.string.MediaPreviewFragment__speed_normal) else MediaPreviewPlayerControlView.formatPlaybackSpeed(preset)
      row.addView(label(text))
      row.setOnClickListener { apply(preset, dismiss = true) }
      root.addView(row)
      presetRows += preset to check
    }

    contentView = root
    width = ViewUtil.dpToPx(240)
    height = ViewGroup.LayoutParams.WRAP_CONTENT
    isFocusable = true
    isOutsideTouchable = true
    elevation = ViewUtil.dpToPx(8).toFloat()
    refresh()
  }

  private fun label(text: String): TextView = TextView(themed).apply {
    this.text = text
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
  }

  private fun apply(speed: Float, dismiss: Boolean) {
    currentSpeed = snap(speed)
    onSpeedChanged(currentSpeed)
    refresh()
    if (dismiss) dismiss()
  }

  private fun refresh() {
    valueLabel.text = MediaPreviewPlayerControlView.formatPlaybackSpeed(currentSpeed)
    if (abs(slider.value - currentSpeed) > 0.001f) slider.value = currentSpeed
    for ((preset, check) in presetRows) {
      check.visibility = if (abs(preset - currentSpeed) < 0.001f) View.VISIBLE else View.INVISIBLE
    }
  }

  /** 在 [anchor]（齿轮）上方居中弹出。 */
  fun showAbove(anchor: View) {
    val content = contentView
    content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
    val location = IntArray(2).also { anchor.getLocationInWindow(it) }
    val x = location[0] + anchor.width / 2 - width / 2
    val y = location[1] - content.measuredHeight - ViewUtil.dpToPx(10)
    showAtLocation(anchor, Gravity.NO_GRAVITY, x.coerceAtLeast(ViewUtil.dpToPx(8)), y.coerceAtLeast(0))
  }

  @VisibleForTesting
  fun clickPresetForTesting(speed: Float) {
    apply(speed, dismiss = true)
  }
}
