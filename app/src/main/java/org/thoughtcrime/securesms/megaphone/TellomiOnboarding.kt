/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.megaphone

import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.conversationlist.model.Conversation
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.profiles.manage.EditProfileActivity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Tellomi（tellomi/tellomi#1218 F-02）：首屏「开始使用」里的找朋友三条路（搜索用户名 / 我的二维码 / 邀请朋友），
 * 在出现第一个真人会话后自动收起。
 *
 * 真人会话 = 不是自己（「笔记」）、也不是官方账号（release channel）的会话；消息请求也算——对方是个真人。
 */
object TellomiOnboarding {

  private val changes = MutableStateFlow(0)

  /**
   * 这里每改一次找朋友的开关就加一。首屏卡片收集它、马上重读开关：正看着列表时收到第一个真人消息，卡片当场收起，
   * 不用等下次回到前台。
   */
  val findFriendsChanges: StateFlow<Int> = changes.asStateFlow()

  @JvmStatic
  fun hasRealConversation(recipientIds: Collection<RecipientId>, selfId: RecipientId?, officialAccountId: RecipientId?): Boolean {
    return recipientIds.any { it != selfId && it != officialAccountId }
  }

  @JvmStatic
  fun onConversationListChanged(conversations: List<Conversation>) {
    if (!showsFindFriends()) {
      return
    }

    onConversations(threadRecipientIds(conversations), Recipient.self().id, SignalStore.releaseChannel.releaseChannelRecipientId)
  }

  /**
   * 列表里真正的会话的对方。列表空时的占位项、页眉、页脚也是 [Conversation]，它们的 recipient 都是 `Recipient.UNKNOWN`，
   * 不滤掉就会被当成真人会话，新用户一进首屏三张卡就收起（taishi 中转包 7）。
   */
  @VisibleForTesting
  internal fun threadRecipientIds(conversations: List<Conversation>): List<RecipientId> {
    return conversations
      .filter { it.type == Conversation.Type.THREAD }
      .map { it.threadRecord.recipient.id }
  }

  @VisibleForTesting
  internal fun onConversations(recipientIds: Collection<RecipientId>, selfId: RecipientId?, officialAccountId: RecipientId?) {
    if (showsFindFriends() && hasRealConversation(recipientIds, selfId, officialAccountId)) {
      SignalStore.onboarding.clearFindFriends()
      changes.update { it + 1 }
    }
  }

  /** 列表空了（例如删光了会话）：三条路重新出现，照上游对「邀请朋友」「新建群组」的做法。 */
  @JvmStatic
  fun onConversationListEmpty() {
    SignalStore.onboarding.setShowFindByUsername(true)
    SignalStore.onboarding.setShowMyQrCode(true)
    changes.update { it + 1 }
  }

  /**
   * 「我的二维码」卡去哪，分三支，照上游自己的两个入口（资料页、用户名失步横幅）：
   * - 没有用户名：先去设用户名。注册时用户名是选填的，而二维码页（`UsernameLinkSettingsViewModel`）取的是
   *   `SignalStore.account.username!!`，没有用户名直接进会闪退；
   * - 用户名已不再分给这个账号（`USERNAME_AND_LINK_CORRUPTED`）：去修复页（taishi 中转包 7）。上游资料页在这个状态下
   *   不露二维码入口，失步横幅走的也是 `usernameRecovery`；
   * - 其余（包括只有链接坏了，二维码页自己会重置链接）：二维码页。
   */
  @JvmStatic
  fun myQrCodeIntent(context: Context): Intent {
    val account = SignalStore.account
    return when {
      account.username == null -> EditProfileActivity.getIntentForUsernameEdit(context)
      account.usernameSyncState == AccountValues.UsernameSyncState.USERNAME_AND_LINK_CORRUPTED -> AppSettingsActivity.usernameRecovery(context)
      else -> AppSettingsActivity.usernameLinkSettings(context)
    }
  }

  private fun showsFindFriends(): Boolean {
    val onboarding = SignalStore.onboarding
    return onboarding.shouldShowFindByUsername() || onboarding.shouldShowMyQrCode() || onboarding.shouldShowInviteFriends()
  }
}
