/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.EmptyLogger

/**
 * See [CallLinks]
 */
class CallLinksTest {
  companion object {
    @JvmStatic
    @BeforeClass
    fun setUpClass() {
      Log.initialize(EmptyLogger())
    }
  }

  @Test
  fun `parseUrl returns null for malformed percent escape instead of throwing`() {
    assertNull(CallLinks.parseUrl("https://signal.link/call/#key=abcdef&n=%ZZ"))
  }

  @Test
  fun `parseUrl returns null for malformed percent escape in key instead of throwing`() {
    assertNull(CallLinks.parseUrl("https://signal.link/call/#key=%ZZ"))
  }

  /** Tellomi（tellomi/tellomi#1113）：tell.cc 两种写法（Desktop 发带斜杠的）与旧形状都认；别的 tell.cc 路径不算通话链接。 */
  @Test
  fun `tell cc call links with and without slash are call links`() {
    assertTrue(CallLinks.isCallLink("https://tell.cc/call#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz"))
    assertTrue(CallLinks.isCallLink("https://tell.cc/call/#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz"))
    assertTrue(CallLinks.isCallLink("tellomi://tell.cc/call/#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz"))
    assertTrue(CallLinks.isCallLink("https://signal.link/call/#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz"))
    assertFalse(CallLinks.isCallLink("https://tell.cc/g#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz"))
  }

  /** Tellomi（tellomi/tellomi#1113）：生成的是 tell.cc 形状（不带斜杠，LINKS_AND_SCHEMES.md 那张表），且自己认得出来。 */
  @Test
  fun `generated call links use the tell cc shape`() {
    val url = CallLinks.urlForFormattedKey("bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz")

    assertEquals("https://tell.cc/call#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz", url)
    assertTrue(CallLinks.isCallLink(url))
  }
}
