/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.any
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.net.StaticDns
import java.net.UnknownHostException

/**
 * tellomi/tellomi#1055 第三刀：静态 IP 收进区域表（契约第三节 staticIps，Android 独有，只属于 global 档）。
 */
class TellomiStaticIpsTest {

  private val global = TellomiRegions.GLOBAL
  private val cn = TellomiRegions.CN

  @Test
  fun `global static ips are the build constants for its own hosts`() {
    assertThat(global.staticIps).isEqualTo(
      mapOf(
        "chat.tellomi.app" to BuildConfig.SIGNAL_SERVICE_IPS.toSet(),
        "storage.tellomi.app" to BuildConfig.SIGNAL_STORAGE_IPS.toSet(),
        "cdn.tellomi.app" to BuildConfig.SIGNAL_CDN_IPS.toSet(),
        "cdn2.tellomi.app" to BuildConfig.SIGNAL_CDN2_IPS.toSet(),
        "cdn3.tellomi.app" to BuildConfig.SIGNAL_CDN3_IPS.toSet(),
        "contentproxy.tellomi.app" to BuildConfig.SIGNAL_CONTENT_PROXY_IPS.toSet()
      )
    )
    // cdn3 故意是空的（#1077：Cloudflare 边缘 IP 会变）
    assertThat(global.staticIps.getValue("cdn3.tellomi.app")).isEmpty()
  }

  @Test
  fun `the dns table only knows global hosts`() {
    assertThat(cn.staticIps).isEmpty()
    assertThat(TellomiRegions.staticIpTable()).isEqualTo(global.staticIps)
    // 上游的 cdsi / svr2 两行（指向 Signal 的 IP，也没人用）不在了
    assertThat(TellomiRegions.staticIpTable().keys.none { it.endsWith("signal.org") }).isTrue()
    // CN 的主机在表里查不到，和没有静态 IP 的主机一样，所以切区不用重建这张表
    assertFailure { StaticDns(TellomiRegions.staticIpTable()).lookup("chat.tellomi.cn") }.isInstanceOf<UnknownHostException>()
  }

  @Test
  fun `the invariant check catches misplaced static ips`() {
    assertThat(TellomiRegions.problems(listOf(global, cn.copy(staticIps = mapOf("chat.tellomi.cn" to setOf("192.0.2.1"))))))
      .any { it.contains("no static ip fallback") }
    assertThat(TellomiRegions.problems(listOf(global.copy(staticIps = global.staticIps + ("svr2.staging.signal.org" to setOf("192.0.2.1"))), cn)))
      .any { it.contains("svr2.staging.signal.org") }
  }

  @Test
  fun `the test region has no static ips either`() {
    assertThat(TellomiRegions.testRegionProfiles("tellomi.test")[1].staticIps).isEmpty()
  }
}
