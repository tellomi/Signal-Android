/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import java.net.InetAddress

/**
 * 链接预览不许连的地址段（ADR-0063 §6.2，tellomi/tellomi#1422）。
 *
 * IPv4：`0/8`、`10/8`、`100.64/10`（CGNAT，Tailscale 也用）、`127/8`、`169.254/16`、`172.16/12`、`192.168/16`；
 * IPv6：`::1`、`fc00::/7`、`fe80::/10`，以及 IPv4-mapped（`::ffff:a.b.c.d`）和 NAT64（`64:ff9b::/96`）里嵌着的上面这些 IPv4。
 * 照上游 `LinkUtil` 继续拦的：未指定地址、组播、已废弃的 IPv6 site-local（`fec0::/10`）。
 *
 * **不拦 `198.18.0.0/15`**：大陆常用的 fake-ip 代理（Clash、Surge、sing-box）把所有域名解析到这一段，拦了这些用户就全是品牌壳。
 */
object TellomiLinkAddressPolicy {

  @JvmStatic
  fun isBlocked(address: InetAddress): Boolean {
    val bytes = address.address
    return when (bytes.size) {
      4 -> isBlockedV4(bytes, 0)
      16 -> isBlockedV6(bytes)
      else -> true
    }
  }

  private fun isBlockedV4(bytes: ByteArray, offset: Int): Boolean {
    val a = bytes[offset].toInt() and 0xff
    val b = bytes[offset + 1].toInt() and 0xff
    return a == 0 ||
      a == 10 ||
      (a == 100 && b in 64..127) ||
      a == 127 ||
      (a == 169 && b == 254) ||
      (a == 172 && b in 16..31) ||
      (a == 192 && b == 168) ||
      a in 224..239
  }

  private fun isBlockedV6(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff

    if (startsWithZeros(bytes, 10) && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) {
      return isBlockedV4(bytes, 12)
    }
    if (first == 0x00 && second == 0x64 && bytes[2] == 0xff.toByte() && bytes[3] == 0x9b.toByte() && startsWithZeros(bytes.copyOfRange(4, 12), 8)) {
      return isBlockedV4(bytes, 12)
    }
    if (startsWithZeros(bytes, 15) && (bytes[15].toInt() == 0 || bytes[15].toInt() == 1)) {
      return true
    }

    return (first and 0xfe) == 0xfc ||
      (first == 0xfe && (second and 0xc0) == 0x80) ||
      (first == 0xfe && (second and 0xc0) == 0xc0) ||
      first == 0xff
  }

  private fun startsWithZeros(bytes: ByteArray, count: Int): Boolean {
    for (i in 0 until count) {
      if (bytes[i].toInt() != 0) {
        return false
      }
    }
    return true
  }
}
