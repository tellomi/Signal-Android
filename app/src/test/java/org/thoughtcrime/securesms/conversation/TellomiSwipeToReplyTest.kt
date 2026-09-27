/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.conversation.v2.items.InteractiveConversationElement

/**
 * Tellomi（tellomi/tellomi#1109，ADR-0058 §2）：滑动回复改成手指从右往左滑。
 * Robolectric 默认密度是 1，所以 dp 和 px 在这里相等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiSwipeToReplyTest {

  private fun swipeableElement(isOutgoing: Boolean): RecyclerView.ViewHolder {
    val holder = mockk<RecyclerView.ViewHolder>(moreInterfaces = arrayOf(InteractiveConversationElement::class), relaxed = true)
    val element = holder as InteractiveConversationElement
    every { element.conversationMessage.messageRecord.isOutgoing } returns isOutgoing
    every { element.disallowSwipe(any(), any()) } returns false
    return holder
  }

  private fun callback(): ConversationItemSwipeCallback {
    return ConversationItemSwipeCallback({ true }, { })
  }

  @Test
  fun `the reply swipe runs towards the start edge, i_e_ the finger moves right to left`() {
    val dirs = callback().getSwipeDirs(mockk(relaxed = true), swipeableElement(isOutgoing = false))
    assertThat(dirs).isEqualTo(ItemTouchHelper.START)
  }

  @Test
  fun `a message that cannot be replied to does not swipe at all`() {
    val cannotReply = ConversationItemSwipeCallback({ false }, { })
    assertThat(cannotReply.getSwipeDirs(mockk(relaxed = true), swipeableElement(isOutgoing = false))).isEqualTo(0)
  }

  @Test
  fun `trigger distance is 45dp for incoming and 60dp for outgoing messages, as in Telegram`() {
    assertThat(ConversationItemSwipeCallback.triggerDx(swipeableElement(isOutgoing = false) as InteractiveConversationElement)).isEqualTo(45f)
    assertThat(ConversationItemSwipeCallback.triggerDx(swipeableElement(isOutgoing = true) as InteractiveConversationElement)).isEqualTo(60f)
  }

  @Test
  fun `the bubble follows the finger 1 to 1 up to the trigger`() {
    assertThat(TellomiSwipeToReply.bubbleOffset(0f, 45f, 100f, 180f)).isEqualTo(0f)
    assertThat(TellomiSwipeToReply.bubbleOffset(-12f, 45f, 100f, 180f)).isEqualTo(0f)
    assertThat(TellomiSwipeToReply.bubbleOffset(30f, 45f, 100f, 180f)).isEqualTo(30f)
    assertThat(TellomiSwipeToReply.bubbleOffset(45f, 45f, 100f, 180f)).isEqualTo(45f)
  }

  @Test
  fun `past the trigger it rubber-bands and never exceeds the trigger plus the band or the cap`() {
    // 过阈值 100：(1 - 1 / (100 × 0.4 / 100 + 1)) × 100 ≈ 28.57
    assertThat(TellomiSwipeToReply.bubbleOffset(145f, 45f, 100f, 180f)).isCloseTo(73.571f, 0.01f)
    val far = TellomiSwipeToReply.bubbleOffset(10_000f, 60f, 100f, 180f)
    assertThat(far).isGreaterThan(155f)
    assertThat(far).isLessThan(160f)
    assertThat(TellomiSwipeToReply.bubbleOffset(10_000f, 45f, 100f, 50f)).isEqualTo(50f)
  }

  @Test
  fun `reply direction sign is negative left-to-right and positive right-to-left`() {
    assertThat(TellomiSwipeToReply.replySign(isRtl = false)).isEqualTo(-1f)
    assertThat(TellomiSwipeToReply.replySign(isRtl = true)).isEqualTo(1f)
  }
}
