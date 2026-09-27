/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.linkpreview.TellomiLinkFetcher.Step
import java.util.concurrent.TimeUnit

/**
 * ADR-0063 §6.5 / §8.1 第 9 行：日志里查不到完整 URL，更查不到 `#` 片段。
 * 路径、查询和片段里都放一个 canary 字符串，把抓取器的每条分支都走一遍，收集到的日志里 canary 必须是 0 次。
 */
class TellomiLinkFetcherLoggingTest {

  @get:Rule
  val fixture = TellomiLinkFetchFixture()

  private val d = TellomiLinkFetchFixture.DOMAIN
  private val canary = "zqxCANARY7f3e"
  private val logger = CapturingLogger()

  @Before
  fun setUp() {
    Log.initialize(logger)
  }

  @After
  fun tearDown() {
    Log.initialize()
  }

  @Test
  fun `no log line contains any part of the URL`() {
    val base = "https://a.$d/$canary"
    val query = "?q=$canary"
    val fragment = "#$canary-fragment"

    fixture.html("$base/ok$query")
    fixture.redirect("$base/hop$query", "https://b.$d/$canary/landing$query")
    fixture.html("https://b.$d/$canary/landing$query")
    fixture.redirect("$base/private$query", "https://10.0.0.1/$canary")
    fixture.redirect("$base/downgrade$query", "http://b.$d/$canary")
    fixture.route("$base/404$query", MockResponse().setResponseCode(404))
    fixture.route("$base/json$query", MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
    fixture.route("$base/big$query", MockResponse().setHeader("Content-Type", "text/html").setBody(Buffer().write(ByteArray(2 * 1024 * 1024 + 1))))
    fixture.route("$base/slow$query", MockResponse().setHeader("Content-Type", "text/html").setBody("x").setHeadersDelay(3, TimeUnit.SECONDS))
    fixture.redirect("https://s.$d/$canary$query", "https://target.$d/$canary")
    for (i in 0 until 7) {
      fixture.redirect("$base/loop$i$query", "$base/loop${i + 1}$query")
    }
    fixture.dns.fail("$canary.$d")
    fixture.html("https://evil.other-fixture.org/$canary")

    val fetcher = fixture.fetcher(TellomiLinkFetcher.Limits.P1.copy(requestTimeoutMs = 300))
    val urls = listOf(
      "$base/ok$query$fragment" to Step.HTML,
      "$base/hop$query$fragment" to Step.HTML,
      "$base/private$query$fragment" to Step.HTML,
      "$base/downgrade$query$fragment" to Step.HTML,
      "$base/404$query$fragment" to Step.HTML,
      "$base/json$query$fragment" to Step.HTML,
      "$base/big$query$fragment" to Step.HTML,
      "$base/slow$query$fragment" to Step.HTML,
      "$base/loop0$query$fragment" to Step.HTML,
      "https://s.$d/$canary$query$fragment" to Step.SHORT_LINK,
      "https://$canary.$d/$canary$fragment" to Step.HTML,
      "https://$canary.$d/$canary$fragment" to Step.HTML,
      "https://evil.other-fixture.org/$canary$fragment" to Step.HTML,
      "http://a.$d/$canary$fragment" to Step.HTML,
      "not a url $canary" to Step.HTML
    )
    for ((url, step) in urls) {
      fetcher.newSession().fetch(url, step)
    }

    assertTrue("expected the fetcher to log its outcomes", logger.lines.size >= urls.size)
    val hits = logger.lines.filter { it.contains(canary, ignoreCase = true) }
    assertEquals("log lines containing the canary:\n" + hits.joinToString("\n"), 0, hits.size)
  }

  private class CapturingLogger : Log.Logger() {
    val lines: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    private fun add(level: String, tag: String, message: String?, t: Throwable?) {
      lines += "$level/$tag: $message ${t?.stackTraceToString() ?: ""}"
    }

    override fun v(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("V", tag, message, t)
    override fun d(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("D", tag, message, t)
    override fun i(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("I", tag, message, t)
    override fun w(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("W", tag, message, t)
    override fun e(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = add("E", tag, message, t)
    override fun flush() = Unit
  }
}
