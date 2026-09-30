/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import androidx.fragment.app.Fragment
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.groups.v2.GroupInviteLinkUrl
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mediaoverview.MediaOverviewContextMenu
import org.thoughtcrime.securesms.service.webrtc.links.ReadCallLinkResult
import org.thoughtcrime.securesms.testutil.SignalStoreRule
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADR-0063 §6.5 / §8.1 row 9 (audit §3 item 14): the places that log an exception object next to a link. An exception's message can
 * hold the address of what failed, so where it cannot be shown not to, only the kind of failure is logged. Each case puts a canary
 * in the address and in the exception's message, then looks for it in the log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLogExceptionsTest {

  @get:Rule
  val signalStore = SignalStoreRule()

  @get:Rule
  val fixture = TellomiLinkFetchFixture()

  private val canary = "cafe0123babe"
  private val logger = CapturingLogger()

  @Before
  fun setUp() {
    Log.initialize(logger)
    SignalStore.settings.isLinkPreviewsEnabled = true
    TellomiLinkRegistry.setForTesting(null)
  }

  @After
  fun tearDown() {
    unmockkStatic(GroupInviteLinkUrl::class)
    Log.initialize()
  }

  private fun assertNoCanary() {
    val hits = logger.lines.filter { it.contains(canary, ignoreCase = true) }
    assertEquals("log lines containing the canary:\n" + hits.joinToString("\n"), 0, hits.size)
  }

  @Test
  fun `a call link that could not be read is logged as a status code, which is all it holds`() {
    val fields = ReadCallLinkResult.Failure::class.java.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

    assertEquals("the failure has one field, and it is a number", listOf("status" to Short::class.javaPrimitiveType), fields.map { it.name to it.type })
    assertEquals("Failure(status=404)", ReadCallLinkResult.Failure(404).toString())
  }

  @Test
  fun `a link preview the media overview cannot read is logged as the kind of failure, not the JSON it was in`() {
    val menu = MediaOverviewContextMenu(mockk<Fragment>(relaxed = true), mockk(relaxed = true))
    // Cut off: org.json's message for this is "Unterminated object at character N of [...]", with the text.
    val record = mockk<MediaTable.MediaRecord>(relaxed = true) {
      every { linkPreviewJson } returns """[{"url":"https://a.example/$canary#$canary","title":"""
    }
    val share = MediaOverviewContextMenu::class.java.getDeclaredMethod("getShareLinkActionItem", MediaTable.MediaRecord::class.java).apply { isAccessible = true }

    assertNull(share.invoke(menu, record))

    assertTrue("it says what failed: ${logger.lines}", logger.lines.any { it.contains("Failed to deserialize link preview") })
    assertNoCanary()
  }

  @Test
  fun `a group link that cannot be read is logged as the kind of failure, not the exception's message`() {
    val url = "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAA${canary}AAAAAAAA"
    mockkStatic(GroupInviteLinkUrl::class)
    every { GroupInviteLinkUrl.fromUri(any()) } throws GroupInviteLinkUrl.InvalidGroupLinkException("Bad group link $url")

    val latch = CountDownLatch(1)
    var error: LinkPreviewRepository.Error? = null
    LinkPreviewRepository(fixture.fetcher()).getLinkPreview(
      ApplicationProvider.getApplicationContext(),
      url,
      object : LinkPreviewRepository.Callback {
        override fun onSuccess(linkPreview: LinkPreview) = latch.countDown()

        override fun onError(e: LinkPreviewRepository.Error) {
          error = e
          latch.countDown()
        }
      }
    )

    assertTrue(latch.await(10, TimeUnit.SECONDS))
    assertEquals(LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE, error)
    assertTrue("it says what failed: ${logger.lines}", logger.lines.any { it.contains("Bad group link") })
    assertNoCanary()
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
