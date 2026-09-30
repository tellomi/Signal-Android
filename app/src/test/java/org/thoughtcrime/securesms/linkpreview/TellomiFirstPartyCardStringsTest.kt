/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The resource side of [TellomiFirstPartyCard.Strings] and [TellomiLinkDisplay.Strings] (audit G3, G4, G6, H2): card-visual §3.9's
 * counts and §3.10's second table (the six words on the buttons of a Tellomi object's card) pinned with the real string resources,
 * in the four languages the app ships. The other tests of these cards hand them lambdas; these are the words a person reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiFirstPartyCardStringsTest {

  private val card get() = TellomiFirstPartyCard.Strings.from(ApplicationProvider.getApplicationContext())
  private val display get() = TellomiLinkDisplay.Strings.from(ApplicationProvider.getApplicationContext())

  /** §3.9: members, stickers and tracks, with the thousands separator and no shortening ("12,345", never "1.2万" or "12K"). */
  private fun assertCounts(members: List<String>, stickers: List<String>, tracks: List<String>) {
    assertEquals("members of 1, 12, 12,345", members, listOf(1, 12, 12345).map(card.memberCount))
    assertEquals("stickers of 1, 24", stickers, listOf(1, 24).map(card.stickerCount))
    assertEquals("tracks of 1, 12", tracks, listOf(1, 12).map(display.trackCount))
  }

  /** §3.10, second table, in the order: message, join group, open, join call, add stickers, view stickers. */
  private fun assertButtons(vararg words: String) {
    assertEquals(
      words.toList(),
      listOf(card.actionMessage, card.actionJoinGroup, card.actionOpen, card.actionJoinCall, card.actionAddStickers, card.actionViewStickers)
    )
  }

  @Test
  fun `english`() {
    assertCounts(
      members = listOf("1 member", "12 members", "12,345 members"),
      stickers = listOf("1 sticker", "24 stickers"),
      tracks = listOf("1 track", "12 tracks")
    )
    assertButtons("Message", "Join Group", "Open", "Join Call", "Add", "View")
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `simplified chinese`() {
    assertCounts(
      members = listOf("1 位成员", "12 位成员", "12,345 位成员"),
      stickers = listOf("1 个贴纸", "24 个贴纸"),
      tracks = listOf("1 首", "12 首")
    )
    assertButtons("发消息", "加入群聊", "打开", "加入通话", "添加", "查看")
  }

  @Test
  @Config(qualifiers = "zh-rHK")
  fun `traditional chinese, hong kong`() {
    assertCounts(
      members = listOf("1 個成員", "12 個成員", "12,345 個成員"),
      stickers = listOf("1 個貼圖", "24 個貼圖"),
      tracks = listOf("1 首", "12 首")
    )
    assertButtons("傳送訊息", "加入群組", "開啟", "加入通話", "新增", "查看")
  }

  @Test
  @Config(qualifiers = "zh-rTW")
  fun `traditional chinese, taiwan`() {
    assertCounts(
      members = listOf("1 個成員", "12 個成員", "12,345 個成員"),
      stickers = listOf("1 個貼圖", "24 個貼圖"),
      tracks = listOf("1 首", "12 首")
    )
    assertButtons("傳送訊息", "加入群組", "開啟", "加入通話", "新增", "查看")
  }
}
