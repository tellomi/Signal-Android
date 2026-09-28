/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import java.util.Locale

/**
 * What a message bubble shows for a link preview, by the level rust/links decided (ADR-0063 §5.1
 * ladder, card-visual §3–§5). Visual details (layout, tint, action buttons) come with the finalized
 * card spec; this only keeps each level's text honest:
 * - generic, group / call / sticker: Signal's snapshot, unchanged (no override);
 * - brand: the platform name, no sender-written text and no image;
 * - structured: the validated title and one line of attrs in place of the description;
 * - user and official cards: text computed from the URL, never the sender's (§4.8, §6.1).
 *
 * Whether the image shows is [TellomiLinkCard.showImage], decided by the caller.
 */
data class TellomiLinkDisplay(
  val title: String?,
  val description: String?,
  val domain: String?,
  val officialBadge: Boolean
) {

  data class Strings(val officialTitle: String, val tellomiUser: String)

  companion object {
    // Shown as plain text, in this order (kinds.toml). Counts, dates and coordinates wait for the
    // finalized card spec (they need localized formatting).
    private val TEXT_ATTRS = listOf("author", "artist", "album", "developer", "owner", "name", "address", "platform")

    /** Null: no override, show the preview the way Signal does. */
    @JvmStatic
    fun of(linkPreview: LinkPreview, card: TellomiLinkCard?, locale: Locale, strings: Strings): TellomiLinkDisplay? {
      if (card == null) {
        return null
      }
      return when (card.level) {
        TellomiLinkCard.Level.PLAIN_LINK, TellomiLinkCard.Level.GENERIC -> null
        TellomiLinkCard.Level.BRAND -> TellomiLinkDisplay(
          title = card.providerName?.forLocale(locale),
          description = null,
          domain = card.domain,
          officialBadge = false
        )
        TellomiLinkCard.Level.STRUCTURED -> TellomiLinkDisplay(
          title = card.title ?: linkPreview.title,
          description = formatAttrs(card),
          domain = card.domain,
          officialBadge = false
        )
        TellomiLinkCard.Level.FIRST_PARTY -> {
          val firstParty = card.firstParty
          when (firstParty?.type) {
            "official" -> TellomiLinkDisplay(
              title = strings.officialTitle,
              description = firstParty.path,
              domain = card.domain,
              officialBadge = card.officialBadge
            )
            "user" -> TellomiLinkDisplay(
              title = firstParty.display ?: strings.tellomiUser,
              description = if (firstParty.display != null) strings.tellomiUser else null,
              domain = card.domain,
              officialBadge = false
            )
            else -> null
          }
        }
      }
    }

    @JvmStatic
    fun formatAttrs(card: TellomiLinkCard): String? {
      val byKey = card.attrs.associate { it.key to it.value }
      val parts = TEXT_ATTRS.mapNotNull { key -> byKey[key]?.takeIf { it.isNotEmpty() } }.toMutableList()
      byKey["duration_ms"]?.let { formatDuration(it) }?.let { parts += it }
      return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun formatDuration(value: String): String? {
      val ms = value.toLongOrNull()?.takeIf { it > 0 } ?: return null
      val totalSeconds = (ms + 500) / 1000
      val hours = totalSeconds / 3600
      val minutes = (totalSeconds % 3600) / 60
      val seconds = totalSeconds % 60
      return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
      } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
      }
    }
  }
}
