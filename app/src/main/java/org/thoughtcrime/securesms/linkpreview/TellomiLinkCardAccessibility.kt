/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.content.Context
import org.thoughtcrime.securesms.R

/**
 * card-visual §3.6 (audit F1, F2): what a screen reader says for a link card as a whole. One sentence, so the card is a single
 * stop and its parts are not read again one by one; the words and their order are the same on every client.
 *
 * - a link to a website: `Link, <title>, <domain>`;
 * - a Tellomi object (user, group, call, sticker pack, official website): `<kind>, <title>, <subtitle>, button: <action>`.
 *
 * The parts that are not there are left out (no title, no subtitle), and a part is not said twice: a user with no name is titled
 * with the kind, the official website is titled with its kind, and a plain-link card has the domain as its title.
 */
object TellomiLinkCardAccessibility {

  /** The words, per language; [separator] goes between the parts. */
  class Strings(
    val separator: String,
    val link: String,
    val buttonWithAction: (String) -> String,
    val kindUser: String,
    val kindGroup: String,
    val kindCall: String,
    val kindStickerPack: String,
    val kindOfficial: String
  ) {
    companion object {
      @JvmStatic
      fun from(context: Context): Strings {
        return Strings(
          separator = context.getString(R.string.TellomiLinkCard__a11y_separator),
          link = context.getString(R.string.TellomiLinkCard__a11y_link),
          buttonWithAction = { action -> context.getString(R.string.TellomiLinkCard__a11y_button_with_action, action) },
          kindUser = context.getString(R.string.TellomiLinkCard__tellomi_user),
          kindGroup = context.getString(R.string.TellomiLinkCard__a11y_kind_group),
          kindCall = context.getString(R.string.TellomiLinkCard__call_title),
          kindStickerPack = context.getString(R.string.TellomiLinkCard__a11y_kind_sticker_pack),
          kindOfficial = context.getString(R.string.TellomiLinkCard__official_title)
        )
      }
    }
  }

  /** A link to a website: `Link, <title>, <domain>`. A card with no title (a plain link) is `Link, <domain>`. */
  @JvmStatic
  fun link(title: String?, domain: String?, strings: Strings): String {
    val parts = mutableListOf(strings.link)
    val shownTitle = title.nonBlank()
    if (shownTitle != null) {
      parts += shownTitle
    }
    val shownDomain = domain.nonBlank()
    if (shownDomain != null && shownDomain != shownTitle) {
      parts += shownDomain
    }
    return parts.joinToString(strings.separator)
  }

  /** A Tellomi object: `<kind>, <title>, <subtitle>, button: <action>`. */
  @JvmStatic
  fun firstParty(display: TellomiFirstPartyCard.Display, strings: Strings): String {
    val kind = when (display.type) {
      TellomiFirstPartyCard.Type.USER -> strings.kindUser
      TellomiFirstPartyCard.Type.GROUP -> strings.kindGroup
      TellomiFirstPartyCard.Type.CALL -> strings.kindCall
      TellomiFirstPartyCard.Type.STICKER -> strings.kindStickerPack
      TellomiFirstPartyCard.Type.OFFICIAL -> strings.kindOfficial
    }

    val parts = mutableListOf(kind)
    val title = display.title.nonBlank()
    if (title != null && title != kind) {
      parts += title
    }
    val subtitle = display.subtitle.nonBlank()
    if (subtitle != null && subtitle != kind && subtitle != title) {
      parts += subtitle
    }
    display.action.nonBlank()?.let { parts += strings.buttonWithAction(it) }
    return parts.joinToString(strings.separator)
  }

  private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
