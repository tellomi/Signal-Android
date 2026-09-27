/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2.items

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.conversation.ConversationItem
import org.thoughtcrime.securesms.conversation.ConversationItemDisplayMode
import org.thoughtcrime.securesms.conversation.v2.tellomiIsSplitByUnreadDivider
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.recipients.Recipient
import java.util.Optional

/**
 * Tellomi：气泡分组补齐（tellomi/tellomi#1206 第二段；需求 bubbles-and-motion 3.1）。
 * - 上一条挂着表情回应时，这一条是组头（和组尾那边对称，和 iOS 一致）；
 * - 未读线上下两条不算邻居，分组在未读线处断开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiGroupingTest {

  private val sender = mockk<Recipient>(relaxed = true)
  private val sentAt = 1_790_000_000_000L

  private fun message(id: Long, sentAt: Long, reactions: List<ReactionRecord> = emptyList()): MessageRecord {
    return mockk(relaxed = true) {
      every { this@mockk.id } returns id
      every { isUpdate } returns false
      every { timestamp } returns sentAt
      every { dateSent } returns sentAt
      every { fromRecipient } returns sender
      every { isSecure } returns true
      every { isOutgoing } returns false
      every { this@mockk.reactions } returns reactions
    }
  }

  private fun shapeOf(current: MessageRecord, previous: MessageRecord?, next: MessageRecord?): V2ConversationItemShape.MessageShape {
    val context = mockk<V2ConversationContext>(relaxed = true) {
      every { displayMode } returns ConversationItemDisplayMode.Standard
      every { getPreviousMessage(0) } returns previous
      every { getNextMessage(0) } returns next
    }
    return V2ConversationItemShape(context).setMessageShape(current, isGroupThread = false, adapterPosition = 0)
  }

  @Test
  fun `a message right after one with a reaction starts a new group`() {
    val previous = message(1, sentAt, reactions = listOf(mockk(relaxed = true)))
    val current = message(2, sentAt + 10_000)
    val next = message(3, sentAt + 20_000)

    assertThat(shapeOf(current, previous, next)).isEqualTo(V2ConversationItemShape.MessageShape.START)
  }

  @Test
  fun `without reactions the same three messages stay one group`() {
    val previous = message(1, sentAt)
    val current = message(2, sentAt + 10_000)
    val next = message(3, sentAt + 20_000)

    assertThat(shapeOf(current, previous, next)).isEqualTo(V2ConversationItemShape.MessageShape.MIDDLE)
  }

  /** 旧版渲染（媒体、引用、链接预览等走它）同一条规则；判断是 View 里的私有方法，用反射调真实代码。 */
  @Test
  fun `the older renderer also starts a new group after a message with a reaction`() {
    val item = ConversationItem(ApplicationProvider.getApplicationContext())
    val isStartOfMessageCluster = ConversationItem::class.java
      .getDeclaredMethod("isStartOfMessageCluster", MessageRecord::class.java, Optional::class.java, Boolean::class.javaPrimitiveType)
      .apply { isAccessible = true }
    val current = message(2, sentAt + 10_000)

    val afterReaction = isStartOfMessageCluster.invoke(item, current, Optional.of(message(1, sentAt, reactions = listOf(mockk(relaxed = true)))), false) as Boolean
    val afterPlain = isStartOfMessageCluster.invoke(item, current, Optional.of(message(1, sentAt)), false) as Boolean

    assertThat(afterReaction).isTrue()
    assertThat(afterPlain).isFalse()
  }

  @Test
  fun `the two messages around the unread divider are not neighbours`() {
    val read = message(1, sentAt)
    val firstUnread = message(2, sentAt + 10_000)

    assertThat(tellomiIsSplitByUnreadDivider(newer = firstUnread, anchorId = 2)).isTrue()
    assertThat(tellomiIsSplitByUnreadDivider(newer = read, anchorId = 2)).isFalse()
    assertThat(tellomiIsSplitByUnreadDivider(newer = firstUnread, anchorId = -1)).isFalse()
    assertThat(tellomiIsSplitByUnreadDivider(newer = null, anchorId = 2)).isFalse()
  }
}
