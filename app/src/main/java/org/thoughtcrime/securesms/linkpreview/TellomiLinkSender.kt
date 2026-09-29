/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.signal.core.util.Hex
import org.signal.core.util.logging.Log
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.linkpreview.TellomiLinkFetcher.Reason
import org.thoughtcrime.securesms.linkpreview.TellomiLinkFetcher.Step
import org.thoughtcrime.securesms.linkpreview.TellomiLinkSendJob.Exchange
import org.thoughtcrime.securesms.linkpreview.TellomiLinkSendJob.Request
import java.util.Locale
import java.util.Optional

/**
 * The composer's link preview when the link registry is loaded (ADR-0063 §4.2 / §4.4 / §5.2,
 * tellomi/tellomi#1422): rust/links decides what to fetch and assembles the preview (snapshot +
 * `Preview.rich`), [TellomiLinkFetcher] does the requests, and tell.cc objects (groups, stickers,
 * call links) use Signal's own lookups. Same as Desktop's `getTellomiPreview`.
 *
 * Logs provider, route, level and failure classes only — never the URL (§6.5).
 */
class TellomiLinkSender(
  private val fetcher: TellomiLinkFetcher,
  private val expandShortLinks: () -> Boolean,
  private val locale: () -> Locale,
  private val lookups: Lookups
) {

  companion object {
    private val TAG = Log.tag(TellomiLinkSender::class.java)

    /** What rust/links is told about one fetch. */
    @VisibleForTesting
    @JvmStatic
    fun toExchange(requestUrl: String, result: TellomiLinkFetcher.Result): Exchange {
      return when (result) {
        is TellomiLinkFetcher.Result.Body -> Exchange.Response(200, result.finalUrl.toString(), result.contentType.toString(), null, result.bytes)
        is TellomiLinkFetcher.Result.Location -> Exchange.Response(result.status, requestUrl, "", result.location.toString(), ByteArray(0))
        is TellomiLinkFetcher.Result.Failure -> when {
          result.beforeConnect -> Exchange.NetworkError
          // rust/links reads the status itself (a short link that did not redirect, a page that is not there).
          result.reason == Reason.HTTP_STATUS || result.reason == Reason.NOT_REDIRECT -> {
            Exchange.Response(result.httpCode, result.finalUrl?.toString() ?: requestUrl, "", null, ByteArray(0))
          }
          else -> Exchange.Failure
        }
      }
    }
  }

  /** What the client already knows how to do; both block and are called on the job's thread. */
  interface Lookups {
    /** Signal's lookup for a tell.cc object of this kind (`tellomi.group`, `tellomi.sticker`, `tellomi.call`). */
    fun firstParty(kind: String, url: String): FirstParty

    /** Validates and re-encodes a downloaded preview image the way Signal does; null if it is not usable. */
    fun thumbnail(bytes: ByteArray): Attachment?
  }

  sealed class FirstParty {
    /**
     * The preview Signal's lookup made; its title and image are used. [count]: how many members the group or
     * stickers the pack has, if the lookup knows (ADR-0063 §4.8, card-visual §3.9).
     */
    class Found @JvmOverloads constructor(val preview: LinkPreview, val count: Int? = null) : FirstParty()

    /** The group link is definitely not active. */
    object Inactive : FirstParty()

    object NotFound : FirstParty()
  }

  sealed class Result {
    class Found(val preview: LinkPreview) : Result()
    object NotAvailable : Result()
    object GroupLinkInactive : Result()
  }

  @WorkerThread
  fun preview(registry: LinkRegistry, url: String, session: TellomiLinkFetcher.Session, isCancelled: () -> Boolean): Result? {
    val context = TellomiLinkSendJob.contextJson(fetcher.unreachableHosts(), expandShortLinks(), locale())
    return preview(TellomiLinkSendJob.NativeJob(registry.begin(url, context)), url, session, isCancelled)
  }

  /** Null: cancelled. */
  @WorkerThread
  fun preview(job: TellomiLinkSendJob.Job, url: String, session: TellomiLinkFetcher.Session, isCancelled: () -> Boolean): Result? {
    var firstParty: LinkPreview? = null
    var thumbnail: Attachment? = null

    val deps = object : TellomiLinkSendJob.Deps {
      override fun fetch(request: Request): Exchange = perform(session, request)

      override fun image(request: Request.Image): Boolean {
        val result = session.fetch(request.url, TellomiLinkFetcher.Spec(Step.IMAGE, maxRedirects = request.maxRedirects, timeoutMs = request.timeoutMs))
        if (result !is TellomiLinkFetcher.Result.Body) {
          return false
        }
        thumbnail = lookups.thumbnail(result.bytes)
        return thumbnail != null
      }

      override fun firstParty(kind: String): TellomiLinkSendJob.FirstPartyResult {
        return when (val found = lookups.firstParty(kind, url)) {
          is FirstParty.Found -> {
            firstParty = found.preview
            // How many members / stickers, for the receiver's card (card-visual §3.9); only a count above zero.
            val count = found.count?.takeIf { it > 0 }
            TellomiLinkSendJob.FirstPartyResult(
              ok = true,
              title = found.preview.title.takeIf { it.isNotEmpty() },
              memberCount = count?.takeIf { kind == "tellomi.group" },
              stickerCount = count?.takeIf { kind == "tellomi.sticker" }
            )
          }
          FirstParty.Inactive -> TellomiLinkSendJob.FirstPartyResult(ok = false, invalid = true)
          FirstParty.NotFound -> TellomiLinkSendJob.FirstPartyResult(ok = false)
        }
      }

      override fun now(): Long = System.currentTimeMillis()

      override fun isCancelled(): Boolean = isCancelled()
    }

    val outcome = TellomiLinkSendJob.run(job, deps) ?: return if (isCancelled()) null else Result.NotAvailable
    Log.i(TAG, "${outcome.provider ?: "-"}/${outcome.route ?: "-"} ${outcome.level} [${outcome.failures.joinToString(",")}]")

    if (outcome.groupLinkInvalid) {
      return Result.GroupLinkInactive
    }
    val preview = outcome.preview ?: return Result.NotAvailable

    // Group avatars, sticker covers and call avatars come from the client itself.
    val image: Optional<Attachment> = when {
      firstParty != null -> firstParty!!.thumbnail
      preview.imageUrl != null -> Optional.ofNullable(thumbnail)
      else -> Optional.empty()
    }
    val rich = preview.richHex?.let { Hex.fromStringCondensed(it) }
    return Result.Found(LinkPreview(preview.url, preview.title ?: "", preview.description ?: "", preview.date ?: 0, image, rich))
  }

  private fun perform(session: TellomiLinkFetcher.Session, request: Request): Exchange {
    val (url, spec) = when (request) {
      is Request.Expand -> request.url to TellomiLinkFetcher.Spec(Step.SHORT_LINK, timeoutMs = request.timeoutMs)
      is Request.Fetch -> {
        val step = if (request.contentTypes.any { it == "text/html" || it == "application/xhtml+xml" }) Step.HTML else Step.JSON
        request.url to TellomiLinkFetcher.Spec(
          step = step,
          accept = request.accept,
          contentTypes = request.contentTypes.toSet(),
          maxBytes = request.maxBytes,
          maxRedirects = request.maxRedirects,
          timeoutMs = request.timeoutMs
        )
      }
      else -> return Exchange.Failure
    }
    return toExchange(url, session.fetch(url, spec))
  }
}
