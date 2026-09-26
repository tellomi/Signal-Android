package org.thoughtcrime.securesms.mediapreview

import android.animation.Animator
import android.animation.Animator.AnimatorListener
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.LegacyPlayerControlView
import androidx.media3.ui.TimeBar
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.visible
import org.signal.core.ui.R as CoreUiR

/**
 * The bottom bar for the media preview.
 *
 * Tellomi（tellomi/tellomi#1257，owner 2026-09-25「多个视频点开时完全参考 Telegram 的设计」）：进度条放进深色胶囊（左已播、右总时长），
 * 拖动时不暂停、松手才跳（照 Telegram），拖动过程报给查看器画该位置的预览帧；下面一行 转发 · 倍速（带角标）· 删除；
 * 播放 / 暂停在屏幕中间（[MediaPreviewCenterControlsView]）。分享挪到了右上角「···」里。
 */
@OptIn(UnstableApi::class)
class MediaPreviewPlayerControlView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
  playbackAttrs: AttributeSet? = null
) : LegacyPlayerControlView(context, attrs, defStyleAttr, playbackAttrs) {

  private val durationBar: LinearLayout = findViewById(R.id.exo_duration_viewgroup)
  private val exoProgress: DefaultTimeBar = findViewById(R.id.exo_progress)
  private val currentPositionLabel: TextView = findViewById(R.id.exo_position_label)
  private val durationLabel: TextView = findViewById(R.id.exo_duration_label)
  private val forwardButton: ImageButton = findViewById(R.id.exo_forward)
  private val deleteButton: ImageButton = findViewById(R.id.media_preview_delete_button)
  private val speedGroup: View = findViewById(R.id.media_preview_speed_group)
  private val speedButton: ImageButton = findViewById(R.id.media_preview_speed_button)
  private val speedBadge: TextView = findViewById(R.id.media_preview_speed_badge)

  private var mediaMode: MediaMode = MediaMode.IMAGE

  /** 拖进度条：按住、移动、松手。坐标是屏幕坐标（拇指中心 x、胶囊顶边 y），给查看器摆预览帧。 */
  interface ScrubListener {
    fun onScrubStart(positionMs: Long, thumbCenterXOnScreen: Float, pillTopOnScreen: Float)
    fun onScrubMove(positionMs: Long, thumbCenterXOnScreen: Float, pillTopOnScreen: Float)
    fun onScrubStop(positionMs: Long)
  }

  var scrubListener: ScrubListener? = null

  /** 播放器换了（翻到另一个视频）：查看器据此重新套中间的播放键、循环。 */
  var onPlayerChanged: ((Player?) -> Unit)? = null

  /** 本次查看器里的倍速：翻到下一个视频也沿用（照 Telegram，不写全局设置）。 */
  var playbackSpeed: Float = 1f
    set(value) {
      field = value
      player?.setPlaybackSpeed(value)
      updateSpeedBadge()
    }

  enum class MediaMode {
    IMAGE,
    VIDEO;

    companion object {
      @JvmStatic
      fun fromString(contentType: String?): MediaMode {
        if (MediaUtil.isVideo(contentType)) return VIDEO
        if (MediaUtil.isImageType(contentType)) return IMAGE
        throw IllegalArgumentException("Unknown content type: $contentType")
      }
    }
  }

  init {
    setShowPreviousButton(false)
    setShowNextButton(false)
    showShuffleButton = false
    showVrButton = false
    showTimeoutMs = -1

    exoProgress.addListener(
      object : TimeBar.OnScrubListener {
        override fun onScrubStart(timeBar: TimeBar, position: Long) {
          updateTimeLabels(position)
          val (x, y) = scrubAnchorOnScreen(position)
          scrubListener?.onScrubStart(position, x, y)
        }

        override fun onScrubMove(timeBar: TimeBar, position: Long) {
          updateTimeLabels(position)
          val (x, y) = scrubAnchorOnScreen(position)
          scrubListener?.onScrubMove(position, x, y)
        }

        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
          updateTimeLabels(position)
          scrubListener?.onScrubStop(position)
        }
      }
    )
    updateSpeedBadge()
  }

  override fun setPlayer(player: Player?) {
    super.setPlayer(player)
    player?.setPlaybackSpeed(playbackSpeed)
    onPlayerChanged?.invoke(player)
  }

  fun setMediaMode(mediaMode: MediaMode) {
    this.mediaMode = mediaMode
    durationBar.visible = mediaMode == MediaMode.VIDEO
    speedGroup.visible = mediaMode == MediaMode.VIDEO
    if (mediaMode == MediaMode.VIDEO) {
      setProgressUpdateListener { position, _ ->
        updateTimeLabels(position)
      }
    } else {
      setProgressUpdateListener(null)
    }
  }

  /** 左边已播、右边总时长（owner 2026-09-25 要的是「视频的整体时长」；Telegram 右边其实是剩余时间，见 #1257 报告）。 */
  private fun updateTimeLabels(position: Long) {
    val finalPlayer = player ?: return
    val duration = finalPlayer.duration
    currentPositionLabel.text = formatPlaybackTime(position.coerceAtLeast(0L))
    durationLabel.text = if (duration > 0) formatPlaybackTime(duration) else "-:--"
  }

  /** 拇指中心（按 [positionMs] 在条上的比例）与胶囊顶边的屏幕坐标。 */
  private fun scrubAnchorOnScreen(positionMs: Long): Pair<Float, Float> {
    val duration = player?.duration?.takeIf { it > 0 } ?: 1L
    val fraction = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val barLocation = IntArray(2).also { exoProgress.getLocationOnScreen(it) }
    val pillLocation = IntArray(2).also { durationBar.getLocationOnScreen(it) }
    return (barLocation[0] + fraction * exoProgress.width) to pillLocation[1].toFloat()
  }

  private fun updateSpeedBadge() {
    val showBadge = kotlin.math.abs(playbackSpeed - 1f) > 0.05f
    speedBadge.visible = showBadge
    speedBadge.text = formatPlaybackSpeed(playbackSpeed)
  }

  fun setForwardButtonListener(listener: OnClickListener?) = forwardButton.setOnClickListener(listener)

  fun setDeleteButtonListener(listener: OnClickListener?) = deleteButton.setOnClickListener(listener)

  fun setDeleteButtonVisible(visible: Boolean) {
    deleteButton.visibility = if (visible) VISIBLE else INVISIBLE
  }

  fun setSpeedButtonListener(listener: OnClickListener?) = speedButton.setOnClickListener(listener)

  val speedButtonView: View get() = speedButton

  @VisibleForTesting
  fun timeLabelsForTesting(): Pair<CharSequence, CharSequence> = currentPositionLabel.text to durationLabel.text

  @VisibleForTesting
  fun speedBadgeForTesting(): CharSequence? = if (speedBadge.visible) speedBadge.text else null

  @VisibleForTesting
  fun timeBarForTesting(): View = exoProgress

  companion object {
    /** m:ss；一小时以上 h:mm:ss。 */
    @JvmStatic
    fun formatPlaybackTime(positionMs: Long): String {
      val totalSeconds = positionMs / 1000
      val hours = totalSeconds / 3600
      val minutes = (totalSeconds % 3600) / 60
      val seconds = totalSeconds % 60
      return if (hours > 0) {
        "%d:%02d:%02d".format(java.util.Locale.US, hours, minutes, seconds)
      } else {
        "%d:%02d".format(java.util.Locale.US, minutes, seconds)
      }
    }

    /** 1.5x、2x、0.7x。 */
    @JvmStatic
    fun formatPlaybackSpeed(speed: Float): String {
      val rounded = kotlin.math.round(speed * 10f) / 10f
      return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}x" else "${rounded}x"
    }
  }
}

class LottieAnimatedButton @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : LottieAnimationView(context, attrs) {

  init {
    addValueCallback(KeyPath("**"), LottieProperty.COLOR) { ContextCompat.getColor(context, CoreUiR.color.signal_colorOnSurface) }
  }

  override fun onTouchEvent(event: MotionEvent?): Boolean {
    when (event?.action) {
      MotionEvent.ACTION_DOWN -> {
        speed = 1f
        playAnimation()
      }
      MotionEvent.ACTION_UP -> {
        if (isAnimating) {
          addAnimatorListener(object : AnimatorListener {
            override fun onAnimationEnd(animation: Animator) {
              removeAllAnimatorListeners()
              playAnimationReverse()
            }

            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
          })
        } else {
          playAnimationReverse()
        }
      }
    }
    return super.onTouchEvent(event)
  }

  private fun playAnimationReverse() {
    speed = -1f
    playAnimation()
  }
}

class AnimatedInOutImageButton @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : AppCompatImageButton(context, attrs) {
  private val rotationWhenVisible: Float
  private val rotationWhenHidden: Float

  init {
    val styledAttrs = getContext().obtainStyledAttributes(attrs, R.styleable.AnimatedInOutImageButton)
    rotationWhenVisible = styledAttrs.getFloat(R.styleable.AnimatedInOutImageButton_rotationWhenVisible, 0f)
    rotationWhenHidden = styledAttrs.getFloat(R.styleable.AnimatedInOutImageButton_rotationWhenHidden, 0f)
    styledAttrs.recycle()
  }

  override fun setVisibility(visibility: Int) {
    if (visibility == VISIBLE) {
      super.setVisibility(visibility)
      animateIn()
    } else {
      animateOut { super.setVisibility(visibility) }
    }
  }

  private fun animateIn() {
    this.rotation = rotationWhenHidden
    this.scaleX = 0.5f
    this.scaleY = 0.5f
    this.alpha = 0f

    val animator = this.animate()
      .setDuration(animationDurationMs)
      .alpha(1f)
      .rotation(rotationWhenVisible)
      .scaleX(1f)
      .scaleY(1f)

    animator.interpolator = PathInterpolator(0.4f, 0.0f, 0.2f, 1f)

    animator.start()
  }

  private fun animateOut(endAction: Runnable) {
    val animator = this.animate()
      .setDuration(animationDurationMs)
      .alpha(0f)
      .rotation(rotationWhenHidden)
      .scaleX(0.5f)
      .scaleY(0.5f)
      .withEndAction(endAction)

    animator.interpolator = PathInterpolator(0.4f, 0.0f, 0.2f, 1f)
    animator.start()
  }

  companion object {
    const val animationDurationMs: Long = 150
  }
}
