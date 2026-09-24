/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.megaphone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.conversationlist.model.Conversation
import org.thoughtcrime.securesms.conversationlist.model.ConversationReader
import org.thoughtcrime.securesms.database.model.ThreadWithRecipient
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.profiles.manage.EditProfileActivity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.SignalStoreRule
import java.util.Locale

/**
 * Tellomi（tellomi/tellomi#1218 F-02、第 5 条）：首屏「开始使用」= 找朋友三条路（搜索用户名 / 我的二维码 / 邀请朋友）+ 设头像；
 * 上游的「新建群组」「聊天颜色」不出；三条路在出现第一个真人会话后收起。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiOnboardingTest {

  @get:Rule
  val signalStore = SignalStoreRule()

  private val self = RecipientId.from(1)
  private val officialAccount = RecipientId.from(2)
  private val friend = RecipientId.from(3)

  private fun showEverything() {
    SignalStore.onboarding.setShowNewGroup(true)
    SignalStore.onboarding.setShowInviteFriends(true)
    SignalStore.onboarding.setShowAppearance(true)
    SignalStore.onboarding.setShowAddPhoto(true)
    SignalStore.onboarding.setShowFindByUsername(true)
    SignalStore.onboarding.setShowMyQrCode(true)
  }

  private fun shownCards(): List<OnboardingListItem> {
    return OnboardingListItem.entries.filter(OnboardingState.DisplayState()::shouldDisplayListItem)
  }

  private fun thread(threadId: Long, recipientId: RecipientId): Conversation {
    return Conversation(
      ThreadWithRecipient.Builder(threadId)
        .setRecipient(Recipient(id = recipientId, isResolving = false))
        .setBody("hi")
        .build()
    )
  }

  /** 两个 intent 去的是同一页：同一个 Activity，extras（起始路由）逐项相同。 */
  private fun assertSameDestination(expected: Intent, actual: Intent) {
    assertEquals(expected.component, actual.component)
    val expectedExtras = expected.extras!!
    val actualExtras = actual.extras!!
    assertEquals(expectedExtras.keySet(), actualExtras.keySet())
    expectedExtras.keySet().forEach {
      @Suppress("DEPRECATION")
      assertEquals(it, expectedExtras.get(it), actualExtras.get(it))
    }
  }

  @Test
  fun `notes to self and the official account are not real conversations`() {
    assertFalse(TellomiOnboarding.hasRealConversation(emptyList(), self, officialAccount))
    assertFalse(TellomiOnboarding.hasRealConversation(listOf(self), self, officialAccount))
    assertFalse(TellomiOnboarding.hasRealConversation(listOf(officialAccount), self, officialAccount))
    assertFalse(TellomiOnboarding.hasRealConversation(listOf(self, officialAccount), self, officialAccount))
    assertTrue(TellomiOnboarding.hasRealConversation(listOf(self, officialAccount, friend), self, officialAccount))
    // 还没拿到官方账号时，别的会话照样算
    assertTrue(TellomiOnboarding.hasRealConversation(listOf(friend), self, null))
  }

  @Test
  fun `find friends paths come first and new group or chat colors never show`() {
    showEverything()

    assertEquals(
      listOf(OnboardingListItem.FIND_BY_USERNAME, OnboardingListItem.MY_QR_CODE, OnboardingListItem.INVITE, OnboardingListItem.ADD_PHOTO),
      shownCards()
    )
  }

  @Test
  fun `the first real conversation folds the three paths but keeps add photo`() {
    showEverything()

    SignalStore.onboarding.clearFindFriends()

    assertEquals(listOf(OnboardingListItem.ADD_PHOTO), shownCards())
    assertTrue(SignalStore.onboarding.hasOnboarding(ApplicationProvider.getApplicationContext()))
  }

  @Test
  fun `only a real conversation folds the paths and tells the cards to re-read`() {
    showEverything()
    val before = TellomiOnboarding.findFriendsChanges.value

    TellomiOnboarding.onConversations(listOf(self, officialAccount), self, officialAccount)

    assertEquals(before, TellomiOnboarding.findFriendsChanges.value)
    assertTrue(SignalStore.onboarding.shouldShowFindByUsername())

    TellomiOnboarding.onConversations(listOf(self, friend), self, officialAccount)

    assertEquals(before + 1, TellomiOnboarding.findFriendsChanges.value)
    assertEquals(listOf(OnboardingListItem.ADD_PHOTO), shownCards())

    // 已经收起了，再来会话不再动
    TellomiOnboarding.onConversations(listOf(friend), self, officialAccount)

    assertEquals(before + 1, TellomiOnboarding.findFriendsChanges.value)
  }

  @Test
  fun `new group or chat colors flags alone do not keep the megaphone alive`() {
    SignalStore.onboarding.setShowNewGroup(true)
    SignalStore.onboarding.setShowAppearance(true)

    assertFalse(SignalStore.onboarding.hasOnboarding(ApplicationProvider.getApplicationContext()))
    assertTrue(OnboardingState.DisplayState().hasNoVisibleContent())
  }

  /** 没设用户名时二维码页会闪退（取的是 `username!!`），先去设用户名。 */
  @Test
  fun `my qr code opens the qr page only when there is a username`() {
    val context = ApplicationProvider.getApplicationContext<Application>()

    assertEquals(EditProfileActivity::class.java.name, TellomiOnboarding.myQrCodeIntent(context).component?.className)

    SignalStore.account.username = "kaixin.01"

    assertSameDestination(AppSettingsActivity.usernameLinkSettings(context), TellomiOnboarding.myQrCodeIntent(context))
  }

  /** taishi 中转包 7：用户名已不再分给这个账号时，照上游转去修复页；只有链接坏了还是二维码页（它自己会重置链接）。 */
  @Test
  fun `my qr code goes to username recovery when the username is out of sync`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    SignalStore.account.username = "kaixin.01"

    SignalStore.account.usernameSyncState = AccountValues.UsernameSyncState.USERNAME_AND_LINK_CORRUPTED
    assertSameDestination(AppSettingsActivity.usernameRecovery(context), TellomiOnboarding.myQrCodeIntent(context))

    SignalStore.account.usernameSyncState = AccountValues.UsernameSyncState.LINK_CORRUPTED
    assertSameDestination(AppSettingsActivity.usernameLinkSettings(context), TellomiOnboarding.myQrCodeIntent(context))

    SignalStore.account.usernameSyncState = AccountValues.UsernameSyncState.IN_SYNC
    assertSameDestination(AppSettingsActivity.usernameLinkSettings(context), TellomiOnboarding.myQrCodeIntent(context))
  }

  /** taishi 中转包 7：列表空时的占位项、页眉、页脚也是 Conversation，recipient 都是 Recipient.UNKNOWN，不能算真人会话。 */
  @Test
  fun `placeholders headers and footers in the list are not conversations`() {
    val placeholders = listOf(
      Conversation.Type.EMPTY,
      Conversation.Type.PINNED_HEADER,
      Conversation.Type.UNPINNED_HEADER,
      Conversation.Type.ARCHIVED_FOOTER,
      Conversation.Type.CONVERSATION_FILTER_EMPTY
    ).map { Conversation(ConversationReader.buildThreadRecordForType(it, 0, false)) }

    assertEquals(emptyList<RecipientId>(), TellomiOnboarding.threadRecipientIds(placeholders))

    val onlyNotes = placeholders + thread(1, self)
    assertFalse(TellomiOnboarding.hasRealConversation(TellomiOnboarding.threadRecipientIds(onlyNotes), self, officialAccount))

    assertEquals(listOf(self, friend), TellomiOnboarding.threadRecipientIds(onlyNotes + thread(2, friend)))
  }

  @Test
  fun `an empty list brings the paths back`() {
    val before = TellomiOnboarding.findFriendsChanges.value

    TellomiOnboarding.onConversationListEmpty()

    assertTrue(SignalStore.onboarding.shouldShowFindByUsername())
    assertTrue(SignalStore.onboarding.shouldShowMyQrCode())
    assertEquals(before + 1, TellomiOnboarding.findFriendsChanges.value)
  }

  /**
   * taishi 中转包 7：zh-TW 的卡片要和点进去的页面同一个说法（「使用者名稱」）；粤语两张卡原来没有译文，会显示英文。
   */
  @Test
  fun `card titles match the pages they open in zh-TW and are translated for cantonese`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    fun localized(tag: String): Context {
      return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
    }

    val taiwan = localized("zh-TW")
    assertTrue(taiwan.getString(R.string.FindByActivity__find_by_username).contains("使用者名稱"))
    assertTrue(taiwan.getString(R.string.TellomiOnboarding__search_by_username).contains("使用者名稱"))

    val english = localized("en")
    val cantonese = localized("yue")
    assertNotEquals(english.getString(R.string.TellomiOnboarding__search_by_username), cantonese.getString(R.string.TellomiOnboarding__search_by_username))
    assertNotEquals(english.getString(R.string.TellomiOnboarding__my_qr_code), cantonese.getString(R.string.TellomiOnboarding__my_qr_code))
  }
}
