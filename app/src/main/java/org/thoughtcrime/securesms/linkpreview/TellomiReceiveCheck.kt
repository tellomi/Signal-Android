/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ADR-0063 §7.4: what rust/links' `receive_check` says about a preview that has just arrived (the JSON shape is
 * `rust/links/src/classify.rs` `ReceiveCheck`). Everything else about the preview is decided when it is drawn.
 */
@Serializable
data class TellomiReceiveCheck(
  /** false: the preview is dropped whole (the message itself is kept). */
  @SerialName("keep_preview") val keepPreview: Boolean,
  /** false: only `rich` is dropped (too big, malformed); the snapshot stays. When true the bytes are stored as they arrived. */
  @SerialName("keep_rich") val keepRich: Boolean
) {
  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun parse(json: String): TellomiReceiveCheck? {
      return try {
        JSON.decodeFromString(serializer(), json)
      } catch (e: IllegalArgumentException) {
        null
      }
    }
  }
}
