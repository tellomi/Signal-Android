/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.messages

import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.internal.push.Preview
import org.whispersystems.signalservice.internal.push.RichContent

/**
 * ADR-0063 §4.5 / §7.4: `Preview.rich` (field 1000) travels with a message as bytes. A received value is kept as it
 * arrived — fields this build does not know included — and goes out again unchanged when the message is forwarded.
 * It is never trimmed against the registry of the day; which card it becomes is decided at render time (§5.1).
 *
 * "As it arrived": Wire keeps every field it does not know in `unknownFields` and writes those back after the known
 * ones, so re-encoding what it decoded gives back the received bytes for any encoder that writes fields in ascending
 * order (prost, Wire, SwiftProtobuf and protopiler do).
 */
object TellomiRichContent {

  private val TAG = Log.tag(TellomiRichContent::class.java)

  /** The bytes of a received [Preview.rich], or null when the preview has none. */
  @JvmStatic
  fun receivedBytes(preview: Preview): ByteArray? {
    return preview.rich?.encode()
  }

  /**
   * The [RichContent] to put on an outgoing [Preview]. Null — the field stays absent and the preview encodes byte for
   * byte as it did before the field existed — when there are no bytes, or when stored bytes no longer parse: the
   * snapshot (fields 1–5) always stands on its own, so a bad value never fails the send (§7.1).
   */
  @JvmStatic
  fun forSending(bytes: ByteArray?): RichContent? {
    if (bytes == null) {
      return null
    }
    return try {
      RichContent.ADAPTER.decode(bytes)
    } catch (e: Exception) {
      Log.w(TAG, "Dropping unparsable rich content (${bytes.size} bytes)", e)
      null
    }
  }
}
