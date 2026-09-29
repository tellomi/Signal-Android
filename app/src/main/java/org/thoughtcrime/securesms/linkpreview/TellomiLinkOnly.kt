/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.signal.core.util.Linkifier
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import java.util.Optional

/**
 * card-visual §3.5 (2026-09-29, tellomi/tellomi#1422): a message that is nothing but one link shows the card alone,
 * with no link text under it; the URL stays in the long-press menu ("Copy"). With no preview at all (previews off,
 * or fetching failed), or when rust/links decided "plain link", the receiver draws a no-image card from the URL alone:
 * the registrable domain and a link icon (§3.7, first row). A message with anything else keeps Signal's layout.
 * Same rules as Desktop (tellomi/Signal-Desktop#26).
 */
object TellomiLinkOnly {

  private val LINK_ONLY = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

  /**
   * What the bubble shows for the message's link: [card] for the first preview (null: Signal's display);
   * [localPreview], the preview drawn from the URL alone when the message is just a link sent without one;
   * [cardOnly], the card stands alone, without the link text under it.
   */
  data class Decision(val card: TellomiLinkCard?, val localPreview: LinkPreview?, val cardOnly: Boolean) {
    companion object {
      @JvmField
      val NONE = Decision(null, null, false)
    }
  }

  /** Decided in the data layer, off the main thread (ADR-0063 §5.1 rule 4). */
  @JvmStatic
  @WorkerThread
  fun decide(record: MessageRecord, hasMentions: Boolean): Decision {
    return decide(record, hasMentions, TellomiLinkRegistry::classify) { url -> TellomiLinkOpener.plan(url)?.lookalike }
  }

  /**
   * [classify]: rust/links' decision for one preview; [lookalike]: the well-known domain a URL imitates (the same check
   * that warns before opening it, so a red domain and that warning always come together).
   */
  @VisibleForTesting
  @JvmStatic
  fun decide(
    record: MessageRecord,
    hasMentions: Boolean,
    classify: (LinkPreview, String, Boolean, List<String>) -> TellomiLinkCard?,
    lookalike: (String) -> String?
  ): Decision {
    val mms = record as? MmsMessageRecord ?: return Decision.NONE
    val body = mms.body
    val isStory = mms.storyType.isStory
    val attachmentContentTypes = mms.slideDeck.slides.map { it.contentType }
    val linkOnlyUrl = linkOnlyUrl(body, hasOtherContent(mms, hasMentions))
    val previews = mms.linkPreviews

    if (previews.isEmpty()) {
      // Just a link, sent without a preview (previews off, or fetching failed): it still gets a card. rust/links
      // computes its domain as for any preview.
      if (linkOnlyUrl == null) {
        return Decision.NONE
      }
      val local = LinkPreview(linkOnlyUrl, "", "", 0, Optional.empty())
      val card = classify(local, body, isStory, attachmentContentTypes)
      return if (card?.domain != null) Decision(toPlainLinkCard(card, lookalike(linkOnlyUrl)), local, true) else Decision.NONE
    }

    val card = classify(previews[0], body, isStory, attachmentContentTypes)
    val cardOnly = isLinkCardOnly(linkOnlyUrl, previews.map { it.url }, card)
    if (card?.level == TellomiLinkCard.Level.PLAIN_LINK) {
      // Shown only when the message is just this link, as the no-image card; otherwise not at all.
      return if (cardOnly && linkOnlyUrl != null && card.domain != null) {
        Decision(toPlainLinkCard(card, lookalike(linkOnlyUrl)), null, true)
      } else {
        Decision(card, null, false)
      }
    }
    return Decision(card, null, cardOnly)
  }

  /**
   * Anything that makes the message more than plain text: mentions, formatting, attachments (a long text too), a
   * sticker, a contact, a payment, a gift, a poll, view-once, a story, a deleted message. Same list as Desktop.
   */
  @JvmStatic
  fun hasOtherContent(record: MmsMessageRecord, hasMentions: Boolean): Boolean {
    return hasMentions ||
      record.isUpdate ||
      record.isMmsNotification ||
      record.storyType.isStory ||
      MessageTypes.isStoryReaction(record.type) ||
      record.slideDeck.slides.isNotEmpty() ||
      record.messageRanges?.ranges?.isNotEmpty() == true ||
      record.sharedContacts.isNotEmpty() ||
      record.payment != null ||
      record.isPaymentNotification ||
      record.isPaymentTombstone ||
      record.giftBadge != null ||
      record.poll != null ||
      record.isViewOnce ||
      record.isRemoteDelete ||
      record.deletedBy != null
  }

  /**
   * The link the whole body consists of, or null. [hasOtherContent]: anything that makes the message more than plain
   * text (formatting, mentions, attachments, a sticker, a contact, a payment…).
   */
  @JvmStatic
  fun linkOnlyUrl(body: String?, hasOtherContent: Boolean): String? {
    if (hasOtherContent || body.isNullOrEmpty()) {
      return null
    }
    val trimmed = body.trim()
    // Most messages are not a link: rule them out without parsing.
    if (!LINK_ONLY.matches(trimmed) || trimmed.toHttpUrlOrNull() == null) {
      return null
    }
    // The body must show it as one link, all of it (nothing the linkifier leaves out, like trailing punctuation).
    val links = Linkifier.findLinks(trimmed)
    val only = links.singleOrNull() ?: return null
    return if (only.start == 0 && only.end == trimmed.length) trimmed else null
  }

  /**
   * Whether the bubble is the card alone: the body is exactly the link of its only preview, and rust/links decided
   * what that card is (without a decision, Signal's layout stays).
   */
  @JvmStatic
  fun isLinkCardOnly(linkOnlyUrl: String?, previewUrls: List<String>, card: TellomiLinkCard?): Boolean {
    return linkOnlyUrl != null && card != null && previewUrls.singleOrNull() == linkOnlyUrl
  }

  /**
   * The no-image card drawn from the URL alone: the domain rust/links computed and whether it imitates a well-known
   * one (§6.1); nothing the sender wrote, nothing a first-party card adds.
   */
  @JvmStatic
  fun toPlainLinkCard(card: TellomiLinkCard, lookalike: String?): TellomiLinkCard {
    return TellomiLinkCard(
      level = TellomiLinkCard.Level.PLAIN_LINK,
      domain = card.domain,
      lookalike = card.lookalike ?: lookalike,
      showImage = false,
      tintable = false,
      payment = card.payment,
      reason = card.reason
    )
  }
}
