/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log

/**
 * ADR-0063 §5.1 rule 4 / §6.1 / §7.4: what to write of a preview that has just arrived, decided by rust/links (the same
 * `classify` that draws it) before anything is stored.
 *
 * - `receive_check` says whether to keep the preview at all (URL valid and in the body, or a story), and whether to keep its
 *   `rich` (not oversized or malformed). Dropping the preview leaves the message itself alone. A `rich` that is kept is stored
 *   as it arrived, byte for byte (§7.4); this only ever drops it.
 * - The preview's image is only kept when the card that will be drawn shows it (§7.4): `classify` says `show_image` = false for a
 *   plain link, a brand shell, a user or official card, or a message with attachments; then no attachment pointer is made and
 *   nothing is queued for download. The level is decided now, from the registry of the day; a later hot update does not fetch
 *   an image that was not downloaded.
 *
 * Fail-open: without a registry, or when rust/links or its bridge fails, there is no verdict and the caller behaves as it did
 * before this existed (nothing is dropped by this); only the class of the failure is logged, never the URL.
 */
object TellomiLinkReceive {

  private val TAG = Log.tag(TellomiLinkReceive::class.java)

  /** What to store of one received preview. [keepImage] only matters when the preview has an image. */
  data class Verdict(val keepPreview: Boolean, val keepRich: Boolean, val keepImage: Boolean)

  sealed interface Outcome {
    /** rust/links could not say: the caller does what it did before. */
    data object Undecided : Outcome

    /** The message is kept, without this preview. */
    data object Dropped : Outcome

    /** The preview to store: [LinkPreview.withKept] of what arrived. */
    data class Kept(val preview: LinkPreview) : Outcome
  }

  /** What to do with [received], the preview as it arrived (thumbnail pointer and raw `rich` included). */
  @JvmStatic
  @WorkerThread
  fun receive(received: LinkPreview, body: String, isStory: Boolean, attachmentContentTypes: List<String>): Outcome {
    return outcome(received, decide(received, body, isStory, attachmentContentTypes))
  }

  @VisibleForTesting
  @JvmStatic
  fun outcome(received: LinkPreview, verdict: Verdict?): Outcome {
    return when {
      verdict == null -> Outcome.Undecided
      !verdict.keepPreview -> Outcome.Dropped
      else -> Outcome.Kept(received.withKept(verdict.keepImage, verdict.keepRich))
    }
  }

  /** Null: no decision (fail-open, see above). */
  @JvmStatic
  @WorkerThread
  fun decide(preview: LinkPreview, body: String, isStory: Boolean, attachmentContentTypes: List<String>): Verdict? {
    return decide(preview, body, isStory, attachmentContentTypes, TellomiLinkRegistry::receiveCheck, TellomiLinkRegistry::classify)
  }

  @VisibleForTesting
  @JvmStatic
  fun decide(
    preview: LinkPreview,
    body: String,
    isStory: Boolean,
    attachmentContentTypes: List<String>,
    receiveCheck: (LinkPreview, String, Boolean, List<String>) -> TellomiReceiveCheck?,
    classify: (LinkPreview, String, Boolean, List<String>) -> TellomiLinkCard?
  ): Verdict? {
    val checked = guarded("receive_check") { receiveCheck(preview, body, isStory, attachmentContentTypes) } ?: return null
    if (!checked.keepPreview) {
      return Verdict(keepPreview = false, keepRich = false, keepImage = false)
    }

    // Only asked when there is an image to keep or drop; a failure keeps it, as before.
    val keepImage = if (preview.thumbnail.isPresent) {
      guarded("classify") { classify(preview, body, isStory, attachmentContentTypes) }?.showImage ?: true
    } else {
      true
    }
    return Verdict(keepPreview = true, keepRich = checked.keepRich, keepImage = keepImage)
  }

  /** The bridge is native code: a library that is not there is [LinkageError], not an exception. Neither may fail a received message. */
  private inline fun <T> guarded(what: String, block: () -> T?): T? {
    return try {
      block()
    } catch (e: Exception) {
      Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
      null
    } catch (e: LinkageError) {
      Log.w(TAG, "$what is not available: ${e.javaClass.simpleName}")
      null
    }
  }
}
