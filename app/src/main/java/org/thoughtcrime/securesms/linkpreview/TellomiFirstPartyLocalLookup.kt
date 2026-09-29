/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.v2.GroupInviteLinkUrl
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * card-visual §5.2: what this device already has for the object a Tellomi card is about (a known user, a group it is in, an
 * installed sticker pack), read from the local database only, never from the network. Decided in the data layer, off the main
 * thread. Same lookups as Desktop's `firstPartyLocal.node.ts` (tellomi/Signal-Desktop#28).
 */
object TellomiFirstPartyLocalLookup {

  private val TAG = Log.tag(TellomiFirstPartyLocalLookup::class.java)

  /** The local data for the first link of a message, or null when its card is not a first-party card. */
  @JvmStatic
  @WorkerThread
  fun forMessage(record: MessageRecord, decision: TellomiLinkOnly.Decision): TellomiFirstPartyCard.Local? {
    val card = decision.card ?: return null
    val url = (record as? MmsMessageRecord)?.linkPreviews?.firstOrNull()?.url ?: return null
    return lookup(url, card)
  }

  @JvmStatic
  @WorkerThread
  fun lookup(url: String, card: TellomiLinkCard?): TellomiFirstPartyCard.Local? {
    if (card == null || card.level != TellomiLinkCard.Level.FIRST_PARTY) {
      return null
    }

    val keys = TellomiFirstPartyCard.lookupKeys(url, card)
    return try {
      when {
        keys.username != null -> lookupUser(keys.username)
        keys.groupInviteUrl != null -> lookupGroup(keys.groupInviteUrl)
        keys.stickerPackId != null -> TellomiFirstPartyCard.Local(isStickerPackInstalled = SignalDatabase.stickers.isPackInstalled(keys.stickerPackId))
        else -> null
      }
    } catch (e: Exception) {
      // Only the kind of failure, never the URL (ADR-0063 §6.5).
      Log.w(TAG, "Local lookup failed: ${e.javaClass.simpleName}")
      null
    }
  }

  /** A user counts as known only once the reader has accepted them (or it is themselves), as Desktop does. */
  private fun lookupUser(username: String): TellomiFirstPartyCard.Local? {
    val id = SignalDatabase.recipients.getByUsername(username).orElse(null) ?: return null
    val recipient = Recipient.resolved(id)
    if (!recipient.isSelf && !recipient.isProfileSharing) {
      return null
    }
    return TellomiFirstPartyCard.Local(
      knownUserName = recipient.getDisplayName(AppDependencies.application),
      knownUserId = recipient.id
    )
  }

  private fun lookupGroup(inviteUrl: String): TellomiFirstPartyCard.Local? {
    val link = GroupInviteLinkUrl.fromUri(inviteUrl) ?: return null
    val record = SignalDatabase.groups.getGroup(GroupId.v2(link.groupMasterKey)).orElse(null) ?: return null
    return TellomiFirstPartyCard.Local(isGroupMember = record.isActive, groupRecipientId = record.recipientId)
  }
}
