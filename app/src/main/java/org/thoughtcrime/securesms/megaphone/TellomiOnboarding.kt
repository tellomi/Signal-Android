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

    val recipientIds = conversations
      .filter { it.type == Conversation.Type.THREAD }
      .map { it.threadRecord.recipient.id }

    onConversations(recipientIds, Recipient.self().id, SignalStore.releaseChannel.releaseChannelRecipientId)
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
   * 「我的二维码」卡：有用户名进二维码页；没有就先去设用户名。注册时用户名是选填的，而二维码页
   * （`UsernameLinkSettingsViewModel`）取的是 `SignalStore.account.username!!`，没有用户名直接进会闪退——上游只在有用户名时才露出这个入口。
   */
  @JvmStatic
  fun myQrCodeIntent(context: Context, hasUsername: Boolean = SignalStore.account.username != null): Intent {
    return if (hasUsername) {
      AppSettingsActivity.usernameLinkSettings(context)
    } else {
      EditProfileActivity.getIntentForUsernameEdit(context)
    }
  }

  private fun showsFindFriends(): Boolean {
    val onboarding = SignalStore.onboarding
    return onboarding.shouldShowFindByUsername() || onboarding.shouldShowMyQrCode() || onboarding.shouldShowInviteFriends()
  }
}
