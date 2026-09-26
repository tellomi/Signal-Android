/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.util.requireLong
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.paged.ContactSearchConfiguration
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.contacts.paged.ContactSearchPagedDataSourceRepository
import org.thoughtcrime.securesms.conversationlist.model.ConversationFilter
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil

/** 转发网格（tellomi/tellomi#1259）里的一格：一个能转发过去的聊天。 */
data class TellomiForwardTarget(
  val recipient: Recipient,
  val isSavedMessages: Boolean
) {
  val id: RecipientId get() = recipient.id
  val key: ContactSearchKey.RecipientSearchKey get() = ContactSearchKey.RecipientSearchKey(recipient.id, false)

  /** 网格里头像下面的名字：人用短名（同 Telegram 的 compactDisplayTitle），群用群名，自己是「我的收藏」 */
  fun shortName(context: Context): String {
    return if (isSavedMessages) context.getString(R.string.note_to_self) else recipient.getShortDisplayName(context)
  }

  /** 副标题与发出后的提示用的名字 */
  fun fullName(context: Context): String {
    return if (isSavedMessages) context.getString(R.string.note_to_self) else recipient.getDisplayName(context)
  }
}

/** 搜索态（F-9）的结果，按需求的顺序分组：我的收藏 · 聊天 · 联系人（含没聊过的）· 群组。 */
data class TellomiForwardSearchResults(
  val savedMessages: TellomiForwardTarget? = null,
  val chats: List<TellomiForwardTarget> = emptyList(),
  val contacts: List<TellomiForwardTarget> = emptyList(),
  val groups: List<TellomiForwardTarget> = emptyList()
) {
  val isEmpty: Boolean get() = savedMessages == null && chats.isEmpty() && contacts.isEmpty() && groups.isEmpty()
}

/**
 * 转发网格的候选聊天（tellomi/tellomi#1259 F-5 / F-9）。
 *
 * 第一格固定「我的收藏」——沿用 #1174 的 [ContactSearchPagedDataSourceRepository.getRecents]（带上自己时把自己放第一行，
 * 会话还没建过、新注册账号也有）；之后与聊天列表同序：置顶的在前，其余按最后一条消息从新到旧，归档的排最后，最多 [MAX_CHATS] 个。
 * 不出现：自己不能发言的群（含只有管理员能发言的群）、已退出 / 被移出的群、拉黑的、隐藏的、还没接受的消息请求；
 * 「动态」（Signal Story）不进网格（owner D6）。
 */
class TellomiForwardTargetsRepository(
  private val context: Context,
  private val contactSearchRepository: ContactSearchPagedDataSourceRepository = ContactSearchPagedDataSourceRepository(context)
) {

  companion object {
    const val MAX_CHATS = 150
    const val MAX_RECENT_CONTACTS = 12
  }

  @WorkerThread
  fun load(maxChats: Int = MAX_CHATS): List<TellomiForwardTarget> {
    val targets = mutableListOf<TellomiForwardTarget>()
    val seen = mutableSetOf<RecipientId>()
    var chatCount = 0

    fun consider(recipient: Recipient) {
      if (recipient.isSelf) {
        if (seen.add(recipient.id)) {
          targets.add(0, TellomiForwardTarget(recipient, isSavedMessages = true))
        }
        return
      }
      if (!seen.add(recipient.id) || !canForwardTo(recipient)) {
        return
      }
      targets += TellomiForwardTarget(recipient, isSavedMessages = false)
      chatCount++
    }

    // 上限只数能转的聊天，每条路径只在循环条件里查一次。查询不在 SQL 里限条数（0 = 不限）：前面的行可能被过滤掉
    // （消息请求、只有管理员能发言的群），限了条数就会少收未归档的、提前拿归档的补
    val recents = ContactSearchConfiguration.Section.Recents(
      limit = 0,
      mode = ContactSearchConfiguration.Section.Recents.Mode.ALL,
      includeInactiveGroups = false,
      includeGroupsV1 = false,
      includeSms = false,
      includeSelf = true,
      includeHeader = false
    )
    contactSearchRepository.getRecents(recents)?.use { cursor ->
      while (chatCount < maxChats && cursor.moveToNext()) {
        consider(contactSearchRepository.getRecipientFromThreadCursor(cursor))
      }
    }

    // 归档的排最后
    SignalDatabase.threads.getArchivedConversationList(ConversationFilter.OFF).use { cursor ->
      while (chatCount < maxChats && cursor.moveToNext()) {
        consider(Recipient.resolved(RecipientId.from(cursor.requireLong(ThreadTable.RECIPIENT_ID))))
      }
    }

    return targets
  }

  /** 搜索态空查询时的「最近联系人」一排：网格里的一对一聊天（不含我的收藏和群），按网格顺序。 */
  fun recentContacts(targets: List<TellomiForwardTarget>): List<TellomiForwardTarget> {
    return targets.filter { !it.isSavedMessages && !it.recipient.isGroup }.take(MAX_RECENT_CONTACTS)
  }

  /**
   * 搜索（F-9）。「我的收藏」按它的名字匹配（「收藏」也能搜到，同上游 IncludeWithRemap）；人按名字 / 用户名 / 号码（同上游联系人搜索），
   * 群按群名。网格里有的聊天按网格顺序排在「聊天」组；没聊过的人进「联系人」，网格里没有的群进「群组」，同一个聊天只出现一次。
   */
  @WorkerThread
  fun search(query: String, chats: List<TellomiForwardTarget>): TellomiForwardSearchResults {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
      return TellomiForwardSearchResults()
    }

    val savedMessages = chats.firstOrNull { it.isSavedMessages }
      ?.takeIf { context.getString(R.string.note_to_self).contains(trimmed, ignoreCase = true) }

    val matchedContacts = linkedSetOf<RecipientId>()
    SignalDatabase.recipients.querySignalContacts(RecipientTable.ContactSearchQuery(trimmed, RecipientTable.IncludeSelfMode.Exclude))?.use { cursor ->
      while (cursor.moveToNext()) {
        matchedContacts += RecipientId.from(cursor.requireLong(RecipientTable.ID))
      }
    }

    val matchedGroups = linkedSetOf<RecipientId>()
    SignalDatabase.groups.queryGroupsByTitle(trimmed, includeInactive = false, excludeV1 = true, excludeMms = true).use { reader ->
      while (true) {
        val record = reader.getNext() ?: break
        matchedGroups += record.recipientId
      }
    }

    val chatIds = chats.map { it.id }.toSet()
    val matchedChats = chats.filter { !it.isSavedMessages && (it.id in matchedContacts || it.id in matchedGroups) }

    val otherContacts = matchedContacts
      .filterNot { it in chatIds }
      .map { Recipient.resolved(it) }
      .filter { canForwardTo(it) }
      .map { TellomiForwardTarget(it, isSavedMessages = false) }

    val otherGroups = matchedGroups
      .filterNot { it in chatIds }
      .map { Recipient.resolved(it) }
      .filter { canForwardTo(it) }
      .map { TellomiForwardTarget(it, isSavedMessages = false) }

    return TellomiForwardSearchResults(
      savedMessages = savedMessages,
      chats = matchedChats,
      contacts = otherContacts,
      groups = otherGroups
    )
  }

  /** 这个聊天能不能进网格：能发言、没拉黑没隐藏、消息请求已接受。 */
  @WorkerThread
  fun canForwardTo(recipient: Recipient): Boolean {
    if (recipient.isBlocked || recipient.isHidden || recipient.isReleaseNotes) {
      return false
    }

    if (recipient.isGroup) {
      if (!recipient.isPushV2Group) {
        return false
      }
      val group = SignalDatabase.groups.getGroup(recipient.requireGroupId()).orElse(null) ?: return false
      if (!group.isActive) {
        return false
      }
      if (group.isAnnouncementGroup && !group.isAdmin(Recipient.self())) {
        return false
      }
    } else if (!recipient.isRegistered) {
      return false
    }

    val threadId = SignalDatabase.threads.getThreadIdFor(recipient.id)
    return RecipientUtil.isMessageRequestAccepted(threadId, recipient)
  }
}
