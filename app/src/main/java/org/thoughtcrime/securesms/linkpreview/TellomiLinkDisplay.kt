/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.content.Context
import android.text.format.DateFormat
import org.thoughtcrime.securesms.R
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What a message bubble shows for a link preview, by the level rust/links decided (ADR-0063 §5.1
 * ladder), per the finalized card spec (card-visual §3.7 / §3.9 / §3.10, 2026-09-29): a title, one
 * sub line and the domain line. The sender's description never shows.
 * - generic: the snapshot title and the registrable domain;
 * - brand: the platform name and what the link is (the kind's name), no sender-written text and no image;
 * - structured: the validated title and the kind's sub line (attrs, counts, durations); a video's
 *   publish date follows the domain;
 * - user and official cards: text computed from the URL, never the sender's (§4.8, §6.1);
 * - group, call and sticker cards: Signal's display until the first-party card layout lands (§5.2).
 *
 * Layout, tint and action buttons are not decided here. Whether the image shows is
 * [TellomiLinkCard.showImage], decided by the caller.
 */
data class TellomiLinkDisplay(
  val title: String?,
  val description: String?,
  val domain: String?,
  val officialBadge: Boolean
) {

  /** Everything that needs resources or the clock, so [of] stays a pure function. */
  data class Strings(
    val officialTitle: String,
    val tellomiUser: String,
    /** Title of a place card that has no name (card-visual §3.7). */
    val place: String,
    /** A kind's name for the brand shell's sub line (§3.10); null for kinds the table does not name. */
    val kindName: (String) -> String?,
    /** "12 tracks" (§3.9). */
    val trackCount: (Int) -> String,
    /** A publish date: medium length, no time, no year when it is this year (§3.9). */
    val date: (Long) -> String
  ) {
    companion object {
      @JvmStatic
      fun from(context: Context): Strings {
        return Strings(
          officialTitle = context.getString(R.string.TellomiLinkCard__official_title),
          tellomiUser = context.getString(R.string.TellomiLinkCard__tellomi_user),
          place = context.getString(R.string.TellomiLinkCard__place),
          kindName = { kind -> KIND_NAMES[kind]?.let { context.getString(it) } },
          trackCount = { count -> context.resources.getQuantityString(R.plurals.TellomiLinkCard__track_count, count, NumberFormat.getInstance().format(count)) },
          date = { millis -> formatDate(millis, Locale.getDefault(), System.currentTimeMillis()) }
        )
      }
    }
  }

  companion object {
    /** card-visual §3.10: every kind in kinds.toml, reserved ones included (they only ever show on brand shells). */
    private val KIND_NAMES: Map<String, Int> = mapOf(
      "video" to R.string.TellomiLinkCard__kind_video,
      "channel" to R.string.TellomiLinkCard__kind_channel,
      "music.track" to R.string.TellomiLinkCard__kind_music_track,
      "music.album" to R.string.TellomiLinkCard__kind_music_album,
      "music.playlist" to R.string.TellomiLinkCard__kind_music_playlist,
      "place" to R.string.TellomiLinkCard__kind_place,
      "app" to R.string.TellomiLinkCard__kind_app,
      "repo" to R.string.TellomiLinkCard__kind_repo,
      "article" to R.string.TellomiLinkCard__kind_article,
      "product" to R.string.TellomiLinkCard__kind_product,
      "package" to R.string.TellomiLinkCard__kind_package,
      "question" to R.string.TellomiLinkCard__kind_question,
      "deal" to R.string.TellomiLinkCard__kind_deal,
      "ride" to R.string.TellomiLinkCard__kind_ride,
      "payment" to R.string.TellomiLinkCard__kind_payment,
      "web" to R.string.TellomiLinkCard__kind_web
    )

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
      formatDuration(byKey["duration_ms"])?.let { parts += it }
      return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** `m:ss` under an hour, `h:mm:ss` from an hour; zero, negative or not a number shows nothing (§3.9). */
    @JvmStatic
    fun formatDuration(value: String?): String? {
      val ms = value?.toLongOrNull()?.takeIf { it > 0 } ?: return null
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

    /** Medium length date in the device's time zone, without the year when it is this year (§3.9). */
    @JvmStatic
    fun formatDate(millis: Long, locale: Locale, nowMillis: Long): String {
      val then = Calendar.getInstance().apply { timeInMillis = millis }
      val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
      val skeleton = if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "MMMd" else "yMMMd"
      return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(millis))
    }

  }
}
