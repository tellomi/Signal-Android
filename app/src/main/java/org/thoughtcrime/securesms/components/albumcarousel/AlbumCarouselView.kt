/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.albumcarousel

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.annotation.VisibleForTesting
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import org.signal.core.ui.view.Stub
import org.signal.core.util.AccessibilityUtil
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ConversationItemFooter
import org.thoughtcrime.securesms.components.ThumbnailView
import org.thoughtcrime.securesms.components.transfercontrols.TransferControlView
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideClickListener
import org.thoughtcrime.securesms.mms.SlidesClickedListener
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.MediaUtil
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Tellomi：一条消息里 ≥ 2 张图片 / 视频时的横滑相册（tellomi/tellomi#1257，需求第一节）。
 *
 * 由 [org.thoughtcrime.securesms.conversation.ConversationItem] 铺在整条消息的宽度上（不在气泡里）：滑动区是整屏宽，
 * 静止时第一张左边对齐 [startInset]，自己发的放得下时整组靠右（[alignEndWhenFits]）。几何全在 [AlbumCarouselGeometry]；
 * 这里负责摆放、松手吸附（C-6）、不随图片滚动的时间胶囊（C-7）、整组「下载 N 个项目」（C-12）与读屏（C-13）。
 */
class AlbumCarouselView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

  companion object {
    private const val SETTLE_DURATION_MS = 260

    /** 同一条消息被回收再绑定时回到原来的位置（按消息 id，只存在内存里）。 */
    private val savedOffsets = LruCache<Long, Int>(64)

    /** C-2：给定滑动区宽度（px）时的行高（px）。ConversationItem 量气泡前要用它给相册留高度。 */
    @JvmStatic
    fun rowHeightPx(context: Context, viewportWidthPx: Int): Int {
      val metrics = context.resources.displayMetrics
      val config = context.resources.configuration
      val density = metrics.density
      val capByScreenHeight = config.orientation == Configuration.ORIENTATION_LANDSCAPE || config.smallestScreenWidthDp >= 600
      val rowHeightDp = AlbumCarouselGeometry.rowHeightDp(viewportWidthPx / density, config.screenHeightDp.toFloat(), capByScreenHeight)
      return (rowHeightDp * density).roundToInt()
    }

    /** C-1：只有 ≥ 2 个附件、而且全部是图片或视频时才横滑。 */
    @JvmStatic
    fun isEligible(slides: List<Slide>): Boolean {
      return slides.size >= 2 && slides.all { it.hasImage() || it.hasVideo() }
    }
  }

  private val recyclerView: RecyclerView
  private val layoutManager: CarouselLayoutManager
  private val adapter = CarouselAdapter()
  private val transferControls: Stub<TransferControlView>

  /** 无说明时的时间与勾（C-7）。ConversationItem 把它当作这条消息的 footer。 */
  val footer: Stub<ConversationItemFooter>

  private val spacing = dp(AlbumCarouselGeometry.ITEM_SPACING_DP)
  private val minNextPeek = dp(AlbumCarouselGeometry.NEXT_ITEM_MIN_PEEK_DP)
  private val endMargin = dp(AlbumCarouselGeometry.END_MARGIN_DP)
  private val itemRadius = resources.getDimensionPixelSize(R.dimen.message_corner_radius)
  private val overlayMargin = dp(8f)
  private val flingProjector = OverScroller(context)
  private val settleInterpolator = DecelerateInterpolator(1.6f)

  private var requestManager: RequestManager? = null
  private var slides: List<Slide> = emptyList()
  private var showControls = false
  private var messageId = -1L
  private var geometry: AlbumCarouselGeometry.Layout? = null
  private var userDragged = false
  private var pendingRestoreOffset = 0

  /** 静止时第一张左边到屏幕起始边的距离（C-5）。 */
  var startInset: Int = 0
    set(value) {
      if (field != value) {
        field = value
        requestLayout()
      }
    }

  /** 放得下、不能滑时整组贴末端（自己发的相册靠右）。 */
  var alignEndWhenFits: Boolean = false
    set(value) {
      if (field != value) {
        field = value
        requestLayout()
      }
    }

  var thumbnailClickListener: SlideClickListener? = null
  var startTransferClickListener: SlidesClickedListener? = null
  var cancelTransferClickListener: SlidesClickedListener? = null
  var playVideoClickListener: SlideClickListener? = null
  var itemLongClickListener: OnLongClickListener? = null

  init {
    inflate(context, R.layout.album_carousel_view, this)
    clipChildren = false
    clipToPadding = false

    recyclerView = findViewById(R.id.album_carousel_recycler)
    transferControls = Stub(findViewById(R.id.album_carousel_transfer_controls_stub))
    footer = Stub(findViewById(R.id.album_carousel_footer_stub))

    layoutManager = CarouselLayoutManager(context)
    recyclerView.layoutManager = layoutManager
    recyclerView.adapter = adapter
    recyclerView.itemAnimator = null
    recyclerView.isNestedScrollingEnabled = false
    recyclerView.addItemDecoration(CarouselDecoration())
    recyclerView.onFlingListener = CarouselFlingListener()
    recyclerView.addOnScrollListener(CarouselScrollListener())

    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    recyclerView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    ViewCompat.setAccessibilityDelegate(this, CarouselAccessibilityDelegate())
  }

  val isScrollable: Boolean
    get() = geometry?.isScrollable == true

  val itemCount: Int
    get() = slides.size

  fun setSlides(requestManager: RequestManager, messageId: Long, slides: List<Slide>, showControls: Boolean) {
    require(slides.size >= 2) { "A carousel needs at least two slides." }

    val sameMessage = this.messageId == messageId && this.slides.size == slides.size

    this.requestManager = requestManager
    this.slides = slides
    this.showControls = showControls
    this.messageId = messageId

    if (!sameMessage) {
      geometry = null
      pendingRestoreOffset = savedOffsets.get(messageId) ?: 0
    }

    bindAlbumTransferControls()
    adapter.notifyDataSetChanged()
    updateAccessibilityText()
    requestLayout()
  }

  /** 查看器在第几张（按附件的 uri）；找不到返回 -1。 */
  fun indexOfSlideUri(uri: android.net.Uri?): Int {
    if (uri == null) return -1
    return slides.indexOfFirst { it.uri == uri || it.displayUri == uri }
  }

  /** C-9：先把第 [index] 张滚到完整露出（不做动画），返回它的视图（排好之后才有）。 */
  fun revealItem(index: Int): View? {
    val g = geometry ?: return null
    if (index !in 0 until g.itemCount) return null

    val target = g.offsetRevealingItem(index, currentOffset())
    scrollToOffsetImmediately(target)
    return layoutManager.findViewByPosition(index)
  }

  fun findItemView(index: Int): View? = layoutManager.findViewByPosition(index)

  @VisibleForTesting
  fun geometryForTesting(): AlbumCarouselGeometry.Layout? = geometry

  @VisibleForTesting
  fun currentItemIndexForTesting(): Int = currentIndex()

  /** 屏幕坐标下按在相册的滑动区里、而且整组能滑时，横向拖动交给相册（C-11），不触发滑动回复。 */
  fun claimsHorizontalDrag(rawX: Float, rawY: Float): Boolean {
    if (!isScrollable || visibility != VISIBLE) return false
    val location = IntArray(2)
    recyclerView.getLocationOnScreen(location)
    val insideX = rawX >= location[0] && rawX <= location[0] + recyclerView.width
    val insideY = rawY >= location[1] && rawY <= location[1] + recyclerView.height
    return insideX && insideY
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val width = MeasureSpec.getSize(widthMeasureSpec)
    val rowHeight = rowHeightPx(context, width)

    if (slides.isNotEmpty() && width > 0) {
      val newGeometry = AlbumCarouselGeometry.layout(
        viewportWidth = width,
        startInset = startInset,
        endMargin = endMargin,
        spacing = spacing,
        minNextPeek = minNextPeek,
        rowHeight = rowHeight,
        aspectRatios = slides.map { AlbumCarouselGeometry.aspectRatio(it.asAttachment().width, it.asAttachment().height) },
        alignEndWhenFits = alignEndWhenFits
      )

      if (!sameGeometry(geometry, newGeometry)) {
        val previousOffset = if (geometry != null) currentOffset() else pendingRestoreOffset
        geometry = newGeometry
        recyclerView.setPaddingRelative(newGeometry.itemLefts[0], 0, endMargin, 0)
        adapter.notifyDataSetChanged()
        restoreOffset(min(previousOffset, newGeometry.maxScroll))
      }
    }

    super.onMeasure(
      MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
    )

    // 整组「下载 N 个项目」只盖住相册可视区（上游相册里它和相册一样大，大小标签在左上角）。
    val g = geometry
    if (g != null && transferControls.resolved() && transferControls.get().visibility != GONE) {
      val (areaStart, areaEnd) = albumArea(g, width)
      transferControls.get().measure(
        MeasureSpec.makeMeasureSpec(areaEnd - areaStart, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
      )
    }
  }

  /** 「相册可视区」：能滑时是起点到末端边距；放得下时是整组自己的范围。 */
  private fun albumArea(g: AlbumCarouselGeometry.Layout, width: Int): Pair<Int, Int> {
    val areaStart = g.itemLefts[0]
    val areaEnd = if (g.isScrollable) width - endMargin else g.itemLefts.last() + g.itemWidths.last()
    return areaStart to areaEnd
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)

    val g = geometry ?: return
    val width = right - left
    val height = bottom - top

    val (areaStart, areaEnd) = albumArea(g, width)

    if (footer.resolved() && footer.get().visibility != GONE) {
      val f = footer.get()
      val fw = f.measuredWidth
      val fh = f.measuredHeight
      val endEdge = areaEnd - overlayMargin
      val x = if (isRtl()) width - endEdge else endEdge - fw
      f.layout(x, height - overlayMargin - fh, x + fw, height - overlayMargin)
    }

    if (transferControls.resolved() && transferControls.get().visibility != GONE) {
      val c = transferControls.get()
      val left = if (isRtl()) width - areaEnd else areaStart
      c.layout(left, 0, left + c.measuredWidth, c.measuredHeight)
    }
  }

  private fun sameGeometry(a: AlbumCarouselGeometry.Layout?, b: AlbumCarouselGeometry.Layout): Boolean {
    return a != null &&
      a.viewportWidth == b.viewportWidth &&
      a.rowHeight == b.rowHeight &&
      a.itemLefts.contentEquals(b.itemLefts) &&
      a.itemWidths.contentEquals(b.itemWidths)
  }

  private fun bindAlbumTransferControls() {
    // C-12：一张都没下载时，可视区中间给整组的「下载 N 个项目」；其余情况每张自己带进度圈与重试。
    if (showControls && noneDownloaded()) {
      val controls = transferControls.get()
      controls.visibility = VISIBLE
      controls.setShowSecondaryText(true)
      controls.setTransferClickListener { startTransferClickListener?.onClick(it, slides) }
      controls.setCancelClickListener { cancelTransferClickListener?.onClick(it, slides) }
      controls.setSlides(slides)
      controls.setVisible(true)
    } else if (transferControls.resolved()) {
      transferControls.get().clear()
      transferControls.get().visibility = GONE
    }
  }

  private fun noneDownloaded(): Boolean {
    return slides.none { it.transferState == AttachmentTable.TRANSFER_PROGRESS_DONE || it.asAttachment().uri != null }
  }

  private fun showItemControls(): Boolean {
    return showControls && !(transferControls.resolved() && transferControls.get().visibility == VISIBLE)
  }

  private fun isRtl(): Boolean = layoutDirection == LAYOUT_DIRECTION_RTL

  /** 当前滚动偏移（从起始边算，LTR / RTL 一样）。 */
  private fun currentOffset(): Int {
    val g = geometry ?: return 0
    val child = recyclerView.getChildAt(0) ?: return pendingRestoreOffset
    val position = recyclerView.getChildAdapterPosition(child)
    if (position == RecyclerView.NO_POSITION || position >= g.itemCount) return 0
    val childStart = if (isRtl()) recyclerView.width - child.right else child.left
    return g.itemLefts[position] - childStart
  }

  private fun restoreOffset(offset: Int) {
    val g = geometry ?: return
    pendingRestoreOffset = offset
    if (offset <= 0 || !g.isScrollable) {
      layoutManager.scrollToPositionWithOffset(0, 0)
      return
    }
    val index = g.itemIndexAtOffset(offset)
    layoutManager.scrollToPositionWithOffset(index, g.snapOffsetForItem(index) - offset)
  }

  private fun scrollToOffsetImmediately(target: Int) {
    val delta = target - currentOffset()
    if (delta != 0) {
      recyclerView.scrollBy(if (isRtl()) -delta else delta, 0)
    }
    saveOffset()
  }

  private fun settleTo(target: Int) {
    val delta = target - currentOffset()
    if (delta == 0) {
      saveOffset()
      return
    }

    val dx = if (isRtl()) -delta else delta
    if (AccessibilityUtil.areAnimationsDisabled(context)) {
      // C-13：减弱动态效果时直接到位，不做滑动动画。
      recyclerView.scrollBy(dx, 0)
      saveOffset()
    } else {
      recyclerView.smoothScrollBy(dx, 0, settleInterpolator, SETTLE_DURATION_MS)
    }
  }

  private fun saveOffset() {
    if (messageId >= 0) {
      val offset = currentOffset()
      pendingRestoreOffset = offset
      savedOffsets.put(messageId, offset)
    }
  }

  private fun currentIndex(): Int {
    val g = geometry ?: return 0
    return g.itemIndexAtOffset(currentOffset())
  }

  private fun moveBy(step: Int): Boolean {
    val g = geometry ?: return false
    if (!g.isScrollable) return false
    val index = (currentIndex() + step).coerceIn(0, g.itemCount - 1)
    settleTo(g.snapOffsetForItem(index))
    announceForAccessibility(itemPositionText(index))
    return true
  }

  private fun updateAccessibilityText() {
    contentDescription = resources.getQuantityString(R.plurals.AlbumCarouselView__album_with_d_items, slides.size, slides.size)
  }

  private fun itemPositionText(index: Int): String {
    return resources.getString(R.string.AlbumCarouselView__item_d_of_d, index + 1, slides.size)
  }

  private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

  private inner class CarouselLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {
    override fun canScrollHorizontally(): Boolean = geometry?.isScrollable == true
  }

  private inner class CarouselAdapter : RecyclerView.Adapter<CarouselViewHolder>() {
    override fun getItemCount(): Int = if (geometry == null) 0 else slides.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselViewHolder {
      val thumbnail = ThumbnailView(parent.context)
      thumbnail.layoutParams = RecyclerView.LayoutParams(0, 0)
      return CarouselViewHolder(thumbnail)
    }

    override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
      val g = geometry ?: return
      holder.bind(slides[position], g.itemWidths[position], g.rowHeight)
    }

    override fun onViewRecycled(holder: CarouselViewHolder) {
      requestManager?.let { holder.thumbnail.clear(it) }
    }
  }

  private inner class CarouselViewHolder(val thumbnail: ThumbnailView) : RecyclerView.ViewHolder(thumbnail) {
    fun bind(slide: Slide, width: Int, height: Int) {
      val params = thumbnail.layoutParams
      if (params.width != width || params.height != height) {
        params.width = width
        params.height = height
        thumbnail.layoutParams = params
      }

      thumbnail.setRadii(itemRadius, itemRadius, itemRadius, itemRadius)
      thumbnail.showSecondaryText(false)
      thumbnail.setThumbnailClickListener { v, s -> thumbnailClickListener?.onClick(v, s) }
      thumbnail.setStartTransferClickListener { v, s -> startTransferClickListener?.onClick(v, s) }
      thumbnail.setCancelTransferClickListener { v, s -> cancelTransferClickListener?.onClick(v, s) }
      if (MediaUtil.isInstantVideoSupported(slide)) {
        thumbnail.setPlayVideoClickListener { v, s -> playVideoClickListener?.onClick(v, s) }
      } else {
        thumbnail.setPlayVideoClickListener(null)
      }
      thumbnail.setOnLongClickListener { itemLongClickListener?.onLongClick(it) ?: false }

      val manager = requestManager ?: return
      thumbnail.setImageResource(manager, slide, showItemControls(), false)
    }
  }

  /** C-4：卡片间 8；浅色主题下每张 1 物理像素浅灰描边。 */
  private inner class CarouselDecoration : RecyclerView.ItemDecoration() {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 1f
      color = 0x26000000
    }
    private val rect = RectF()

    override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
      val position = parent.getChildAdapterPosition(view)
      if (position == RecyclerView.NO_POSITION || position >= slides.size - 1) {
        outRect.set(0, 0, 0, 0)
      } else if (isRtl()) {
        outRect.set(spacing, 0, 0, 0)
      } else {
        outRect.set(0, 0, spacing, 0)
      }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
      if (DynamicTheme.isDarkTheme(context)) return

      for (i in 0 until parent.childCount) {
        val child = parent.getChildAt(i)
        rect.set(child.left + 0.5f, child.top + 0.5f, child.right - 0.5f, child.bottom - 0.5f)
        rect.offset(child.translationX, child.translationY)
        c.drawRoundRect(rect, itemRadius.toFloat(), itemRadius.toFloat(), strokePaint)
      }
    }
  }

  /** C-6：甩出去按落点找最近的吸附位（可越过几张），至少朝甩的方向走一格。 */
  private inner class CarouselFlingListener : RecyclerView.OnFlingListener() {
    override fun onFling(velocityX: Int, velocityY: Int): Boolean {
      val g = geometry ?: return false
      if (!g.isScrollable || abs(velocityX) < recyclerView.minFlingVelocity) return false

      val offsetVelocity = if (isRtl()) -velocityX else velocityX
      flingProjector.fling(0, 0, offsetVelocity, 0, Int.MIN_VALUE, Int.MAX_VALUE, 0, 0)
      val distance = flingProjector.finalX
      flingProjector.abortAnimation()

      val current = currentOffset()
      settleTo(g.targetSnapOffset(current, current + distance, offsetVelocity.sign))
      return true
    }
  }

  /** C-6：慢慢松手（没有甩）停在最近的吸附位。 */
  private inner class CarouselScrollListener : RecyclerView.OnScrollListener() {
    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
      when (newState) {
        RecyclerView.SCROLL_STATE_DRAGGING -> userDragged = true
        RecyclerView.SCROLL_STATE_IDLE -> {
          val g = geometry
          if (userDragged && g != null && g.isScrollable) {
            userDragged = false
            val current = currentOffset()
            val target = g.targetSnapOffset(current, current, 0)
            if (target != current) {
              settleTo(target)
              return
            }
          }
          saveOffset()
        }
      }
    }
  }

  /** C-13：整组读作「相册，共 N 项」，自定义动作「下一项 / 上一项」逐张切换；双击打开当前这一张。 */
  private inner class CarouselAccessibilityDelegate : AccessibilityDelegateCompat() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
      super.onInitializeAccessibilityNodeInfo(host, info)
      val index = currentIndex()
      info.contentDescription = resources.getString(R.string.AlbumCarouselView__s_s, contentDescription ?: "", itemPositionText(index))
      info.isClickable = true
      info.isLongClickable = true
      info.addAction(AccessibilityActionCompat.ACTION_CLICK)
      info.addAction(AccessibilityActionCompat.ACTION_LONG_CLICK)
      if (isScrollable && index < slides.size - 1) {
        info.addAction(AccessibilityActionCompat(R.id.accessibility_action_album_next, resources.getString(R.string.AlbumCarouselView__next_item)))
      }
      if (isScrollable && index > 0) {
        info.addAction(AccessibilityActionCompat(R.id.accessibility_action_album_previous, resources.getString(R.string.AlbumCarouselView__previous_item)))
      }
    }

    override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
      return when (action) {
        R.id.accessibility_action_album_next -> moveBy(1)
        R.id.accessibility_action_album_previous -> moveBy(-1)
        AccessibilityNodeInfoCompat.ACTION_CLICK -> {
          val index = currentIndex()
          val view = revealItem(index) ?: return false
          slides.getOrNull(index)?.let { thumbnailClickListener?.onClick(view, it) }
          true
        }
        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> itemLongClickListener?.onLongClick(host) ?: false
        else -> super.performAccessibilityAction(host, action, args)
      }
    }
  }
}
