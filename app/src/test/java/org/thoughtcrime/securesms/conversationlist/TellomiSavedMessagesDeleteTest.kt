/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Tellomi（tellomi/tellomi#1174，需求 official-account-and-saved §3.2「删除」）：只删「我的收藏」时，确认框说清楚删的是什么。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiSavedMessagesDeleteTest {

  private val context: Application
    get() = ApplicationProvider.getApplicationContext()

  private val savedMessages = Recipient(id = RecipientId.from(1), isResolving = false, isSelf = true)
  private val buddy = Recipient(id = RecipientId.from(2), isResolving = false)

  @Test
  fun `only saved messages on its own gets its own wording`() {
    assertThat(TellomiSavedMessages.isOnlySavedMessages(listOf(savedMessages))).isTrue()
    assertThat(TellomiSavedMessages.isOnlySavedMessages(listOf(buddy))).isFalse()
    assertThat(TellomiSavedMessages.isOnlySavedMessages(listOf(savedMessages, buddy))).isFalse()
  }

  @Test
  fun `the wording says whether linked devices lose it too`() {
    assertThat(context.getString(R.string.ConversationListFragment__tellomi_delete_saved_messages_title)).isEqualTo("Delete Saved Messages?")
    assertThat(context.getString(TellomiSavedMessages.deleteMessage(isMultiDevice = false)))
      .isEqualTo("Everything in Saved Messages will be deleted from this device. This can't be undone.")
    assertThat(context.getString(TellomiSavedMessages.deleteMessage(isMultiDevice = true)))
      .isEqualTo("Everything in Saved Messages will be deleted from this device and your linked devices. This can't be undone.")
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `simplified chinese wording`() {
    assertThat(context.getString(R.string.ConversationListFragment__tellomi_delete_saved_messages_title)).isEqualTo("删除「我的收藏」？")
    assertThat(context.getString(TellomiSavedMessages.deleteMessage(isMultiDevice = true))).isEqualTo("「我的收藏」里的内容会从这台设备和已关联设备上删除，无法恢复。")
  }

  @Test
  @Config(qualifiers = "yue")
  fun `cantonese wording`() {
    assertThat(context.getString(R.string.ConversationListFragment__tellomi_delete_saved_messages_title)).isEqualTo("刪除「我的收藏」？")
    assertThat(context.getString(TellomiSavedMessages.deleteMessage(isMultiDevice = false))).isEqualTo("「我的收藏」入面嘅嘢會喺呢部機度刪除，刪咗就搵唔返。")
  }
}
