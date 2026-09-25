/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Tellomi（tellomi/tellomi#1259 F-3 / F-4 / F-6 / F-7 / F-9）：转发网格的界面。
 *
 * 头像用 Compose 的检查模式画占位（真头像走 AvatarImageView + Glide，Robolectric 里不画）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class TellomiForwardGridScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val saved = target(1, "Saved Messages", isSavedMessages = true)
  private val people = (2L..9L).map { target(it, "Friend$it") }

  private class Recorder : TellomiForwardGridCallbacks {
    val clicked = mutableListOf<Pair<RecipientId, Boolean>>()
    val queries = mutableListOf<String>()
    var shareClicks = 0

    override fun onTargetClicked(target: TellomiForwardTarget, fromSearch: Boolean) {
      clicked += target.id to fromSearch
    }

    override fun onQueryChanged(query: String) {
      queries += query
    }

    override fun onSearchFocusChanged(focused: Boolean) = Unit

    override fun onShareClicked() {
      shareClicks++
    }
  }

  private fun target(id: Long, name: String, isSavedMessages: Boolean = false): TellomiForwardTarget {
    val recipient = mockk<Recipient>(relaxed = true)
    every { recipient.id } returns RecipientId.from(id)
    every { recipient.getShortDisplayName(any()) } returns name
    every { recipient.getDisplayName(any()) } returns name
    return TellomiForwardTarget(recipient, isSavedMessages)
  }

  private fun show(state: TellomiForwardGridViewModel.State, canShare: Boolean = true, recorder: Recorder = Recorder()): Recorder {
    composeTestRule.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        SignalTheme(isDarkMode = false) {
          TellomiForwardGridContent(state = state, canShare = canShare, callbacks = recorder, bottomContentPadding = 0.dp)
        }
      }
    }
    return recorder
  }

  private fun gridState(selected: List<TellomiForwardTarget> = emptyList()): TellomiForwardGridViewModel.State {
    return TellomiForwardGridViewModel.State(
      isLoading = false,
      targets = listOf(saved) + people,
      selected = selected.map { it.id },
      selectedTargets = selected
    )
  }

  /** F-4：4 列——第 5 格换到第二行；第一格是我的收藏；点一格交给回调。 */
  @Test
  fun `four columns with saved messages first`() {
    val recorder = show(gridState())

    val first = composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(saved)).getUnclippedBoundsInRoot()
    val fourth = composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[2])).getUnclippedBoundsInRoot()
    val fifth = composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[3])).getUnclippedBoundsInRoot()
    assertThat(fourth.top).isEqualTo(first.top)
    assertThat(fifth.top).isGreaterThan(first.top)
    assertThat(fifth.left).isEqualTo(first.left)
    assertThat(TellomiForwardGridMetrics.COLUMNS).isEqualTo(4)

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[0])).performClick()
    assertThat(recorder.clicked).containsExactly(people[0].id to false)
  }

  /** F-3 / F-6：没选时副标题「选择聊天」；选中后副标题是名字（「、」/「, 」连接），选中的格子是选中态、有环有勾。 */
  @Test
  fun `selected cells are marked and the subtitle lists their names`() {
    show(gridState(selected = listOf(people[1], saved)))

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SUBTITLE).assertTextEquals("Friend3, Saved Messages")
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[1])).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true))
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[0])).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, false))
    assertThat(composeTestRule.onAllNodesWithTagCount(TellomiForwardGridTags.CHECK)).isEqualTo(2)
    assertThat(composeTestRule.onAllNodesWithTagCount(TellomiForwardGridTags.RING)).isEqualTo(2)
  }

  @Test
  fun `the subtitle asks to select chats before any selection`() {
    show(gridState())

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SUBTITLE).assertTextEquals("Select chats")
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.TITLE).assertTextEquals("Forward to")
  }

  /** F-9：搜索态空查询是「最近联系人」一排，点一个按「从搜索里选」交给回调。 */
  @Test
  fun `empty search shows a row of recent contacts`() {
    val recorder = show(gridState().copy(isSearchActive = true, recentContacts = people.take(3)))

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.RECENT_ROW).assertIsDisplayed()
    composeTestRule.onNodeWithText("Recent").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[2])).performClick()
    assertThat(recorder.clicked).containsExactly(people[2].id to true)
  }

  /** F-9：有查询按「我的收藏 · 聊天 · 联系人 · 群组」分组；搜不到写「没有找到」。 */
  @Test
  fun `search results are grouped and nothing found says so`() {
    show(
      gridState().copy(
        isSearchActive = true,
        query = "Fri",
        searchResults = TellomiForwardSearchResults(chats = listOf(people[0]), contacts = listOf(people[5]))
      )
    )
    composeTestRule.onNodeWithText("Chats").assertIsDisplayed()
    composeTestRule.onNodeWithText("Contacts").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[5])).assertIsDisplayed()
  }

  @Test
  fun `search with no results says so`() {
    show(gridState().copy(isSearchActive = true, query = "zzzz"))

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.NO_RESULTS).assertTextEquals("No results for “zzzz”")
  }

  /** F-3 / F-10：搜索框右边的「分享到其他 App」只在能分享时出现。 */
  @Test
  fun `share icon only when there is something to share`() {
    val recorder = show(gridState(), canShare = true)
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SHARE).performClick()
    assertThat(recorder.shareClicks).isEqualTo(1)
  }

  @Test
  fun `no share icon when nothing can be shared`() {
    show(gridState(), canShare = false)
    assertThat(composeTestRule.onAllNodesWithTagCount(TellomiForwardGridTags.SHARE)).isEqualTo(0)
  }

  /** F-7：底部「添加消息…」+ 带数量的「发送」。 */
  @Test
  fun `the bottom bar shows the count on send`() {
    var sends = 0
    composeTestRule.setContent {
      SignalTheme(isDarkMode = false) {
        TellomiForwardGridBottomBar(message = "", selectedCount = 3, isSendEnabled = true, onMessageChanged = {}, onSend = { sends++ })
      }
    }

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SEND_COUNT, useUnmergedTree = true).assertTextEquals("3")
    composeTestRule.onNodeWithText("Add a message…").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SEND).performClick()
    assertThat(sends).isEqualTo(1)
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int {
    return onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
  }
}
