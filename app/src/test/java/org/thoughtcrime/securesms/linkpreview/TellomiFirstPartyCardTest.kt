/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * card-visual §5.2 / §3.9 / §3.10 (tellomi/tellomi#1422): the card of a Tellomi object has an avatar or cover, a title, a
 * subtitle and one action button; what this device already knows changes the subtitle and the button, never the sender.
 * Same cases as Desktop (`firstPartyCard_test.node.ts`, tellomi/Signal-Desktop#28).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiFirstPartyCardTest {

  private val strings = TellomiFirstPartyCard.Strings(
    officialTitle = "Tellomi website",
    tellomiUser = "Tellomi user",
    callTitle = "Tellomi call",
    actionMessage = "Message",
    actionJoinGroup = "Join Group",
    actionOpen = "Open",
    actionJoinCall = "Join Call",
    actionAddStickers = "Add",
    actionViewStickers = "View",
    groupJoined = "You’re a member",
    stickersAdded = "Added",
    memberCount = { if (it == 1) "1 member" else "$it members" },
    stickerCount = { if (it == 1) "1 sticker" else "$it stickers" }
  )

  private val base = TellomiLinkCard(level = TellomiLinkCard.Level.FIRST_PARTY, provider = "tellomi", domain = "tell.cc", showImage = false)

  private fun card(firstParty: TellomiLinkCard.FirstParty?) = base.copy(firstParty = firstParty)

  private val user = card(TellomiLinkCard.FirstParty(type = "user", display = "@kefu.57", username = "kefu.57"))
  private val group = card(TellomiLinkCard.FirstParty(type = "group", title = "周末爬山群", memberCount = 12))
  private val sticker = card(TellomiLinkCard.FirstParty(type = "sticker", title = "Bandit", stickerCount = 24))

  private fun display(card: TellomiLinkCard, local: TellomiFirstPartyCard.Local? = null) = TellomiFirstPartyCard.display(card, local, strings)

  @Test
  fun `a user is named from the URL, and the button says what it does`() {
    assertEquals(
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.USER, "@kefu.57", "Tellomi user", "Message", false),
      display(user)
    )
  }

  @Test
  fun `a user this device already knows keeps the name it has`() {
    val shown = display(user, TellomiFirstPartyCard.Local(knownUserName = "Kai Xin"))
    assertEquals("Kai Xin", shown?.title)
    assertEquals("Tellomi user", shown?.subtitle)
  }

  @Test
  fun `a user with no name in the URL is a Tellomi user, once`() {
    val shown = display(card(TellomiLinkCard.FirstParty(type = "user")))
    assertEquals("Tellomi user", shown?.title)
    assertNull(shown?.subtitle)
  }

  @Test
  fun `a group to join counts its members`() {
    assertEquals(
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.GROUP, "周末爬山群", "12 members", "Join Group", false),
      display(group)
    )
    assertEquals("1 member", display(card(TellomiLinkCard.FirstParty(type = "group", title = "Two", memberCount = 1)))?.subtitle)
    for (count in listOf(null, 0L, -3L)) {
      assertNull(count.toString(), display(card(TellomiLinkCard.FirstParty(type = "group", title = "G", memberCount = count)))?.subtitle)
    }
  }

  @Test
  fun `a group this account is already in opens`() {
    val shown = display(group, TellomiFirstPartyCard.Local(isGroupMember = true))
    assertEquals("You’re a member", shown?.subtitle)
    assertEquals("Open", shown?.action)
  }

  @Test
  fun `a call is named by its room, or is a Tellomi call`() {
    assertEquals(
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.CALL, "Camping Prep", null, "Join Call", false),
      display(card(TellomiLinkCard.FirstParty(type = "call", title = "Camping Prep")))
    )
    assertEquals("Tellomi call", display(card(TellomiLinkCard.FirstParty(type = "call")))?.title)
  }

  @Test
  fun `a sticker pack to add counts its stickers, and one already added is viewed`() {
    assertEquals(
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.STICKER, "Bandit", "24 stickers", "Add", false),
      display(sticker)
    )
    val installed = display(sticker, TellomiFirstPartyCard.Local(isStickerPackInstalled = true))
    assertEquals("Added", installed?.subtitle)
    assertEquals("View", installed?.action)
    assertEquals("1 sticker", display(card(TellomiLinkCard.FirstParty(type = "sticker", title = "One", stickerCount = 1)))?.subtitle)
  }

  @Test
  fun `the official site card shows fixed text, the path and the badge`() {
    val official = card(TellomiLinkCard.FirstParty(type = "official", path = "/download")).copy(domain = "tellomi.app", officialBadge = true)
    assertEquals(
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.OFFICIAL, "Tellomi website", "/download", "Open", true),
      display(official)
    )
  }

  @Test
  fun `it is not a first-party card at any other level, or with a type this build does not know`() {
    assertNull(display(group.copy(level = TellomiLinkCard.Level.GENERIC)))
    assertNull(display(base))
    assertNull(display(card(TellomiLinkCard.FirstParty(type = "hologram", title = "x"))))
  }

  @Test
  fun `a user is looked up by the username the URL names, lower case`() {
    assertEquals(
      TellomiFirstPartyCard.LookupKeys(username = "kefu.57"),
      TellomiFirstPartyCard.lookupKeys("https://tell.cc/Kefu.57", card(TellomiLinkCard.FirstParty(type = "user", display = "@Kefu.57", username = "Kefu.57")))
    )
    assertEquals(TellomiFirstPartyCard.LookupKeys(), TellomiFirstPartyCard.lookupKeys("https://tell.cc/u#eu/abc", card(TellomiLinkCard.FirstParty(type = "user"))))
  }

  @Test
  fun `a group is looked up by its invite link, a pack by the id in its link`() {
    assertEquals(TellomiFirstPartyCard.LookupKeys(groupInviteUrl = "https://tell.cc/g#CjQKIGV4"), TellomiFirstPartyCard.lookupKeys("https://tell.cc/g#CjQKIGV4", group))
    assertEquals(
      TellomiFirstPartyCard.LookupKeys(stickerPackId = "00112233445566778899aabbccddeeff"),
      TellomiFirstPartyCard.lookupKeys("https://tell.cc/s#pack_id=00112233445566778899aabbccddeeff&pack_key=${"11".repeat(32)}", sticker)
    )
    assertEquals(TellomiFirstPartyCard.LookupKeys(), TellomiFirstPartyCard.lookupKeys("https://tell.cc/s#pack_id=zz&pack_key=yy", sticker))
  }

  @Test
  fun `a call, the official site, and anything that is not first party look nothing up`() {
    assertEquals(TellomiFirstPartyCard.LookupKeys(), TellomiFirstPartyCard.lookupKeys("https://tell.cc/call#key=bcdf-ghkm", card(TellomiLinkCard.FirstParty(type = "call"))))
    assertEquals(TellomiFirstPartyCard.LookupKeys(), TellomiFirstPartyCard.lookupKeys("https://tellomi.app/download", card(TellomiLinkCard.FirstParty(type = "official", path = "/download"))))
    assertEquals(TellomiFirstPartyCard.LookupKeys(), TellomiFirstPartyCard.lookupKeys("https://tell.cc/g#x", group.copy(level = TellomiLinkCard.Level.GENERIC)))
  }
}
