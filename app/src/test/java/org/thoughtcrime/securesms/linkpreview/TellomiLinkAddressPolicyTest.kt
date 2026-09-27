/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.Inet6Address
import java.net.InetAddress

/**
 * ADR-0063 §6.2 的地址段：IPv4 `0/8`、`10/8`、`100.64/10`、`127/8`、`169.254/16`、`172.16/12`、`192.168/16`；
 * IPv6 `::1`、`fc00::/7`、`fe80::/10`，以及 IPv4-mapped 的这些。**不拦 `198.18.0.0/15`**（fake-ip 代理）。
 * NAT64（`64:ff9b::/96`）按嵌着的 IPv4 判。另外照上游继续拦组播、未指定地址和已废弃的 IPv6 site-local（`fec0::/10`）。
 */
@RunWith(Parameterized::class)
class TellomiLinkAddressPolicyTest(private val address: InetAddress, private val blocked: Boolean) {

  @Test
  fun isBlocked() {
    assertEquals(address.toString(), blocked, TellomiLinkAddressPolicy.isBlocked(address))
  }

  companion object {
    private fun v4(literal: String) = InetAddress.getByName(literal)

    /** 保留成 [Inet6Address] 的 IPv4-mapped 地址（有的解析器这样返回；`InetAddress.getByName` 会直接转成 IPv4）。 */
    private fun mapped(a: Int, b: Int, c: Int, e: Int): InetAddress {
      val bytes = ByteArray(16)
      bytes[10] = 0xff.toByte()
      bytes[11] = 0xff.toByte()
      bytes[12] = a.toByte()
      bytes[13] = b.toByte()
      bytes[14] = c.toByte()
      bytes[15] = e.toByte()
      return Inet6Address.getByAddress(null, bytes, -1)
    }

    @JvmStatic
    @Parameterized.Parameters(name = "{0} blocked={1}")
    fun data(): Collection<Array<Any>> {
      val blocked = listOf(
        "0.0.0.0", "0.1.2.3", "0.255.255.255",
        "10.0.0.1", "10.255.255.255",
        "100.64.0.0", "100.100.100.100", "100.127.255.255",
        "127.0.0.1", "127.255.0.9",
        "169.254.0.1", "169.254.169.254",
        "172.16.0.1", "172.31.255.255",
        "192.168.0.1", "192.168.255.255",
        "224.0.0.1",
        "::", "::1",
        "fc00::1", "fd12:3456:789a::1", "fdff:ffff::",
        "fe80::1", "febf::1",
        "fec0::1",
        "ff02::1",
        "::ffff:10.0.0.1", "::ffff:127.0.0.1",
        "64:ff9b::a00:1", "64:ff9b::7f00:1"
      ).map { arrayOf<Any>(v4(it), true) } + listOf(
        mapped(10, 0, 0, 1),
        mapped(100, 64, 0, 1),
        mapped(127, 0, 0, 1),
        mapped(169, 254, 1, 1),
        mapped(172, 20, 0, 1),
        mapped(192, 168, 1, 1),
        mapped(0, 0, 0, 0)
      ).map { arrayOf<Any>(it, true) }

      val allowed = listOf(
        "1.1.1.1", "8.8.8.8", "114.114.114.114",
        "1.0.0.0",
        "100.63.255.255", "100.128.0.0",
        "126.255.255.255", "128.0.0.1",
        "169.253.255.255", "169.255.0.1",
        "172.15.255.255", "172.32.0.0",
        "192.167.255.255", "192.169.0.0",
        "198.18.0.1", "198.19.255.255",
        "203.0.113.10",
        "2001:4860:4860::8888", "2400:3200::1", "240e::1",
        "fbff::1", "fe7f::1",
        "64:ff9b::808:808"
      ).map { arrayOf<Any>(v4(it), false) } + listOf(
        mapped(198, 18, 0, 1),
        mapped(8, 8, 8, 8)
      ).map { arrayOf<Any>(it, false) }

      return blocked + allowed
    }
  }
}
