/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.recipients.Recipient
import java.util.Optional

/**
 * Tellomi：群里名字 / 头像跟气泡分组同一规则（tellomi/tellomi#1206 第二段；需求 bubbles-and-motion 3.1）。
 * 旧版渲染（图片、相册、链接、引用、语音走它）里，上一条挂着表情回应时这一条画成组头，
 * 那名字也要在这一条上；挂着回应的那条是组尾，头像要在它旁边。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiGroupSenderTest {

  private val sender = mockk<Recipient>(relaxed = true)
  private val sentAt = 1_790_000_000_000L

  private fun message(id: Long, sentAt: Long, reactions: List<ReactionRecord> = emptyList()): MessageRecord {
    return mockk(relaxed = true) {
      every { this@mockk.id } returns id
      every { isUpdate } returns false
      every { timestamp } returns sentAt
      every { dateSent } returns sentAt
      every { fromRecipient } returns sender
      every { isOutgoing } returns false
      every { this@mockk.reactions } returns reactions
    }
  }

  private fun reacted(id: Long, sentAt: Long) = message(id, sentAt, reactions = listOf(mockk(relaxed = true)))

  @Test
  fun `in a group, the message after one with a reaction shows the sender name`() {
    val current = message(2, sentAt + 10_000)

    assertThat(ConversationItem.showsGroupSenderName(current, Optional.of(reacted(1, sentAt)), false)).isTrue()
    assertThat(ConversationItem.showsGroupSenderName(current, Optional.of(message(1, sentAt)), false)).isFalse()
  }

  @Test
  fun `in a group, the message with a reaction shows the avatar even if the same sender continues`() {
    val next = message(2, sentAt + 10_000)

    assertThat(ConversationItem.showsGroupSenderAvatar(reacted(1, sentAt), Optional.of(next), false)).isTrue()
    assertThat(ConversationItem.showsGroupSenderAvatar(message(1, sentAt), Optional.of(next), false)).isFalse()
  }

  @Test
  fun `the rest of the upstream rules are unchanged`() {
    val first = message(1, sentAt)
    val second = message(2, sentAt + 10_000)
    val muchLater = message(3, sentAt + 10 * 60_000)

    assertThat(ConversationItem.showsGroupSenderName(first, Optional.empty(), false)).isTrue()
    assertThat(ConversationItem.showsGroupSenderName(muchLater, Optional.of(second), false)).isTrue()
    assertThat(ConversationItem.showsGroupSenderName(second, Optional.of(first), true)).isTrue()

    assertThat(ConversationItem.showsGroupSenderAvatar(second, Optional.empty(), false)).isTrue()
    assertThat(ConversationItem.showsGroupSenderAvatar(second, Optional.of(muchLater), false)).isTrue()
    assertThat(ConversationItem.showsGroupSenderAvatar(first, Optional.of(second), true)).isTrue()
  }
}
