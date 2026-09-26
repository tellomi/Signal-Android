/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.recipients

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.sms.MessageSender

/**
 * Tellomi（owner 2026-09-26）：「我的收藏」（自己的会话）不套「新聊天默认限时」——收藏的东西不该到时自己消失。
 * 别的一对一新会话照上游：第一条消息时套上默认计时，并发一条计时更新。
 */
class TellomiSavedMessagesUniversalTimerTest {

  private val context = mockk<Context>(relaxed = true)
  private val mockMessageTable = mockk<MessageTable>()
  private val mockRecipientTable = mockk<RecipientTable>()
  private val mockThreadTable = mockk<ThreadTable>()

  @Before
  fun setUp() {
    mockkObject(SignalStore)
    val settings = mockk<SettingsValues> {
      every { universalExpireTimer } returns 60 * 60
    }
    every { SignalStore.settings } returns settings

    mockkObject(SignalDatabase.Companion)
    every { SignalDatabase.instance } returns mockk {
      every { messageTable } returns mockMessageTable
      every { recipientTable } returns mockRecipientTable
      every { threadTable } returns mockThreadTable
    }
    every { mockMessageTable.canSetUniversalTimer(any()) } returns true
    every { mockRecipientTable.setExpireMessagesAndIncrementVersion(any(), any()) } returns 7
    every { mockThreadTable.getOrCreateThreadIdFor(any<Recipient>()) } returns 1L

    mockkStatic(MessageSender::class)
    every { MessageSender.send(any(), any(), any(), any(), any(), any()) } returns 1L
  }

  @After
  fun tearDown() {
    unmockkStatic(MessageSender::class)
    unmockkObject(SignalDatabase.Companion)
    unmockkObject(SignalStore)
  }

  @Test
  fun `saved messages does not get the default timer`() {
    val self = recipient(id = 1, isSelf = true)

    val version = RecipientUtil.setAndSendUniversalExpireTimerIfNecessary(context, self, 1L)

    assertNull("「我的收藏」不套默认限时", version)
    verify(exactly = 0) { mockRecipientTable.setExpireMessagesAndIncrementVersion(any(), any()) }
    verify(exactly = 0) { MessageSender.send(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `another new one to one chat still gets the default timer`() {
    val other = recipient(id = 2, isSelf = false)

    val version = RecipientUtil.setAndSendUniversalExpireTimerIfNecessary(context, other, 1L)

    assertEquals(7, version)
    verify { mockRecipientTable.setExpireMessagesAndIncrementVersion(RecipientId.from(2), 60 * 60) }
    verify { MessageSender.send(any(), any(), 1L, any(), any(), any()) }
  }

  private fun recipient(id: Long, isSelf: Boolean): Recipient {
    return mockk(relaxed = true) {
      every { this@mockk.id } returns RecipientId.from(id)
      every { this@mockk.isSelf } returns isSelf
      every { isGroup } returns false
      every { isDistributionList } returns false
      every { expiresInSeconds } returns 0
      every { isRegistered } returns true
    }
  }
}
