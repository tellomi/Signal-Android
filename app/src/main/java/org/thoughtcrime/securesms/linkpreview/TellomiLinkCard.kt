/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.signal.core.util.Hex
import java.util.Locale

/**
 * ADR-0063 §4.2 / §5.1 / §5.3: what rust/links' `classify` decided for one received preview (the
 * JSON shape is `rust/links/src/classify.rs` `Card`). Everything the sender wrote has already been
 * re-checked against this device's registry and the URL in the message body.
 */
@Serializable
data class TellomiLinkCard(
  val level: Level,
  val provider: String? = null,
  @SerialName("provider_name") val providerName: LocalizedName? = null,
  val kind: String? = null,
  val route: String? = null,
  val title: String? = null,
  val description: String? = null,
  val attrs: List<Attr> = emptyList(),
  val domain: String? = null,
  @SerialName("official_badge") val officialBadge: Boolean = false,
  @SerialName("first_party") val firstParty: FirstParty? = null,
  val lookalike: String? = null,
  @SerialName("show_image") val showImage: Boolean = true,
  /**
   * Brand shell only: the file name of the icon bundled with the app (`assets/links/icons/`), drawn from the package
   * and never fetched (ADR-0063 §九.6). Null: no icon, the shell shows the name and the domain only. A name is
   * only ever read through [TellomiBrandIcons], which checks its shape first.
   */
  val icon: String? = null,
  val tintable: Boolean = false,
  val payment: Boolean = false,
  val reason: String? = null
) {

  @Serializable
  enum class Level {
    @SerialName("plain_link")
    PLAIN_LINK,

    @SerialName("generic")
    GENERIC,

    @SerialName("brand")
    BRAND,

    @SerialName("structured")
    STRUCTURED,

    @SerialName("first_party")
    FIRST_PARTY
  }

  /** The registry names providers in zh-Hans, optionally zh-Hant, and en. */
  @Serializable
  data class LocalizedName(
    @SerialName("zh-Hans") val zhHans: String,
    @SerialName("zh-Hant") val zhHant: String? = null,
    val en: String
  ) {
    fun forLocale(locale: Locale): String {
      if (locale.language != "zh") {
        return en
      }
      val traditional = locale.script == "Hant" || locale.country in setOf("HK", "MO", "TW")
      return if (traditional) zhHant ?: zhHans else zhHans
    }
  }

  @Serializable
  data class Attr(val key: String, val value: String)

  /** `type`: user, group, call, sticker or official; the other fields depend on it. */
  @Serializable
  data class FirstParty(
    val type: String,
    val display: String? = null,
    val username: String? = null,
    val title: String? = null,
    @SerialName("member_count") val memberCount: Long? = null,
    @SerialName("sticker_count") val stickerCount: Long? = null,
    val path: String? = null
  )

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun parse(json: String): TellomiLinkCard? {
      return try {
        JSON.decodeFromString(serializer(), json)
      } catch (e: IllegalArgumentException) {
        null
      }
    }

    /** `rust/links` `PreviewInput`: the upstream fields 1–5 plus the raw bytes of field 1000 as hex. */
    @JvmStatic
    fun previewInputJson(linkPreview: LinkPreview): String {
      return buildJsonObject {
        put("url", linkPreview.url)
        linkPreview.title.takeIf { it.isNotEmpty() }?.let { put("title", it) }
        linkPreview.description.takeIf { it.isNotEmpty() }?.let { put("description", it) }
        put("has_image", linkPreview.thumbnail.isPresent)
        linkPreview.date.takeIf { it > 0 }?.let { put("date", it) }
        linkPreview.rich?.let { put("rich", Hex.toStringCondensed(it)) }
      }.toString()
    }

    /** `rust/links` `MessageContext`. */
    @JvmStatic
    fun messageContextJson(isStory: Boolean, attachmentContentTypes: List<String>): String {
      return buildJsonObject {
        put("is_story", isStory)
        put("attachment_content_types", buildJsonArray { attachmentContentTypes.forEach { add(it) } })
      }.toString()
    }
  }
}
