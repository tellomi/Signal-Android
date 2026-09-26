/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import kotlin.math.max
import kotlin.math.min

/**
 * Tellomi（tellomi/tellomi#1109，ADR-0058 §2）：滑动回复改成手指从右往左滑（RTL 镜像），手感参数单位都是 dp。
 *
 * - 阈值照 Telegram：对方的消息 45、自己的消息 60（`ChatMessageBubbleItemNode.swipeToReplyGesture`）；
 * - 阈值内气泡 1:1 跟手，过阈值后橡皮筋（range 100、系数 0.4），整体最多 180；
 * - 松手只看位移是否过阈值，不看速度。
 */
object TellomiSwipeToReply {
  const val INCOMING_TRIGGER_DP = 45f
  const val OUTGOING_TRIGGER_DP = 60f
  const val RUBBER_BAND_RANGE_DP = 100f
  const val RUBBER_BAND_FACTOR = 0.4f
  const val MAX_OFFSET_DP = 180f

  @JvmStatic
  fun triggerDp(isOutgoing: Boolean): Float = if (isOutgoing) OUTGOING_TRIGGER_DP else INCOMING_TRIGGER_DP

  /**
   * 手指沿回复方向走了 [fingerOffset] → 气泡跟着走多少（同一单位）。
   * 不超过 [trigger] 时 1:1；超过后橡皮筋，越拉越慢，最多 [maxOffset]。
   */
  @JvmStatic
  fun bubbleOffset(fingerOffset: Float, trigger: Float, rubberBandRange: Float, maxOffset: Float): Float {
    if (fingerOffset <= trigger) {
      return max(fingerOffset, 0f)
    }
    val overflow = fingerOffset - trigger
    val band = (1f - 1f / (overflow * RUBBER_BAND_FACTOR / rubberBandRange + 1f)) * rubberBandRange
    return min(trigger + band, maxOffset)
  }

  /** 回复方向在 x 轴上的正负号：从左往右排版时手指往左（负），从右往左排版时往右（正）。 */
  @JvmStatic
  fun replySign(isRtl: Boolean): Float = if (isRtl) 1f else -1f
}
