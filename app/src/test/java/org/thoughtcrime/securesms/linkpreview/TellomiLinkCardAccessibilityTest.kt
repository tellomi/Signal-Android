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
 * card-visual §3.6 (audit F1, F2): the sentence a screen reader says for a whole link card, in the four languages the app ships,
 * with the real string resources. The words are the same on every client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkCardAccessibilityTest {

  private val strings get() = TellomiLinkCardAccessibility.Strings.from(ApplicationProvider.getApplicationContext())

  private fun display(type: TellomiFirstPartyCard.Type, title: String, subtitle: String?, action: String) = TellomiFirstPartyCard.Display(type, title, subtitle, action, officialBadge = false)

  // ---- the words ----

  @Test
  fun `the words, english`() {
    assertEquals(", ", strings.separator)
    assertEquals("Link", strings.link)
    assertEquals("button: Join Group", strings.buttonWithAction("Join Group"))
    assertEquals("Tellomi group", strings.kindGroup)
    assertEquals("Tellomi sticker pack", strings.kindStickerPack)
    assertEquals("Tellomi user", strings.kindUser)
    assertEquals("Tellomi call", strings.kindCall)
    assertEquals("Tellomi website", strings.kindOfficial)
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `the words, simplified chinese`() {
    assertEquals("，", strings.separator)
    assertEquals("链接", strings.link)
    assertEquals("按钮：加入群聊", strings.buttonWithAction("加入群聊"))
    assertEquals("Tellomi 群组", strings.kindGroup)
    assertEquals("Tellomi 贴纸包", strings.kindStickerPack)
    assertEquals("Tellomi 用户", strings.kindUser)
    assertEquals("Tellomi 通话", strings.kindCall)
    assertEquals("Tellomi 官网", strings.kindOfficial)
  }

  @Test
  @Config(qualifiers = "zh-rHK")
  fun `the words, traditional chinese, hong kong`() {
    assertTraditionalChineseWords()
  }

  @Test
  @Config(qualifiers = "zh-rTW")
  fun `the words, traditional chinese, taiwan`() {
    assertTraditionalChineseWords()
  }

  private fun assertTraditionalChineseWords() {
    assertEquals("，", strings.separator)
    assertEquals("連結", strings.link)
    assertEquals("按鈕：加入群組", strings.buttonWithAction("加入群組"))
    assertEquals("Tellomi 群組", strings.kindGroup)
    assertEquals("Tellomi 貼圖包", strings.kindStickerPack)
    assertEquals("Tellomi 用戶", strings.kindUser)
    assertEquals("Tellomi 通話", strings.kindCall)
    assertEquals("Tellomi 官網", strings.kindOfficial)
  }

  // ---- a link to a website: Link, <title>, <domain> ----

  @Test
  fun `a link to a website is read as link, title, domain`() {
    assertEquals("Link, Ke Jie Go Course, bilibili.com", TellomiLinkCardAccessibility.link("Ke Jie Go Course", "bilibili.com", strings))
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `a link to a website, simplified chinese`() {
    assertEquals("链接，柯洁围棋入门课，bilibili.com", TellomiLinkCardAccessibility.link("柯洁围棋入门课", "bilibili.com", strings))
  }

  @Test
  @Config(qualifiers = "zh-rHK")
  fun `a link to a website, traditional chinese`() {
    assertEquals("連結，柯潔圍棋入門課，bilibili.com", TellomiLinkCardAccessibility.link("柯潔圍棋入門課", "bilibili.com", strings))
  }

  @Test
  fun `a part that is not there is left out, and the domain is not said twice`() {
    assertEquals("Link, bilibili.com", TellomiLinkCardAccessibility.link(null, "bilibili.com", strings))
    assertEquals("Link, bilibili.com", TellomiLinkCardAccessibility.link("", "bilibili.com", strings))
    assertEquals("Link, bilibili.com", TellomiLinkCardAccessibility.link("  ", "bilibili.com", strings))
    assertEquals("a plain-link card has the domain as its title", "Link, bilibili.com", TellomiLinkCardAccessibility.link("bilibili.com", null, strings))
    assertEquals("Link, bilibili.com", TellomiLinkCardAccessibility.link("bilibili.com", "bilibili.com", strings))
    assertEquals("Link, Ke Jie Go Course", TellomiLinkCardAccessibility.link("Ke Jie Go Course", null, strings))
    assertEquals("Link", TellomiLinkCardAccessibility.link(null, null, strings))
  }

  @Test
  fun `a title that happens to be the word link is still said`() {
    assertEquals("Link, Link, example.com", TellomiLinkCardAccessibility.link("Link", "example.com", strings))
  }

  // ---- a Tellomi object: <kind>, <title>, <subtitle>, button: <action> ----

  @Test
  fun `a group is read as kind, name, members, button`() {
    assertEquals(
      "Tellomi group, Book Club, 12 members, button: Join Group",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.GROUP, "Book Club", "12 members", "Join Group"), strings)
    )
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `a group, simplified chinese`() {
    assertEquals(
      "Tellomi 群组，读书会，12 位成员，按钮：加入群聊",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.GROUP, "读书会", "12 位成员", "加入群聊"), strings)
    )
  }

  @Test
  @Config(qualifiers = "zh-rTW")
  fun `a group, traditional chinese`() {
    assertEquals(
      "Tellomi 群組，讀書會，12 個成員，按鈕：加入群組",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.GROUP, "讀書會", "12 個成員", "加入群組"), strings)
    )
  }

  @Test
  fun `a user is read as kind, name, button, and the subtitle that repeats the kind is not said`() {
    assertEquals(
      "Tellomi user, @kaixin, button: Message",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.USER, "@kaixin", "Tellomi user", "Message"), strings)
    )
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `a user, simplified chinese`() {
    assertEquals(
      "Tellomi 用户，@kaixin，按钮：发消息",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.USER, "@kaixin", "Tellomi 用户", "发消息"), strings)
    )
  }

  @Test
  fun `a user with no name is a Tellomi user, once`() {
    assertEquals(
      "Tellomi user, button: Message",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.USER, "Tellomi user", null, "Message"), strings)
    )
  }

  @Test
  fun `the official website is read as kind, path, button`() {
    assertEquals(
      "Tellomi website, /download, button: Open",
      TellomiLinkCardAccessibility.firstParty(TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.OFFICIAL, "Tellomi website", "/download", "Open", true), strings)
    )
  }

  @Test
  @Config(qualifiers = "zh-rHK")
  fun `the official website, traditional chinese`() {
    assertEquals(
      "Tellomi 官網，/download，按鈕：開啟",
      TellomiLinkCardAccessibility.firstParty(TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.OFFICIAL, "Tellomi 官網", "/download", "開啟", true), strings)
    )
  }

  @Test
  fun `a call is read as kind, room, button, and a call with no room name is a Tellomi call, once`() {
    assertEquals(
      "Tellomi call, Camping Prep, button: Join Call",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.CALL, "Camping Prep", null, "Join Call"), strings)
    )
    assertEquals(
      "Tellomi call, button: Join Call",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.CALL, "Tellomi call", null, "Join Call"), strings)
    )
  }

  @Test
  fun `a sticker pack is read as kind, name, count or added, button`() {
    assertEquals(
      "Tellomi sticker pack, Bandit, 24 stickers, button: Add",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.STICKER, "Bandit", "24 stickers", "Add"), strings)
    )
    assertEquals(
      "Tellomi sticker pack, Bandit, Added, button: View",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.STICKER, "Bandit", "Added", "View"), strings)
    )
  }

  @Test
  @Config(qualifiers = "zh-rTW")
  fun `a sticker pack, traditional chinese`() {
    assertEquals(
      "Tellomi 貼圖包，Bandit，24 個貼圖，按鈕：新增",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.STICKER, "Bandit", "24 個貼圖", "新增"), strings)
    )
  }

  @Test
  fun `a part that is not there is left out of a Tellomi object too`() {
    assertEquals(
      "a group with no name, from a preview that had none",
      "Tellomi group, 12 members, button: Join Group",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.GROUP, "", "12 members", "Join Group"), strings)
    )
    assertEquals(
      "Tellomi group, Book Club, button: Join Group",
      TellomiLinkCardAccessibility.firstParty(display(TellomiFirstPartyCard.Type.GROUP, "Book Club", null, "Join Group"), strings)
    )
  }

  // ---- the domain a screen reader gets ----

  @Test
  fun `the domain for a screen reader is the registrable domain alone, without the publish date after it`() {
    assertEquals("bilibili.com", TellomiLinkDisplay("Title", null, "bilibili.com ⋅ Sep 3", false).accessibilityDomain)
    assertEquals("bilibili.com", TellomiLinkDisplay("Title", null, "bilibili.com", false).accessibilityDomain)
    assertEquals(null, TellomiLinkDisplay("bilibili.com", null, null, false, plainLink = true).accessibilityDomain)
  }
}
