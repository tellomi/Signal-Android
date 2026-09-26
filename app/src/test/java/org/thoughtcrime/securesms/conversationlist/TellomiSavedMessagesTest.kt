/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.requireLong
import org.thoughtcrime.securesms.components.settings.app.chats.folders.ChatFolderRecord
import org.thoughtcrime.securesms.conversationlist.model.ConversationFilter
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.util.TextSecurePreferences

/**
 * Tellomi（tellomi/tellomi#1174，需求 official-account-and-saved §3.2）：「我的收藏」默认在聊天列表里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiSavedMessagesTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private val allChats = ChatFolderRecord(folderType = ChatFolderRecord.FolderType.ALL)

  private val context: Application
    get() = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    TextSecurePreferences.getSharedPreferences(context).edit().clear().commit()
  }

  private fun listedThreadIds(): List<Long> {
    return SignalDatabase.threads.getUnarchivedConversationList(ConversationFilter.OFF, false, 0, 50, allChats).use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          add(cursor.requireLong(ThreadTable.ID))
        }
      }
    }
  }

  @Test
  fun `saved messages is listed without any message, while other empty chats are not`() {
    val buddyThread = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipients.createRecipient("Buddy")))
    SignalDatabase.threads.markAsActiveEarly(buddyThread)

    val savedThread = TellomiSavedMessages.list()

    assertThat(listedThreadIds()).containsExactly(savedThread)
    assertThat(SignalDatabase.threads.getUnarchivedConversationListCount(ConversationFilter.OFF)).isEqualTo(1)
    assertThat(SignalDatabase.threads.getUnarchivedConversationListCount(ConversationFilter.OFF, allChats)).isEqualTo(1)
  }

  @Test
  fun `it is put in the list once, stays out after the user deletes it, and the settings entry brings it back`() {
    TellomiSavedMessages.ensureListedOnce(context)
    val threadId = SignalDatabase.threads.getThreadIdFor(recipients.self)!!
    assertThat(listedThreadIds()).containsExactly(threadId)

    SignalDatabase.threads.deleteConversation(threadId)
    TellomiSavedMessages.ensureListedOnce(context)
    assertThat(listedThreadIds()).isEmpty()

    TellomiSavedMessages.list()
    assertThat(listedThreadIds()).containsExactly(threadId)
  }

  @Test
  fun `deleting the last message inside keeps it in the list with an empty preview, like a pinned chat`() {
    val threadId = TellomiSavedMessages.list()
    val messageId = recipients.insertOutgoingMessage(recipients.self)
    assertThat(listedThreadIds()).containsExactly(threadId)

    // 上游在会话删空时整条删掉（ACTIVE = 0，还会同步到已关联设备）；「我的收藏」只是清空，还在列表里
    SignalDatabase.messages.deleteMessage(messageId)

    assertThat(listedThreadIds()).containsExactly(threadId)
    assertThat(SignalDatabase.threads.getThreadRecord(threadId)!!.snippet.orEmpty()).isEmpty()
  }
}
