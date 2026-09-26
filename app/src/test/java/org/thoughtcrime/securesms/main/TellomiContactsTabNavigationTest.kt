/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.compose.split.ListDetailEvents
import org.thoughtcrime.securesms.conversation.ConversationArgs
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.ConversationIntents.ConversationScreenType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule

/**
 * Tellomi（#1108）：联系人页点人打开的会话压在「联系人」自己的栈上，返回回到联系人；从这条会话打开的设置也跟着它。
 * 别的入口（通知等）先切到「聊天」再打开，照旧进聊天的栈。搭法照 [MainNavigationViewModelTest]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiContactsTabNavigationTest {

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val testDispatcher = StandardTestDispatcher()

  private val recipientId = RecipientId.from(1)
  private val conversation = MainDetailRoute.Conversation(conversationArgs(recipientId))
  private val conversationSettings = MainDetailRoute.Chats.ConversationSettings(recipientId)

  private lateinit var viewModel: MainNavigationViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)

    mockkObject(SignalDatabase.Companion)
    every { SignalDatabase.notificationProfiles } returns mockk()

    mockkObject(MainNavigationRepository)
    every { MainNavigationRepository.getNumberOfUnreadMessages() } returns emptyFlow()
    every { MainNavigationRepository.getNumberOfUnseenCalls() } returns emptyFlow()
    every { MainNavigationRepository.getNumberOfUnseenStories() } returns emptyFlow()
    every { MainNavigationRepository.getHasFailedOutgoingStories() } returns emptyFlow()

    viewModel = MainNavigationViewModel(SavedStateHandle())
    testDispatcher.scheduler.advanceUntilIdle()
  }

  @After
  fun tearDown() {
    unmockkObject(MainNavigationRepository)
    unmockkObject(SignalDatabase.Companion)
    Dispatchers.resetMain()
  }

  private fun sendEvent(event: MainNavigationEvents) {
    viewModel.onEvent(event)
    testDispatcher.scheduler.advanceUntilIdle()
  }

  @Test
  fun `given the contacts tab, when opening a conversation, then it stacks on contacts and back returns to contacts`() {
    sendEvent(MainNavigationEvents.GoToTab(MainListRoute.Contacts))

    sendEvent(MainNavigationEvents.GoToDetail(conversation))

    assertEquals(MainListRoute.Contacts, viewModel.currentTab.value)
    assertEquals(listOf(MainListRoute.Contacts, conversation), viewModel.navigator[MainListRoute.Contacts])
    assertEquals(listOf(MainListRoute.Chats), viewModel.navigator[MainListRoute.Chats])

    sendEvent(MainNavigationEvents.ListDetailEvent(ListDetailEvents.Back))

    assertEquals(MainListRoute.Contacts, viewModel.currentTab.value)
    assertEquals(listOf(MainListRoute.Contacts), viewModel.navigator[MainListRoute.Contacts])
  }

  @Test
  fun `given a conversation opened from contacts, when opening its settings, then they stack on contacts too`() {
    sendEvent(MainNavigationEvents.GoToTab(MainListRoute.Contacts))
    sendEvent(MainNavigationEvents.GoToDetail(conversation))

    sendEvent(MainNavigationEvents.GoToDetail(conversationSettings))

    assertEquals(listOf(MainListRoute.Contacts, conversation, conversationSettings), viewModel.navigator[MainListRoute.Contacts])
    assertEquals(listOf(MainListRoute.Chats), viewModel.navigator[MainListRoute.Chats])
  }

  @Test
  fun `given the contacts tab, when an entry switches to chats before opening, then the conversation goes onto chats`() {
    sendEvent(MainNavigationEvents.GoToTab(MainListRoute.Contacts))

    sendEvent(MainNavigationEvents.GoToList(MainListRoute.Chats))
    sendEvent(MainNavigationEvents.GoToDetail(conversation))

    assertEquals(MainListRoute.Chats, viewModel.currentTab.value)
    assertEquals(listOf(MainListRoute.Chats, conversation), viewModel.navigator[MainListRoute.Chats])
    assertEquals(listOf(MainListRoute.Contacts), viewModel.navigator[MainListRoute.Contacts])
  }

  @Test
  fun `in the main window, opening a conversation sends an event instead of an intent`() {
    val host = RecordingHost(ApplicationProvider.getApplicationContext())

    TellomiContactsTab.deliverConversation(host, builderFor(conversation))

    assertEquals(listOf<MainNavigationEvents>(MainNavigationEvents.GoToDetail(conversation)), host.events)
    assertNull(host.startedIntent)
  }

  @Test
  fun `outside the main window, opening a conversation still starts the intent`() {
    val intent = Intent("tellomi.test.OPEN_CONVERSATION")
    val host = PlainHost(ApplicationProvider.getApplicationContext())

    TellomiContactsTab.deliverConversation(host, builderFor(conversation, intent))

    assertEquals(intent, host.startedIntent)
  }

  private fun builderFor(route: MainDetailRoute.Conversation, intent: Intent = Intent()): ConversationIntents.Builder {
    return mockk {
      every { toConversationArgs() } returns route.conversationArgs
      every { build() } returns intent
    }
  }

  private open class PlainHost(base: Context) : ContextWrapper(base) {
    var startedIntent: Intent? = null

    override fun startActivity(intent: Intent) {
      startedIntent = intent
    }
  }

  private class RecordingHost(base: Context) : PlainHost(base), MainNavigationEventSink {
    val events = mutableListOf<MainNavigationEvents>()

    override fun onEvent(event: MainNavigationEvents) {
      events += event
    }
  }

  private fun conversationArgs(recipientId: RecipientId): ConversationArgs {
    return ConversationArgs(
      recipientId = recipientId,
      threadId = -1L,
      draftText = null,
      draftMedia = null,
      draftContentType = null,
      media = null,
      stickerLocator = null,
      isBorderless = false,
      distributionType = ThreadTable.DistributionTypes.DEFAULT,
      startingPosition = -1,
      isFirstTimeInSelfCreatedGroup = false,
      isWithSearchOpen = false,
      giftBadge = null,
      shareDataTimestamp = -1L,
      conversationScreenType = ConversationScreenType.NORMAL
    )
  }
}
