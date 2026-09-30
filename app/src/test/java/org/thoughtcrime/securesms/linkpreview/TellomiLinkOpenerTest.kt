/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0063 §4.9 / §5.5 / §6.1 (tellomi/tellomi#1422): the open plan and the order its steps run in.
 */
class TellomiLinkOpenerTest {

  private val golden = Json.parseToJsonElement(
    requireNotNull(javaClass.classLoader?.getResourceAsStream("links/open-plan-golden.json")).use { String(it.readBytes()) }
  ).jsonObject

  private class FakeLauncher(private val takes: Set<String>) : TellomiLinkOpener.Launcher {
    val tried = mutableListOf<String>()
    override fun inApp(url: String) = attempt("in_app")
    override fun installedAppOnly(url: String) = attempt("installed_app_only")
    override fun scheme(url: String) = attempt("scheme")
    override fun browser(url: String) = attempt("browser")
    override fun copyLink(url: String) = attempt("copy_link")
    private fun attempt(type: String): Boolean {
      tried += type
      return type in takes
    }
  }

  private fun plan(url: String): TellomiLinkOpener.Plan {
    val case = golden["open_plan"]!!.jsonArray.map { it.jsonObject }.first { it["url"]!!.jsonPrimitive.content == url }
    return requireNotNull(TellomiLinkOpener.parse(case["plan"]!!.jsonPrimitive.content))
  }

  @Test
  fun `golden plans always target the link itself, or nothing`() {
    val cases = golden["open_plan"]!!.jsonArray.map { it.jsonObject }
    assert(cases.size >= 5)
    for (case in cases) {
      val url = case["url"]!!.jsonPrimitive.content
      val plan = TellomiLinkOpener.parse(case["plan"]!!.jsonPrimitive.content)
      assertNotNull(url, plan)
      for (step in plan!!.steps) {
        assertEquals(url, url, step.url)
      }
    }
    assertEquals(emptyList<TellomiLinkOpener.Step>(), plan("intent://scan/#Intent;scheme=zxing;end").steps)
    assertEquals("bilibili.com", plan("https://www.bi1ibili.com/video/BV1YDhJ6ZEL6").lookalike)
    assertNull(plan("https://www.bilibili.com/video/BV1YDhJ6ZEL6/?spm_id_from=333.1007").lookalike)
  }

  @Test
  fun `falls from an installed app to the browser`() {
    val launcher = FakeLauncher(takes = setOf("browser"))
    assertEquals("browser", TellomiLinkOpener.run(plan("https://www.bilibili.com/video/BV1YDhJ6ZEL6/?spm_id_from=333.1007").steps, launcher))
    assertEquals(listOf("installed_app_only", "browser"), launcher.tried)
  }

  @Test
  fun `stops at the installed app when it takes the link`() {
    val launcher = FakeLauncher(takes = setOf("installed_app_only", "browser"))
    assertEquals("installed_app_only", TellomiLinkOpener.run(plan("https://www.bilibili.com/video/BV1YDhJ6ZEL6/?spm_id_from=333.1007").steps, launcher))
    assertEquals(listOf("installed_app_only"), launcher.tried)
  }

  @Test
  fun `payment links only go to the browser`() {
    val launcher = FakeLauncher(takes = setOf("installed_app_only", "scheme", "browser"))
    assertEquals("browser", TellomiLinkOpener.run(plan("https://render.alipay.com/p/f/fd-j5rqp49m/index.html").steps, launcher))
    assertEquals(listOf("browser"), launcher.tried)
  }

  @Test
  fun `tell-cc opens inside Tellomi`() {
    val launcher = FakeLauncher(takes = setOf("in_app", "browser"))
    assertEquals("in_app", TellomiLinkOpener.run(plan("https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").steps, launcher))
    assertEquals(listOf("in_app"), launcher.tried)
  }

  @Test
  fun `copies the link when Tellomi cannot take its own link`() {
    val launcher = FakeLauncher(takes = setOf("browser", "copy_link"))
    assertEquals("copy_link", TellomiLinkOpener.run(plan("https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").steps, launcher))
    assertEquals(listOf("in_app", "copy_link"), launcher.tried)
  }

  @Test
  fun `an empty plan opens nothing`() {
    val launcher = FakeLauncher(takes = setOf("in_app", "installed_app_only", "scheme", "browser", "copy_link"))
    assertNull(TellomiLinkOpener.run(emptyList(), launcher))
    assertEquals(emptyList<String>(), launcher.tried)
  }

  @Test
  fun `only mailto and tel are handed to the system without a plan`() {
    for (url in listOf("mailto:a@b.co", "tel:+8613800000000", "MAILTO:a@b.co", "Tel:+8613800000000", "  mailto:a@b.co\n")) {
      assertTrue(url, TellomiLinkOpener.isHandedToSystem(url))
    }
    for (url in listOf(
      "",
      "mailto",
      ":a@b.co",
      "a@b.co",
      "+8613800000000",
      "https://a.example/",
      "intent://scan/#Intent;scheme=zxing;end",
      "javascript:alert('mailto:a@b.co')",
      "intent://x#Intent;S.browser_fallback_url=tel:+8613800000000;end",
      "data:text/html,mailto:a@b.co",
      "file:///tel:",
      "xmailto:a@b.co",
      "mailto.evil:a@b.co",
      "mailto+evil:a@b.co",
      "tell:+8613800000000",
      "\u0001mailto:a@b.co",
      "mai lto:a@b.co",
      "//mailto:a@b.co"
    )) {
      assertFalse(url, TellomiLinkOpener.isHandedToSystem(url))
    }
  }

  @Test
  fun `a plan with an unknown step type is not guessed at`() {
    val launcher = FakeLauncher(takes = setOf("browser"))
    val steps = listOf(TellomiLinkOpener.Step("teleport", "https://a.example/"), TellomiLinkOpener.Step("browser", "https://a.example/"))
    assertEquals("browser", TellomiLinkOpener.run(steps, launcher))
    assertEquals(listOf("browser"), launcher.tried)
  }
}
