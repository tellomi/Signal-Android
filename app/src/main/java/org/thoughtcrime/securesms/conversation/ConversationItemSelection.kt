package org.thoughtcrime.securesms.conversation

import android.graphics.Bitmap
import android.graphics.Path
import android.view.ViewGroup
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import org.signal.core.util.DimensionUnit
import org.thoughtcrime.securesms.conversation.v2.items.InteractiveConversationElement
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4Playable
import org.thoughtcrime.securesms.util.hasNoBubble

object ConversationItemSelection {

  const val MAX_SIZE: Long = 4096 * 4096

  @JvmStatic
  fun snapshotView(
    target: InteractiveConversationElement,
    list: RecyclerView,
    messageRecord: MessageRecord,
    videoBitmap: Bitmap?
  ): Bitmap {
    val isOutgoing = messageRecord.isOutgoing
    val hasNoBubble = messageRecord.hasNoBubble(list.context)

    return snapshotMessage(
      target = target,
      list = list,
      videoBitmap = videoBitmap,
      drawConversationItem = (!isOutgoing || hasNoBubble),
      hasReaction = messageRecord.reactions.isNotEmpty()
    )
  }

  private fun snapshotMessage(
    target: InteractiveConversationElement,
    list: RecyclerView,
    videoBitmap: Bitmap?,
    drawConversationItem: Boolean,
    hasReaction: Boolean
  ): Bitmap {
    if (target is ConversationItem && target.hasAlbumCarouselForSnapshot()) {
      return snapshotWithAlbumCarousel(target, list, drawConversationItem, hasReaction)
    }

    val snapshotStrategy = target.getSnapshotStrategy()
    if (snapshotStrategy != null) {
      return createSafeBitmap(target.root.width, target.root.height).applyCanvas {
        snapshotStrategy.snapshot(this)
      }
    }

    val bodyBubble = target.bubbleView
    val reactionsView = target.reactionsView

    val originalScale = bodyBubble.scaleX
    bodyBubble.scaleX = 1.0f
    bodyBubble.scaleY = 1.0f

    val projections = target.getSnapshotProjections(list, false)

    val path = Path()

    val xTranslation = -target.root.x - bodyBubble.x
    val yTranslation = -target.root.y - bodyBubble.y

    val mp4Projection = (target as? GiphyMp4Playable)?.getGiphyMp4PlayableProjection(list)

    var scaledVideoBitmap: Bitmap? = null
    if (videoBitmap != null && mp4Projection != null) {
      scaledVideoBitmap = Bitmap.createScaledBitmap(
        videoBitmap,
        (videoBitmap.width / originalScale).toInt(),
        (videoBitmap.height / originalScale).toInt(),
        true
      )

      mp4Projection.translateX(xTranslation)
      mp4Projection.translateY(yTranslation)
      mp4Projection.applyToPath(path)
    }

    projections.use {
      it.forEach { p ->
        p.translateX(xTranslation)
        p.translateY(yTranslation)
        p.applyToPath(path)
      }
    }

    target.root.destroyAllDrawingCaches()

    var bitmapHeight = bodyBubble.height
    if (hasReaction) {
      bitmapHeight += (reactionsView.height - DimensionUnit.DP.toPixels(4f)).toInt()
    }
    return createSafeBitmap(bodyBubble.width, bitmapHeight).applyCanvas {
      if (drawConversationItem) {
        bodyBubble.draw(this)
      }

      withClip(path) {
        withTranslation(x = xTranslation, y = yTranslation) {
          list.draw(this)

          if (scaledVideoBitmap != null && mp4Projection != null) {
            drawBitmap(scaledVideoBitmap, mp4Projection.x - xTranslation, mp4Projection.y - yTranslation, null)
          }
        }
      }

      withTranslation(
        x = reactionsView.x - bodyBubble.x,
        y = reactionsView.y - bodyBubble.y
      ) {
        reactionsView.draw(this)
      }
    }.also {
      mp4Projection?.release()
      bodyBubble.scaleX = originalScale
      bodyBubble.scaleY = originalScale
    }
  }

  /**
   * Tellomi（#1257）：横滑相册铺在整条消息的宽度上，不在气泡里。快照从整条消息的左边开始（宽度 = 整行），
   * 气泡照原来的办法画（渐变 / 壁纸聊天色从列表按投影取），再把相册和表情回应画到各自的位置上。
   * 对应的 SnapshotMetrics（左偏移 0）见 [snapshotMetricsFor]。
   */
  private fun snapshotWithAlbumCarousel(
    target: ConversationItem,
    list: RecyclerView,
    drawConversationItem: Boolean,
    hasReaction: Boolean
  ): Bitmap {
    val element: InteractiveConversationElement = target
    val bodyBubble = element.bubbleView
    val reactionsView = element.reactionsView
    val carousel = target.albumCarouselForSnapshot()!!

    val originalBubbleScale = bodyBubble.scaleX
    val originalCarouselScale = carousel.scaleX
    bodyBubble.scaleX = 1.0f
    bodyBubble.scaleY = 1.0f
    carousel.scaleX = 1.0f
    carousel.scaleY = 1.0f

    val path = Path()
    val xTranslation = -target.root.x
    val yTranslation = -target.root.y - bodyBubble.y

    target.getSnapshotProjections(list, false).use {
      it.forEach { p ->
        p.translateX(xTranslation)
        p.translateY(yTranslation)
        p.applyToPath(path)
      }
    }

    target.root.destroyAllDrawingCaches()

    var bitmapHeight = bodyBubble.height
    if (hasReaction) {
      bitmapHeight += (reactionsView.height - DimensionUnit.DP.toPixels(4f)).toInt()
    }

    return createSafeBitmap(target.root.width, bitmapHeight).applyCanvas {
      if (drawConversationItem) {
        withTranslation(x = bodyBubble.x) {
          bodyBubble.draw(this)
        }
      }

      withClip(path) {
        withTranslation(x = xTranslation, y = yTranslation) {
          list.draw(this)
        }
      }

      withTranslation(x = carousel.x, y = carousel.y - bodyBubble.y) {
        carousel.draw(this)
      }

      withTranslation(x = reactionsView.x, y = reactionsView.y - bodyBubble.y) {
        reactionsView.draw(this)
      }
    }.also {
      bodyBubble.scaleX = originalBubbleScale
      bodyBubble.scaleY = originalBubbleScale
      carousel.scaleX = originalCarouselScale
      carousel.scaleY = originalCarouselScale
    }
  }

  /** 长按快照的横向位置：有横滑相册时快照从整条消息的左边开始。 */
  @JvmStatic
  fun snapshotMetricsFor(target: InteractiveConversationElement): InteractiveConversationElement.SnapshotMetrics {
    target.getSnapshotStrategy()?.let { return it.snapshotMetrics }

    val bodyBubble = target.bubbleView
    return if (target is ConversationItem && target.hasAlbumCarouselForSnapshot()) {
      InteractiveConversationElement.SnapshotMetrics(snapshotOffset = 0f, contextMenuPadding = bodyBubble.x)
    } else {
      InteractiveConversationElement.SnapshotMetrics(snapshotOffset = bodyBubble.x, contextMenuPadding = bodyBubble.x)
    }
  }

  private fun createSafeBitmap(width: Int, height: Int): Bitmap {
    return if (width * height < MAX_SIZE) {
      createBitmap(width, height)
    } else {
      // Since this is only called in rare cases where the height is incredibly large / 'Read more' has not been triggered yet,
      // used a general solution instead of something more algorithmic.
      createBitmap(width, height / 2)
    }
  }
}

private fun ViewGroup.destroyAllDrawingCaches() {
  children.forEach {
    it.destroyDrawingCache()

    if (it is ViewGroup) {
      it.destroyAllDrawingCaches()
    }
  }
}
