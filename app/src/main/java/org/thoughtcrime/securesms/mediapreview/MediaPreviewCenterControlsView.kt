/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.ViewUtil
import androidx.media3.ui.R as Media3R

/**
 * Tellomi（tellomi/tellomi#1257，owner 2026-09-25「多个视频点开时完全参考 Telegram 的设计」）：视频查看器屏幕中间的播放 / 暂停，
 * 30 秒以上的视频两侧再有 −15 / +15 秒（Telegram 同样只给长视频）。跟四角按钮一起显隐（由查看器的全屏切换带着走）。
 * 独立实现：按钮、图标都是 Signal 自己的资源。
 */
@OptIn(UnstableApi::class)
class MediaPreviewCenterControlsView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

  companion object {
    private const val SKIP_MS = 15_000L
    private const val MIN_DURATION_FOR_SKIP_MS = 30_000L
  }

  private val row = LinearLayout(context)
  private val rewindButton = ImageButton(context)
  private val playPauseButton = ImageButton(context)
  private val fastForwardButton = ImageButton(context)

  private var player: Player? = null

  private val listener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
      update()
    }
  }

  init {
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

    val big = ViewUtil.dpToPx(76)
    val small = ViewUtil.dpToPx(56)
    val gap = ViewUtil.dpToPx(28)

    setUpButton(rewindButton, R.drawable.symbol_skip_back_15_24, Media3R.string.exo_controls_rewind_description)
    setUpButton(playPauseButton, R.drawable.play_button_solid_24, Media3R.string.exo_controls_play_description)
    setUpButton(fastForwardButton, R.drawable.symbol_skip_forward_15_24, Media3R.string.exo_controls_fastforward_description)

    row.addView(rewindButton, LinearLayout.LayoutParams(small, small).apply { marginEnd = gap })
    row.addView(playPauseButton, LinearLayout.LayoutParams(big, big))
    row.addView(fastForwardButton, LinearLayout.LayoutParams(small, small).apply { marginStart = gap })

    playPauseButton.setOnClickListener { togglePlayback() }
    rewindButton.setOnClickListener { seekBy(-SKIP_MS) }
    fastForwardButton.setOnClickListener { seekBy(SKIP_MS) }
    update()
  }

  private fun setUpButton(button: ImageButton, icon: Int, description: Int) {
    button.setBackgroundResource(R.drawable.media_preview_round_button_background)
    button.setImageResource(icon)
    button.setColorFilter(android.graphics.Color.WHITE)
    button.contentDescription = context.getString(description)
  }

  /** 图片时整组隐藏（本视图仍交给全屏切换带着显隐，所以只藏里面那一行）。 */
  fun setIsVideo(isVideo: Boolean) {
    row.visibility = if (isVideo) VISIBLE else GONE
  }

  /** 换成当前这个视频的播放器（翻页时查看器会重新绑）。 */
  fun bind(player: Player?) {
    if (this.player === player) {
      update()
      return
    }
    this.player?.removeListener(listener)
    this.player = player
    player?.addListener(listener)
    update()
  }

  private fun togglePlayback() {
    val player = player ?: return
    if (player.isPlaying) {
      player.pause()
    } else {
      if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0)
      }
      player.play()
    }
  }

  private fun seekBy(deltaMs: Long) {
    val player = player ?: return
    val duration = player.duration.takeIf { it > 0 } ?: return
    player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
  }

  private fun update() {
    val player = player
    val playing = player?.isPlaying == true || (player?.playWhenReady == true && player.playbackState == Player.STATE_BUFFERING)
    playPauseButton.setImageResource(if (playing) R.drawable.pause_solid_24 else R.drawable.play_button_solid_24)
    playPauseButton.contentDescription = context.getString(if (playing) Media3R.string.exo_controls_pause_description else Media3R.string.exo_controls_play_description)

    val showSkip = (player?.duration ?: 0L) > MIN_DURATION_FOR_SKIP_MS
    rewindButton.visibility = if (showSkip) VISIBLE else GONE
    fastForwardButton.visibility = if (showSkip) VISIBLE else GONE
  }

  override fun onDetachedFromWindow() {
    player?.removeListener(listener)
    player = null
    super.onDetachedFromWindow()
  }

  @VisibleForTesting
  fun isShowingPauseForTesting(): Boolean = playPauseButton.contentDescription == context.getString(Media3R.string.exo_controls_pause_description)

  @VisibleForTesting
  fun playPauseButtonForTesting(): ImageButton = playPauseButton

  @VisibleForTesting
  fun showsSkipButtonsForTesting(): Boolean = rewindButton.visibility == VISIBLE && fastForwardButton.visibility == VISIBLE
}
