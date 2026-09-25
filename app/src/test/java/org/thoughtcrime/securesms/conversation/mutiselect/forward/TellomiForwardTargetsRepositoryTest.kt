/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.storageservice.storage.protos.groups.Member
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.signal.storageservice.storage.protos.groups.local.DecryptedMember
import org.signal.storageservice.storage.protos.groups.local.EnabledState
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import java.util.UUID
import kotlin.random.Random

/**
 * Tellomi（tellomi/tellomi#1259，需求 media-album-forward-picker.md F-5 / F-9、判据「转发面板」2）：转发网格的候选聊天。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiForwardTargetsRepositoryTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val repository: TellomiForwardTargetsRepository get() = TellomiForwardTargetsRepository(context)

  private fun chat(name: String, sentAt: Long): RecipientId {
    val id = recipients.createRecipient(name)
    send(id, sentAt)
    return id
  }

  /** 发一条并刷新会话（会话的「有效消息数」「最后时间」要 update 才算进聊天列表） */
  private fun send(id: RecipientId, sentAt: Long) {
    recipients.insertOutgoingMessage(id, sentTimeMillis = sentAt)
    SignalDatabase.threads.update(threadOf(id), false)
  }

  private fun receive(id: RecipientId) {
    recipients.insertIncomingMessage(id)
    SignalDatabase.threads.update(SignalDatabase.threads.getOrCreateThreadIdFor(id, false), false)
  }

  private fun threadOf(id: RecipientId): Long = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(id))

  private fun ids(targets: List<TellomiForwardTarget>): List<RecipientId> = targets.map { it.id }

  /** 判据 2：第一格「我的收藏」——会话还没建过（新注册账号）也有，只出现一次。 */
  @Test
  fun `saved messages is the first cell even before its chat exists and only once`() {
    val buddy = chat("Buddy", sentAt = 1_000)

    val first = repository.load()
    assertThat(ids(first)).containsExactly(recipients.self, buddy)
    assertThat(first.first().isSavedMessages).isTrue()
    assertThat(SignalDatabase.threads.getThreadIdFor(recipients.self)).isNull()

    send(recipients.self, sentAt = 2_000)
    assertThat(ids(repository.load())).containsExactly(recipients.self, buddy)
  }

  /** F-5：之后与聊天列表同序——置顶的在前，其余新的在前，归档的排最后；最多 N 个（不算「我的收藏」）。 */
  @Test
  fun `chats follow the chat list with pinned first archived last and a cap`() {
    val old = chat("Old", sentAt = 1_000)
    val archived = chat("Archived", sentAt = 2_000)
    val pinned = chat("Pinned", sentAt = 3_000)
    val group = recipients.createGroup(recipients.createRecipient("Member")).recipientId
    send(group, sentAt = 4_000)
    val newest = chat("Newest", sentAt = 5_000)

    SignalDatabase.threads.pinConversations(listOf(threadOf(pinned)))
    SignalDatabase.threads.setArchived(setOf(threadOf(archived)), true)

    assertThat(ids(repository.load())).containsExactly(recipients.self, pinned, newest, group, old, archived)
    assertThat(ids(repository.load(maxChats = 3))).containsExactly(recipients.self, pinned, newest, group)
    assertThat(TellomiForwardTargetsRepository.MAX_CHATS).isEqualTo(150)
  }

  /** F-5：不出现——拉黑的、没接受的消息请求、已退出的群、只有管理员能发言而我不是管理员的群（归档里的也一样）。 */
  @Test
  fun `chats you cannot post in are left out`() {
    val friend = chat("Friend", sentAt = 1_000)
    val blocked = chat("Blocked", sentAt = 2_000)
    val stranger = recipients.createRecipient("Stranger", profileSharing = false)
    receive(stranger)
    val leftGroup = createGroup(includeSelf = false)
    receive(leftGroup)
    val announcements = createGroup(includeSelf = true, announcementOnly = true)
    send(announcements, sentAt = 3_000)

    SignalDatabase.recipients.setBlocked(blocked, true, System.currentTimeMillis())
    SignalDatabase.threads.setArchived(setOf(threadOf(blocked), threadOf(leftGroup)), true)

    assertThat(ids(repository.load())).containsExactly(recipients.self, friend)
    assertThat(repository.canForwardTo(Recipient.resolved(stranger))).isFalse()
    assertThat(repository.canForwardTo(Recipient.resolved(leftGroup))).isFalse()
    assertThat(repository.canForwardTo(Recipient.resolved(announcements))).isFalse()
    assertThat(repository.canForwardTo(Recipient.resolved(friend))).isTrue()
  }

  /** F-9 空查询：最近联系人一排只有人（不含我的收藏和群），按网格顺序，最多 12 个。 */
  @Test
  fun `recent contacts are people only in grid order`() {
    val people = (1..14).map { chat("Person$it", sentAt = it * 1_000L) }
    val group = recipients.createGroup(people.first()).recipientId
    send(group, sentAt = 100_000)

    val recent = repository.recentContacts(repository.load())

    assertThat(ids(recent)).isEqualTo(people.reversed().take(TellomiForwardTargetsRepository.MAX_RECENT_CONTACTS))
  }

  /** F-9：我的收藏按名字（「收藏」也能搜到）；网格里有的在「聊天」，没聊过的人在「联系人」，同一个只出现一次。 */
  @Test
  fun `search groups saved messages chats and contacts once each`() {
    val alice = chat("Alice Chat", sentAt = 1_000)
    val aliceNeverChatted = recipients.createRecipient("Alice Contact")
    chat("Bob", sentAt = 2_000)
    val chats = repository.load()

    val saved = repository.search(context.getString(org.thoughtcrime.securesms.R.string.note_to_self).takeLast(2), chats)
    assertThat(saved.savedMessages?.id).isEqualTo(recipients.self)

    val results = repository.search("Alice", chats)
    assertThat(results.savedMessages).isNull()
    assertThat(ids(results.chats)).containsExactly(alice)
    assertThat(ids(results.contacts)).containsExactly(aliceNeverChatted)
    assertThat(results.groups).isEmpty()

    assertThat(repository.search("zzzz", chats).isEmpty).isTrue()
  }

  /** 自己不在里面的群（已退出 / 被移出），或只有管理员能发言而我只是普通成员的群。 */
  private fun createGroup(includeSelf: Boolean, announcementOnly: Boolean = false): RecipientId {
    val other = Recipient.resolved(recipients.createRecipient("Other")).requireAci()
    val members = buildList {
      add(DecryptedMember(aciBytes = other.toByteString(), role = Member.Role.ADMINISTRATOR))
      if (includeSelf) {
        add(DecryptedMember(aciBytes = recipients.selfAci.toByteString(), role = Member.Role.DEFAULT))
      }
    }
    val decryptedGroup = DecryptedGroup.Builder()
      .members(members)
      .revision(0)
      .title("Group ${UUID.randomUUID()}")
      .isAnnouncementGroup(if (announcementOnly) EnabledState.ENABLED else EnabledState.DISABLED)
      .build()
    val groupId: GroupId.V2 = SignalDatabase.groups.create(GroupMasterKey(Random.nextBytes(GroupMasterKey.SIZE)), decryptedGroup, null)!!
    val recipientId = SignalDatabase.recipients.getOrInsertFromGroupId(groupId)
    SignalDatabase.recipients.setProfileSharing(recipientId, true)
    return recipientId
  }
}
