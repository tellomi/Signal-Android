/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.contacts.paged

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.requireLong
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * Tellomi（tellomi/tellomi#1174，需求 official-account-and-saved §3.2 第 3 条）：转发 / 分享面板里「我的收藏」固定第一位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiSavedMessagesForwardTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private val repository: ContactSearchPagedDataSourceRepository
    get() = ContactSearchPagedDataSourceRepository(ApplicationProvider.getApplicationContext<Application>())

  private fun recents(includeSelf: Boolean): List<RecipientId> {
    // 测试里造的联系人没标成已注册，includeSms 让「最近」不按注册状态筛（要测的是置顶，不是这条筛选）
    val section = ContactSearchConfiguration.Section.Recents(includeSelf = includeSelf, includeSms = true, includeHeader = true)
    return repository.getRecents(section)!!.use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          add(RecipientId.from(cursor.requireLong(ThreadTable.RECIPIENT_ID)))
        }
      }
    }
  }

  private fun chatWith(id: RecipientId) {
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(id))
    recipients.insertOutgoingMessage(id)
    SignalDatabase.threads.update(threadId, false)
  }

  @Test
  fun `saved messages comes first in the forward sheet even before its chat exists`() {
    val buddy = recipients.createRecipient("Buddy")
    chatWith(buddy)

    assertThat(recents(includeSelf = true)).containsExactly(recipients.self, buddy)
  }

  @Test
  fun `saved messages is listed once even when it is also the most recent chat`() {
    val buddy = recipients.createRecipient("Buddy")
    chatWith(buddy)
    chatWith(recipients.self)

    assertThat(recents(includeSelf = true)).containsExactly(recipients.self, buddy)
  }

  @Test
  fun `lists that do not ask for self stay as they were`() {
    val buddy = recipients.createRecipient("Buddy")
    chatWith(buddy)
    chatWith(recipients.self)

    assertThat(recents(includeSelf = false)).containsExactly(buddy)
  }
}
