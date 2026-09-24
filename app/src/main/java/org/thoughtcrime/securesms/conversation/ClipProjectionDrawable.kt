package org.thoughtcrime.securesms.conversation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import org.thoughtcrime.securesms.util.Projection

/**
 * Drawable which clips out the given projection
 */
class ClipProjectionDrawable(wrapped: Drawable) : LayerDrawable(arrayOf(wrapped)) {

  constructor() : this(ColorDrawable(Color.TRANSPARENT))

  init {
    setId(0, 0)
  }

  private val clipPath = Path()
  private var projections: List<Projection> = listOf()

  private var gapTop = -1
  private var gapBottom = -1
  private val fullBounds = Rect()
  private val partBounds = Rect()

  fun setWrappedDrawable(drawable: Drawable) {
    setDrawableByLayerId(0, drawable)
  }

  fun setProjections(projections: Set<Projection>) {
    this.projections = projections.toList()
    invalidateSelf()
  }

  fun clearProjections() {
    this.projections = listOf()
    invalidateSelf()
  }

  /**
   * Tellomi（#1257）：[top, bottom) 这一条不画，上下两段各自按原来的形状（圆角）完整地画一次。
   * 横滑相册铺在气泡中间那一条上时用；top 小于 0 表示不分段。
   */
  fun setGap(top: Int, bottom: Int) {
    if (gapTop != top || gapBottom != bottom) {
      gapTop = top
      gapBottom = bottom
      invalidateSelf()
    }
  }

  override fun draw(canvas: Canvas) {
    if (gapTop < 0 || gapBottom <= gapTop || numberOfLayers == 0) {
      drawWithProjections(canvas)
      return
    }

    val layer = getDrawable(0)
    val callback = layer.callback
    fullBounds.set(layer.bounds)

    // 改子图层的 bounds 会回调 invalidate；先摘掉回调，不然每画一次都会再排一次重绘。
    layer.callback = null

    if (gapTop > 0) {
      partBounds.set(fullBounds.left, fullBounds.top, fullBounds.right, fullBounds.top + gapTop)
      layer.bounds = partBounds
      drawWithProjections(canvas)
    }

    if (fullBounds.top + gapBottom < fullBounds.bottom) {
      partBounds.set(fullBounds.left, fullBounds.top + gapBottom, fullBounds.right, fullBounds.bottom)
      layer.bounds = partBounds
      drawWithProjections(canvas)
    }

    layer.bounds = fullBounds
    layer.callback = callback
  }

  private fun drawWithProjections(canvas: Canvas) {
    if (projections.isNotEmpty()) {
      canvas.save()
      clipPath.rewind()

      projections.forEach {
        it.applyToPath(clipPath)
      }

      canvas.clipPath(clipPath, Region.Op.DIFFERENCE)
      super.draw(canvas)
      canvas.restore()
    } else {
      super.draw(canvas)
    }
  }
}
