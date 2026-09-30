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
import java.time.OffsetDateTime
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
 * - group, call and sticker cards: Signal's display until the first-party card layout lands (§5.2);
 * - plain link: the no-image card, the domain once as its title (§3.5, only for a message that is just the link).
 *
 * Layout, tint and action buttons are not decided here. Whether the image shows is
 * [TellomiLinkCard.showImage], decided by the caller.
 */
data class TellomiLinkDisplay(
  val title: String?,
  val description: String?,
  val domain: String?,
  val officialBadge: Boolean,
  /** The no-image card (card-visual §3.5 / §3.7): the domain as the title, a link icon at the end, no other line. */
  val plainLink: Boolean = false,
  /** The domain imitates a well-known one (ADR-0063 §6.1): shown in the danger colour. */
  val lookalike: Boolean = false
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

    /** Between the parts of the sub line (card-visual §3.7): U+00B7. */
    private const val SEPARATOR = " \u00B7 "

    /** Between the domain and the publish date on the domain line (card-visual §3.4 / §3.7): U+22C5, not the sub line's. */
    private const val DOMAIN_DATE_SEPARATOR = " \u22C5 "

    private val PLATFORM_NAMES = mapOf("ios" to "iOS", "android" to "Android")

    /** Null: no override, show the preview the way Signal does. */
    @JvmStatic
    fun of(linkPreview: LinkPreview, card: TellomiLinkCard?, locale: Locale, strings: Strings): TellomiLinkDisplay? {
      if (card == null) {
        return null
      }
      return when (card.level) {
        // Reaches a bubble only as the card of a message that is just this link (TellomiLinkOnly).
        TellomiLinkCard.Level.PLAIN_LINK -> TellomiLinkDisplay(
          title = card.domain,
          description = null,
          domain = null,
          officialBadge = false,
          plainLink = true,
          lookalike = card.lookalike != null
        )
        TellomiLinkCard.Level.GENERIC -> generic(linkPreview, card, card.title)
        TellomiLinkCard.Level.BRAND -> TellomiLinkDisplay(
          title = card.providerName?.forLocale(locale),
          description = card.kind?.let(strings.kindName),
          domain = card.domain,
          officialBadge = false
        )
        TellomiLinkCard.Level.STRUCTURED -> structured(linkPreview, card, strings)
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

    private fun generic(linkPreview: LinkPreview, card: TellomiLinkCard, title: String?): TellomiLinkDisplay {
      return TellomiLinkDisplay(
        title = title.nonEmpty() ?: linkPreview.title.nonEmpty(),
        description = null,
        domain = card.domain,
        officialBadge = false
      )
    }

    private fun structured(linkPreview: LinkPreview, card: TellomiLinkCard, strings: Strings): TellomiLinkDisplay {
      val attrs = card.attrs.associate { it.key to it.value }
      fun text(key: String): String? = attrs[key].nonEmpty()
      fun count(key: String): String? = attrs[key]?.toIntOrNull()?.takeIf { it > 0 }?.let(strings.trackCount)
      fun line(vararg parts: String?): String? = parts.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

      return when (card.kind) {
        "video" -> {
          val published = text("published_at")?.let { parseDate(it) }
          TellomiLinkDisplay(
            title = card.title.nonEmpty() ?: linkPreview.title.nonEmpty(),
            description = line(text("author"), formatDuration(attrs["duration_ms"])),
            domain = if (card.domain != null && published != null) card.domain + DOMAIN_DATE_SEPARATOR + strings.date(published) else card.domain,
            officialBadge = false
          )
        }
        "channel" -> withLine(linkPreview, card, line(text("author")))
        "music.track" -> withLine(linkPreview, card, line(text("artist"), text("album"), formatDuration(attrs["duration_ms"])))
        "music.album" -> withLine(linkPreview, card, line(text("artist"), count("track_count")))
        "music.playlist" -> withLine(linkPreview, card, line(text("author"), count("track_count")))
        "app" -> withLine(linkPreview, card, line(text("developer"), attrs["platform"]?.let { PLATFORM_NAMES[it] }))
        "repo" -> withLine(linkPreview, card, line(text("owner")))
        "place" -> TellomiLinkDisplay(
          title = text("name") ?: card.title.nonEmpty() ?: linkPreview.title.nonEmpty() ?: strings.place,
          description = line(text("address")),
          domain = card.domain,
          officialBadge = false
        )
        else -> generic(linkPreview, card, card.title)
      }
    }

    private fun withLine(linkPreview: LinkPreview, card: TellomiLinkCard, subLine: String?): TellomiLinkDisplay {
      return generic(linkPreview, card, card.title).copy(description = subLine)
    }

    /**
     * `m:ss` under an hour, `h:mm:ss` from an hour, rounded to the second. Zero, negative, not a number, or a duration that rounds
     * to zero seconds (1–499 ms) shows nothing (§3.9: "0 or not valid"); the same on every client.
     */
    @JvmStatic
    fun formatDuration(value: String?): String? {
      val ms = value?.toLongOrNull()?.takeIf { it > 0 } ?: return null
      // Half up; written so that a value near Long.MAX_VALUE cannot overflow.
      val totalSeconds = ms / 1000 + if (ms % 1000 >= 500) 1 else 0
      if (totalSeconds <= 0) {
        return null
      }
      val hours = totalSeconds / 3600
      val minutes = (totalSeconds % 3600) / 60
      val seconds = totalSeconds % 60
      return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
      } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
      }
    }

    /** RFC 3339 → epoch millis; null when it does not parse. */
    private fun parseDate(value: String): Long? {
      return try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
      } catch (e: Exception) {
        null
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

    private fun String?.nonEmpty(): String? = this?.takeIf { it.isNotEmpty() }
  }
}
