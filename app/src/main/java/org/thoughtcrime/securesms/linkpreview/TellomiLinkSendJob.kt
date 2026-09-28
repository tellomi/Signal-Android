/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.signal.libsignal.links.LinkJob
import java.util.Locale
import kotlin.math.min

/**
 * ADR-0063 §4.2 / §4.4 / §5.2, sender side: rust/links decides what to fetch, the client fetches it and feeds
 * the result back, rust/links assembles the preview (snapshot + `Preview.rich`). This drives one link's job:
 * begin → next request → perform → feed back → … → finish, within the 10 s budget per link.
 *
 * The same loop as Desktop's `linkSendJob.std.ts`; the requests are performed by [TellomiLinkFetcher].
 */
object TellomiLinkSendJob {

  const val LINK_BUDGET_MS = 10_000L

  private val JSON = Json { ignoreUnknownKeys = true }

  /** The parts of libsignal's [LinkJob] this uses. */
  interface Job {
    fun nextRequest(): String?
    fun onResponse(id: Int, status: Int, finalUrl: String, contentType: String, location: String?, body: ByteArray)
    fun onNetworkError(id: Int)
    fun onFailure(id: Int)
    fun onFirstParty(id: Int, result: String)
    fun onImage(id: Int, ok: Boolean)
    fun finish(): String
  }

  class NativeJob(private val job: LinkJob) : Job {
    override fun nextRequest(): String? = job.nextRequest()
    override fun onResponse(id: Int, status: Int, finalUrl: String, contentType: String, location: String?, body: ByteArray) = job.onResponse(id, status, finalUrl, contentType, location, body)
    override fun onNetworkError(id: Int) = job.onNetworkError(id)
    override fun onFailure(id: Int) = job.onFailure(id)
    override fun onFirstParty(id: Int, result: String) = job.onFirstParty(id, result)
    override fun onImage(id: Int, ok: Boolean) = job.onImage(id, ok)
    override fun finish(): String = job.finish()
  }

  /** One request from rust/links. `timeoutMs` is already cut to what is left of the link's budget. */
  sealed class Request {
    abstract val id: Int

    /** Read the first `Location` only; never follow it, never read the body. */
    data class Expand(override val id: Int, val url: String, val timeoutMs: Long) : Request()

    data class Fetch(
      override val id: Int,
      val url: String,
      val accept: String,
      val contentTypes: List<String>,
      val maxBytes: Long,
      val maxRedirects: Int,
      val timeoutMs: Long
    ) : Request()

    /** A tell.cc object, looked up with the client's own existing lookup (group join info, call link, sticker manifest). */
    data class FirstParty(override val id: Int, val kind: String) : Request()

    data class Image(override val id: Int, val url: String, val maxRedirects: Int, val timeoutMs: Long) : Request()
  }

  /** What a fetch reports back. */
  sealed class Exchange {
    class Response(val status: Int, val finalUrl: String, val contentType: String, val location: String?, val body: ByteArray) : Exchange() {
      override fun toString(): String = "Response($status, ${body.size} bytes)"
    }

    /** DNS / TCP / TLS: nothing was exchanged with the site. rust/links remembers the host as unreachable. */
    object NetworkError : Exchange()

    /** Anything else: a timeout after connecting, too large, a rejected hop, cancelled… */
    object Failure : Exchange()
  }

  /** What the client found for a tell.cc object. `invalid`: the group link is definitely not active. */
  @Serializable
  data class FirstPartyResult(
    val ok: Boolean,
    val invalid: Boolean? = null,
    val title: String? = null,
    @SerialName("member_count") val memberCount: Int? = null,
    @SerialName("sticker_count") val stickerCount: Int? = null
  )

  interface Deps {
    fun fetch(request: Request): Exchange

    /** Fetch the preview image and validate / re-encode it the way Signal does; false if it is not usable. */
    fun image(request: Request.Image): Boolean

    fun firstParty(kind: String): FirstPartyResult

    fun now(): Long

    fun isCancelled(): Boolean
  }

  @Serializable
  data class Outcome(
    val level: String,
    val provider: String? = null,
    val route: String? = null,
    val kind: String? = null,
    val preview: Preview? = null,
    @SerialName("group_link_invalid") val groupLinkInvalid: Boolean = false,
    val lookalike: String? = null,
    @SerialName("newly_unreachable_hosts") val newlyUnreachableHosts: List<String> = emptyList(),
    val failures: List<String> = emptyList()
  )

  @Serializable
  data class Preview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val date: Long? = null,
    @SerialName("rich_hex") val richHex: String? = null
  )

  @Serializable
  private data class Context(
    val region: String,
    @SerialName("unreachable_hosts") val unreachableHosts: List<String>,
    @SerialName("expand_short_links") val expandShortLinks: Boolean,
    val locale: String
  )

  @JvmStatic
  fun contextJson(unreachableHosts: List<String>, expandShortLinks: Boolean, locale: Locale): String {
    return JSON.encodeToString(
      Context.serializer(),
      Context(
        region = TellomiLinkReachability.P1_REGION_PRIOR,
        unreachableHosts = unreachableHosts,
        expandShortLinks = expandShortLinks,
        locale = locale.toLanguageTag()
      )
    )
  }

  @JvmStatic
  fun parseOutcome(json: String): Outcome? {
    return try {
      JSON.decodeFromString(Outcome.serializer(), json)
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  /** A request as rust/links wrote it, or null for anything this build does not understand. */
  @JvmStatic
  fun parseRequest(json: String, remainingMs: Long): Request? {
    return try {
      val o = JSON.parseToJsonElement(json).jsonObject
      val id = o["id"]!!.jsonPrimitive.int
      fun timeout() = min(o["timeout_ms"]!!.jsonPrimitive.long, remainingMs)
      when (o.string("type")) {
        "expand_short_link" -> Request.Expand(id, o.string("url"), timeout())
        "fetch" -> Request.Fetch(
          id = id,
          url = o.string("url"),
          accept = o.string("accept"),
          contentTypes = o["content_types"]!!.jsonArray.map { it.jsonPrimitive.content.lowercase(Locale.ROOT) },
          maxBytes = o["max_bytes"]!!.jsonPrimitive.long,
          maxRedirects = o["max_redirects"]!!.jsonPrimitive.int,
          timeoutMs = timeout()
        )
        "first_party" -> Request.FirstParty(id, o.string("kind"))
        "image" -> Request.Image(id, o.string("url"), o["max_redirects"]!!.jsonPrimitive.int, timeout())
        else -> null
      }
    } catch (e: RuntimeException) {
      null
    }
  }

  private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

  /**
   * Runs [job] to the end. Null: cancelled, or rust/links asked for something this build does not
   * understand (then no preview rather than a guess). [onRequest] sees every request as rust/links wrote it.
   */
  @JvmStatic
  @JvmOverloads
  fun run(job: Job, deps: Deps, onRequest: ((String) -> Unit)? = null): Outcome? {
    val deadline = deps.now() + LINK_BUDGET_MS

    while (true) {
      if (deps.isCancelled()) {
        return null
      }
      val remaining = deadline - deps.now()
      if (remaining <= 0) {
        break // Budget spent: settle for what is already known (§5.2).
      }
      val json = job.nextRequest() ?: break
      onRequest?.invoke(json)

      when (val request = parseRequest(json, remaining) ?: return null) {
        is Request.Expand, is Request.Fetch -> feed(job, request.id, deps.fetch(request))
        is Request.FirstParty -> job.onFirstParty(request.id, JSON.encodeToString(FirstPartyResult.serializer(), deps.firstParty(request.kind)))
        is Request.Image -> job.onImage(request.id, deps.image(request))
      }
    }

    if (deps.isCancelled()) {
      return null
    }
    return parseOutcome(job.finish())
  }

  private fun feed(job: Job, id: Int, exchange: Exchange) {
    when (exchange) {
      is Exchange.Response -> job.onResponse(id, exchange.status, exchange.finalUrl, exchange.contentType, exchange.location, exchange.body)
      Exchange.NetworkError -> job.onNetworkError(id)
      Exchange.Failure -> job.onFailure(id)
    }
  }
}
