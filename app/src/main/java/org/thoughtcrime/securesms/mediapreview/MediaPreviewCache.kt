package org.thoughtcrime.securesms.mediapreview

import android.graphics.drawable.Drawable

/**
 * Stores the bitmap for a thumbnail we are animating from via a shared
 * element transition. This prevents us from having to load anything on the
 * receiving end.
 */
object MediaPreviewCache {
  var drawable: Drawable? = null

  /** Tellomi（#1257）：从哪个会话打开的查看器（-1 = 不是从会话打开），查看器据此决定显不显示「回复」。 */
  var replyTargetThreadId: Long = -1

  /** Tellomi（#1257）：查看器里点了「回复」——回到会话后引用这条消息（整条，同长按回复）。 */
  data class PendingReply(val threadId: Long, val messageId: Long)

  var pendingReply: PendingReply? = null

  /** 取出发给 [threadId] 的待回复（取了就清掉）。 */
  fun consumePendingReply(threadId: Long): PendingReply? {
    val reply = pendingReply?.takeIf { it.threadId == threadId } ?: return null
    pendingReply = null
    return reply
  }
}
