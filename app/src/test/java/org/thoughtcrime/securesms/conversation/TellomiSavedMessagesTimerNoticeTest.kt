/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule

/**
 * Tellomi（owner 2026-09-26）：「我的收藏」不套「新聊天默认限时」，所以空的「我的收藏」里也不该出那条
 * 「给对方发消息时会把限时设为 X」的提示。别的一对一新会话照上游出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiSavedMessagesTimerNoticeTest {

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val mockMessageTable = mockk<MessageTable>()
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
      every { threadTable } returns mockThreadTable
    }
    every { mockThreadTable.getConversationMetadata(any()) } returns ThreadTable.ConversationMetadata(lastSeen = 0, hasSent = false, lastScrolled = 0, unreadCount = 0)
    every { mockMessageTable.getMessageCountForThread(any<Long>()) } returns 0
    every { mockMessageTable.canSetUniversalTimer(any()) } returns true

    mockkStatic(RecipientUtil::class)
    every { RecipientUtil.isMessageRequestAccepted(any<Long>()) } returns true
    every { RecipientUtil.isRecipientHidden(any()) } returns false
  }

  @After
  fun tearDown() {
    unmockkStatic(RecipientUtil::class)
    unmockkObject(SignalDatabase.Companion)
    unmockkObject(SignalStore)
  }

  @Test
  fun `empty saved messages does not announce the default timer`() {
    val data = ConversationRepository().getConversationData(1L, recipient(id = 1, isSelf = true), -1)

    assertFalse("「我的收藏」不出默认限时的提示", data.showUniversalExpireTimerMessage)
  }

  @Test
  fun `another empty one to one chat still announces the default timer`() {
    val data = ConversationRepository().getConversationData(2L, recipient(id = 2, isSelf = false), -1)

    assertTrue(data.showUniversalExpireTimerMessage)
  }

  private fun recipient(id: Long, isSelf: Boolean): Recipient {
    return mockk(relaxed = true) {
      every { this@mockk.id } returns RecipientId.from(id)
      every { this@mockk.isSelf } returns isSelf
      every { isGroup } returns false
      every { isPushV2Group } returns false
      every { expiresInSeconds } returns 0
      every { isRegistered } returns true
    }
  }
}
