/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tellomi（#1078）：服务端下发的 `global.gif.proxyUrl` 怎么变成代理地址。
 *
 * 测的是 [ContentProxySelector.parseProxyUrl] 这个纯函数：读 `RemoteConfig` 的 delegate 需要 app context，
 * 纯 JVM 单测里直接 NPE（同 RemoteConfigGifTest 的做法）。判据钉的是**决定**：什么样的下发值会被采用，
 * 什么样的会被拒绝、回落到编译期常量。
 */
class ContentProxySelectorTest {

  @Test
  fun `the value the server delivers today is used as is`() {
    // deploy/hk/gen-config.py 的缺省值，Desktop 读的也是它。
    val endpoint = ContentProxySelector.parseProxyUrl("https://contentproxy.tellomi.app:443")

    assertNotNull(endpoint)
    assertEquals("contentproxy.tellomi.app", endpoint!!.host)
    assertEquals(443, endpoint.port)
  }

  @Test
  fun `a CN host without an explicit port gets 443`() {
    // 备案之后换域名就是改这一行下发值，不用发版。
    val endpoint = ContentProxySelector.parseProxyUrl("https://contentproxy.tellomi.cn")

    assertEquals("contentproxy.tellomi.cn", endpoint!!.host)
    assertEquals(443, endpoint.port)
  }

  @Test
  fun `a non-default port and a trailing slash are fine`() {
    val endpoint = ContentProxySelector.parseProxyUrl(" https://contentproxy.tellomi.app:8443/ ")

    assertEquals("contentproxy.tellomi.app", endpoint!!.host)
    assertEquals(8443, endpoint.port)
  }

  @Test
  fun `plaintext http is refused so one config line cannot downgrade every client`() {
    // 明文 CONNECT 从大陆出发会被 reset；宁可回落到编译期常量（外层仍是 TLS）。
    assertNull(ContentProxySelector.parseProxyUrl("http://contentproxy.tellomi.app:443"))
  }

  @Test
  fun `missing or malformed values fall back`() {
    assertNull(ContentProxySelector.parseProxyUrl(null))
    assertNull(ContentProxySelector.parseProxyUrl(""))
    assertNull(ContentProxySelector.parseProxyUrl("   "))
    assertNull(ContentProxySelector.parseProxyUrl("contentproxy.tellomi.app:443"))
    assertNull(ContentProxySelector.parseProxyUrl("https://"))
  }
}
