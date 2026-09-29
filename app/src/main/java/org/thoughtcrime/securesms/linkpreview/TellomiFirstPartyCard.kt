/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.content.Context
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stickers.StickerUrl
import java.text.NumberFormat

/**
 * card-visual §5.2 (tellomi/tellomi#1422): the card of a Tellomi object: an avatar or cover, a title, a subtitle and one action
 * button at the bottom, the way Telegram shows its own objects. The words come from the URL and from what this device already
 * has; of what the sender wrote, only a group's or a pack's name, which the preview carries (ADR-0063 §4.8). Nothing is fetched
 * to draw it (§5.3): a group that no longer exists says so once it is opened. Same rules as Desktop (tellomi/Signal-Desktop#28).
 */
object TellomiFirstPartyCard {

  enum class Type { USER, GROUP, CALL, STICKER, OFFICIAL }

  /** What this device already has for the object, read locally (see [TellomiFirstPartyLocalLookup]). */
  data class Local(
    /** tellomi.user: the name this device shows for that username, once the reader accepted them. */
    val knownUserName: String? = null,
    val knownUserId: RecipientId? = null,
    /** tellomi.group: this account is a member of the group. */
    val isGroupMember: Boolean = false,
    val groupRecipientId: RecipientId? = null,
    /** tellomi.sticker: the pack is installed. */
    val isStickerPackInstalled: Boolean = false
  )

  data class Display(
    val type: Type,
    val title: String,
    val subtitle: String?,
    /** The label of the button at the bottom (card-visual §3.10). */
    val action: String,
    val officialBadge: Boolean
  )

  /** Everything that needs resources, so [display] stays a pure function. */
  class Strings(
    val officialTitle: String,
    val tellomiUser: String,
    val callTitle: String,
    val actionMessage: String,
    val actionJoinGroup: String,
    val actionOpen: String,
    val actionJoinCall: String,
    val actionAddStickers: String,
    val actionViewStickers: String,
    val groupJoined: String,
    val stickersAdded: String,
    val memberCount: (Int) -> String,
    val stickerCount: (Int) -> String
  ) {
    companion object {
      @JvmStatic
      fun from(context: Context): Strings {
        val numbers = NumberFormat.getInstance()
        return Strings(
          officialTitle = context.getString(R.string.TellomiLinkCard__official_title),
          tellomiUser = context.getString(R.string.TellomiLinkCard__tellomi_user),
          callTitle = context.getString(R.string.TellomiLinkCard__call_title),
          actionMessage = context.getString(R.string.TellomiLinkCard__action_message),
          actionJoinGroup = context.getString(R.string.TellomiLinkCard__action_join_group),
          actionOpen = context.getString(R.string.TellomiLinkCard__action_open),
          actionJoinCall = context.getString(R.string.TellomiLinkCard__action_join_call),
          actionAddStickers = context.getString(R.string.TellomiLinkCard__action_add_stickers),
          actionViewStickers = context.getString(R.string.TellomiLinkCard__action_view_stickers),
          groupJoined = context.getString(R.string.TellomiLinkCard__group_joined),
          stickersAdded = context.getString(R.string.TellomiLinkCard__stickers_added),
          memberCount = { count -> context.resources.getQuantityString(R.plurals.TellomiLinkCard__member_count, count, numbers.format(count)) },
          stickerCount = { count -> context.resources.getQuantityString(R.plurals.TellomiLinkCard__sticker_count, count, numbers.format(count)) }
        )
      }
    }
  }

  /** Null when this is not a first-party card, or of a type this build does not know (Signal's display stays). */
  @JvmStatic
  fun display(card: TellomiLinkCard, local: Local?, strings: Strings): Display? {
    val firstParty = card.firstParty
    if (card.level != TellomiLinkCard.Level.FIRST_PARTY || firstParty == null) {
      return null
    }

    return when (firstParty.type) {
      "user" -> {
        val name = local?.knownUserName ?: firstParty.display
        Display(Type.USER, name ?: strings.tellomiUser, if (name != null) strings.tellomiUser else null, strings.actionMessage, false)
      }
      "group" -> {
        val subtitle = if (local?.isGroupMember == true) {
          strings.groupJoined
        } else {
          count(firstParty.memberCount)?.let(strings.memberCount)
        }
        Display(Type.GROUP, firstParty.title.orEmpty(), subtitle, if (local?.isGroupMember == true) strings.actionOpen else strings.actionJoinGroup, false)
      }
      "call" -> Display(Type.CALL, firstParty.title ?: strings.callTitle, null, strings.actionJoinCall, false)
      "sticker" -> {
        val subtitle = if (local?.isStickerPackInstalled == true) {
          strings.stickersAdded
        } else {
          count(firstParty.stickerCount)?.let(strings.stickerCount)
        }
        Display(Type.STICKER, firstParty.title.orEmpty(), subtitle, if (local?.isStickerPackInstalled == true) strings.actionViewStickers else strings.actionAddStickers, false)
      }
      "official" -> Display(Type.OFFICIAL, strings.officialTitle, firstParty.path, strings.actionOpen, card.officialBadge)
      else -> null
    }
  }

  /** A sticker pack id is 16 bytes written as hex; the upstream link parser also lets other strings through. */
  private val PACK_ID = Regex("^[0-9a-fA-F]{32}$")

  /** A count worth showing: a whole number above zero (card-visual §3.9). */
  private fun count(value: Long?): Int? = value?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()

  /**
   * What to look the object up by, from the URL itself (ADR-0063 §4.8: identity comes from the URL, not from the sender).
   * Usernames are compared lower case.
   */
  data class LookupKeys(
    val username: String? = null,
    val groupInviteUrl: String? = null,
    val stickerPackId: String? = null
  )

  @JvmStatic
  fun lookupKeys(url: String, card: TellomiLinkCard): LookupKeys {
    val firstParty = card.firstParty
    if (card.level != TellomiLinkCard.Level.FIRST_PARTY || firstParty == null) {
      return LookupKeys()
    }

    return when (firstParty.type) {
      "user" -> LookupKeys(username = firstParty.username?.lowercase())
      "group" -> LookupKeys(groupInviteUrl = url)
      "sticker" -> LookupKeys(stickerPackId = StickerUrl.parseShareLink(url).map { it.first }.orElse(null)?.takeIf { PACK_ID.matches(it) })
      else -> LookupKeys()
    }
  }
}
