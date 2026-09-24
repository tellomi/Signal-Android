/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.Test
import org.signal.network.config.SignalCdnUrl
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.config.TrustStore
import java.io.InputStream
import java.util.Optional

/**
 * tellomi/tellomi#1055：按区域组装 [SignalServiceConfiguration]，每一份的 cdn3 恰好一个（RegionProfile 契约第五节第 3 条）。
 */
class TellomiServiceConfigurationsTest {

  private val trustStore = object : TrustStore {
    override fun getKeyStoreInputStream(): InputStream = InputStream.nullInputStream()
    override fun getKeyStorePassword(): String = ""
  }

  private fun build(profile: TellomiRegionProfile, censored: Boolean = false): SignalServiceConfiguration {
    return TellomiServiceConfigurations.build(
      profile = profile,
      trustStore = trustStore,
      cdsiUrl = "https://cdsi.example",
      svr2Url = "https://svr2.example",
      networkInterceptors = emptyList(),
      dns = Optional.empty(),
      signalProxy = Optional.empty(),
      systemHttpProxy = Optional.empty(),
      zkGroupServerPublicParams = ByteArray(0),
      genericServerPublicParams = ByteArray(0),
      backupServerPublicParams = ByteArray(0),
      censored = censored
    )
  }

  @Test
  fun `global configuration uses exactly the region endpoints`() {
    val configuration = build(TellomiRegions.GLOBAL)
    val global = TellomiRegions.GLOBAL

    assertThat(configuration.signalServiceUrls.map { it.url }).isEqualTo(listOf(global.chat))
    assertThat(configuration.signalCdnUrlMap.keys).isEqualTo(setOf(0, 2, 3))
    assertThat(configuration.signalCdnUrlMap[0]!!.map { it.url }).isEqualTo(listOf(global.cdn0))
    assertThat(configuration.signalCdnUrlMap[2]!!.map { it.url }).isEqualTo(listOf(global.cdn2))
    assertThat(configuration.signalCdnUrlMap[3]!!.map { it.url }).isEqualTo(listOf(global.cdn3))
    assertThat(configuration.signalStorageUrls.map { it.url }).isEqualTo(listOf(global.storage))
    assertThat(configuration.signalCdsiUrls.map { it.url }).isEqualTo(listOf("https://cdsi.example"))
    assertThat(configuration.signalSvr2Urls.map { it.url }).isEqualTo(listOf("https://svr2.example"))
    assertThat(configuration.censored).isFalse()
    assertThat(build(TellomiRegions.GLOBAL, censored = true).censored).isTrue()
  }

  @Test
  fun `every packaged region yields exactly one cdn3`() {
    for (profile in TellomiRegions.ALL) {
      for (censored in listOf(false, true)) {
        val configuration = build(profile, censored)
        assertThat(configuration.signalCdnUrlMap[3]!!.size).isEqualTo(1)
        // 规避配置的保护不在这里（copy 共用同一个 map，在这里再断言一次是空转）：
        // 由 SignalServiceNetworkAccess 的 init 在构造时断言，用例见 SignalServiceNetworkAccessCdn3Test。
      }
    }
  }

  @Test
  fun `a second cdn3 is rejected`() {
    val configuration = build(TellomiRegions.GLOBAL)
    val twoCdn3 = configuration.copy(
      signalCdnUrlMap = configuration.signalCdnUrlMap + (3 to arrayOf(SignalCdnUrl("https://cdn3.tellomi.app", trustStore), SignalCdnUrl("https://cdn3b.tellomi.app", trustStore)))
    )

    assertFailure { TellomiServiceConfigurations.requireSingleCdn3(twoCdn3) }.isInstanceOf(IllegalStateException::class)
    assertFailure { TellomiServiceConfigurations.requireSingleCdn3(configuration.copy(signalCdnUrlMap = configuration.signalCdnUrlMap - 3)) }.isInstanceOf(IllegalStateException::class)
  }
}
