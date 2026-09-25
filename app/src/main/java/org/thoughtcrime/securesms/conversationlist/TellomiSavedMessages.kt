/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.content.Context
import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences

/**
 * Tellomi：「我的收藏」（= 上游的「备忘录」，自己的会话）默认在聊天列表里（tellomi/tellomi#1174，需求 official-account-and-saved §3.2）。
 *
 * 聊天列表只列有消息的会话（置顶的除外）；ThreadTable 放行了没有消息的自己的会话，这里负责让它「在」：
 * - 第一次进聊天列表时建好会话并标成 ACTIVE，只做一次（新账号一进来就有；删掉之后不会自己回来）；
 * - 从设置页的「我的收藏」进去时再标一次，删掉之后从那里回来就又在列表里了。
 */
object TellomiSavedMessages {

  private const val PREF_LISTED_ONCE = "tellomi.saved_messages_listed_once"

  @JvmStatic
  @WorkerThread
  fun ensureListedOnce(context: Context) {
    if (!Recipient.isSelfSet || TextSecurePreferences.getBooleanPreference(context, PREF_LISTED_ONCE, false)) {
      return
    }

    list()
    TextSecurePreferences.setBooleanPreference(context, PREF_LISTED_ONCE, true)
  }

  /** 建好「我的收藏」会话并让它出现在聊天列表里，返回会话 id。 */
  @JvmStatic
  @WorkerThread
  fun list(): Long {
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.self())
    SignalDatabase.threads.markAsActiveEarly(threadId)
    AppDependencies.databaseObserver.notifyConversationListListeners()
    return threadId
  }
}
