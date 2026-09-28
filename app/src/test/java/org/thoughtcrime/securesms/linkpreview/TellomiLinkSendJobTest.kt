/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.linkpreview.TellomiLinkSendJob.Exchange
import org.thoughtcrime.securesms.linkpreview.TellomiLinkSendJob.Request
import java.util.Locale

/**
 * ADR-0063 §4.2 / §5.2 (tellomi/tellomi#1422): the send-side loop. Every golden `send` case from rust/links,
 * replayed through [TellomiLinkSendJob.run] with the scripted responses, must ask for exactly the golden
 * requests and finish with exactly the golden outcome.
 */
class TellomiLinkSendJobTest {

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  private val golden = Json.parseToJsonElement(resource("send-golden.json").decodeToString()).jsonObject
  private val registry by lazy { LinkRegistry.load(resource(golden["registry"]!!.jsonPrimitive.content)) }

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  /** Answers from a golden case's `script`, and what was asked. */
  private class ScriptedDeps(private val script: JsonObject, var clock: Long = 0) : TellomiLinkSendJob.Deps {
    val asked = mutableListOf<Request>()
    var cancelled = false

    override fun fetch(request: Request): Exchange {
      asked += request
      val url = when (request) {
        is Request.Fetch -> request.url
        is Request.Expand -> request.url
        else -> error("not a fetch")
      }
      if (script["network_error"]?.jsonArray?.any { it.jsonPrimitive.content == url } == true) {
        return Exchange.NetworkError
      }
      val response = script["responses"]?.jsonObject?.get(url)?.jsonObject ?: return Exchange.Failure
      return Exchange.Response(
        status = response["status"]!!.jsonPrimitive.int,
        finalUrl = response["final_url"]!!.jsonPrimitive.content,
        contentType = response["content_type"]?.jsonPrimitive?.contentOrNull ?: "",
        location = response["location"]?.jsonPrimitive?.contentOrNull,
        body = (response["body"]?.jsonPrimitive?.contentOrNull ?: "").toByteArray()
      )
    }

    override fun image(request: Request.Image): Boolean {
      asked += request
      return script["image_ok"]?.jsonPrimitive?.boolean ?: false
    }

    override fun firstParty(kind: String): TellomiLinkSendJob.FirstPartyResult {
      asked += Request.FirstParty(0, kind)
      val json = script["first_party"]?.jsonPrimitive?.contentOrNull ?: return TellomiLinkSendJob.FirstPartyResult(ok = false)
      return Json.decodeFromString(TellomiLinkSendJob.FirstPartyResult.serializer(), json)
    }

    override fun now(): Long = clock

    override fun isCancelled(): Boolean = cancelled
  }

  @Test
  fun `replays every golden send case`() {
    val cases = golden["send"]!!.jsonArray.map { it.jsonObject }
    assertEquals(5, cases.size)

    for (case in cases) {
      val name = case.str("name")!!
      val job = TellomiLinkSendJob.NativeJob(registry.begin(case.str("url")!!, case.str("context")!!))
      val deps = ScriptedDeps(case["script"]!!.jsonObject)
      val requests = mutableListOf<String>()

      val outcome = TellomiLinkSendJob.run(job, deps) { requests += it }

      assertEquals(name, case["requests"]!!.jsonArray.map { it.jsonPrimitive.content }, requests)
      assertEquals(name, TellomiLinkSendJob.parseOutcome(case.str("outcome")!!), outcome)
    }
  }

  @Test
  fun `the golden outcome is byte for byte what finish gives`() {
    val case = golden["send"]!!.jsonArray.map { it.jsonObject }.first { it.str("name") == "App Store public API + image" }
    var finished: String? = null
    val native = TellomiLinkSendJob.NativeJob(registry.begin(case.str("url")!!, case.str("context")!!))
    val job = object : TellomiLinkSendJob.Job by native {
      override fun finish(): String = native.finish().also { finished = it }
    }

    TellomiLinkSendJob.run(job, ScriptedDeps(case["script"]!!.jsonObject))

    assertEquals(case.str("outcome"), finished)
  }

  @Test
  fun `requests are parsed with the time left, and unknown ones are refused`() {
    val fetch = TellomiLinkSendJob.parseRequest(
      """{"id":1,"type":"fetch","url":"https://itunes.apple.com/cn/lookup?id=1","user_agent":"WhatsApp/2","accept":"application/json","content_types":["application/json","Text/JavaScript"],"max_bytes":262144,"max_redirects":5,"connect_timeout_ms":5000,"timeout_ms":10000}""",
      remainingMs = 4_000
    )
    assertEquals(
      Request.Fetch(1, "https://itunes.apple.com/cn/lookup?id=1", "application/json", listOf("application/json", "text/javascript"), 262_144, 5, 4_000),
      fetch
    )
    assertEquals(Request.Expand(2, "https://b23.tv/x", 10_000), TellomiLinkSendJob.parseRequest("""{"id":2,"type":"expand_short_link","url":"https://b23.tv/x","user_agent":"WhatsApp/2","connect_timeout_ms":5000,"timeout_ms":10000}""", 20_000))
    assertNull(TellomiLinkSendJob.parseRequest("""{"id":3,"type":"teleport"}""", 10_000))
    assertNull(TellomiLinkSendJob.parseRequest("""{"id":4,"type":"fetch"}""", 10_000))
    assertNull(TellomiLinkSendJob.parseRequest("not json", 10_000))
  }

  @Test
  fun `a request this build does not understand gives no preview`() {
    val job = FakeJob(listOf("""{"id":1,"type":"teleport"}"""))
    assertNull(TellomiLinkSendJob.run(job, ScriptedDeps(JsonObject(emptyMap()))))
    assertEquals(false, job.finished)
  }

  @Test
  fun `a spent budget settles for what is known`() {
    val deps = ScriptedDeps(JsonObject(emptyMap()))
    val job = object : FakeJob(List(5) { """{"id":$it,"type":"fetch","url":"https://a.example/$it","user_agent":"WhatsApp/2","accept":"text/html","content_types":["text/html"],"max_bytes":1,"max_redirects":5,"connect_timeout_ms":5000,"timeout_ms":10000}""" }) {
      override fun onFailure(id: Int) {
        super.onFailure(id)
        deps.clock += 6_000
      }
    }

    val outcome = TellomiLinkSendJob.run(job, deps)

    assertEquals(2, deps.asked.size)
    assertEquals(listOf(4_000L), deps.asked.drop(1).map { (it as Request.Fetch).timeoutMs })
    assertTrue(job.finished)
    assertEquals("generic", outcome!!.level)
  }

  @Test
  fun `cancelling stops the job without an outcome`() {
    val deps = ScriptedDeps(JsonObject(emptyMap()))
    val job = object : FakeJob(List(3) { """{"id":$it,"type":"first_party","kind":"tellomi.group"}""" }) {
      override fun onFirstParty(id: Int, result: String) {
        super.onFirstParty(id, result)
        deps.cancelled = true
      }
    }

    assertNull(TellomiLinkSendJob.run(job, deps))
    assertEquals(1, deps.asked.size)
    assertEquals(false, job.finished)
  }

  @Test
  fun `the context carries the unreachable hosts, the short-link switch and the locale`() {
    assertEquals(
      """{"region":"global","unreachable_hosts":["www.bilibili.com"],"expand_short_links":false,"locale":"zh-Hant-TW"}""",
      TellomiLinkSendJob.contextJson(listOf("www.bilibili.com"), false, Locale.forLanguageTag("zh-Hant-TW"))
    )
  }

  @Test
  fun `a first-party result leaves out what it does not know`() {
    assertEquals(
      """{"ok":true,"title":"周末爬山群"}""",
      Json.encodeToString(TellomiLinkSendJob.FirstPartyResult.serializer(), TellomiLinkSendJob.FirstPartyResult(ok = true, title = "周末爬山群"))
    )
    assertEquals(
      """{"ok":false,"invalid":true}""",
      Json.encodeToString(TellomiLinkSendJob.FirstPartyResult.serializer(), TellomiLinkSendJob.FirstPartyResult(ok = false, invalid = true))
    )
  }

  private open class FakeJob(requests: List<String>) : TellomiLinkSendJob.Job {
    private val queue = ArrayDeque(requests)
    var finished = false
    val calls = mutableListOf<String>()

    override fun nextRequest(): String? = queue.removeFirstOrNull()
    override fun onResponse(id: Int, status: Int, finalUrl: String, contentType: String, location: String?, body: ByteArray) {
      calls += "response $id $status"
    }
    override fun onNetworkError(id: Int) {
      calls += "network $id"
    }
    override fun onFailure(id: Int) {
      calls += "failure $id"
    }
    override fun onFirstParty(id: Int, result: String) {
      calls += "first_party $id"
    }
    override fun onImage(id: Int, ok: Boolean) {
      calls += "image $id $ok"
    }
    override fun finish(): String {
      finished = true
      return """{"level":"generic","provider":null,"route":null,"kind":null,"preview":null,"group_link_invalid":false,"lookalike":null,"newly_unreachable_hosts":[],"failures":[]}"""
    }
  }
}
