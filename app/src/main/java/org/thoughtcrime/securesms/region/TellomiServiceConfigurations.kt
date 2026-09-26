/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import okhttp3.Dns
import okhttp3.Interceptor
import org.signal.network.config.HttpProxy
import org.signal.network.config.SignalCdnUrl
import org.signal.network.config.SignalCdsiUrl
import org.signal.network.config.SignalProxy
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.config.SignalServiceUrl
import org.signal.network.config.SignalStorageUrl
import org.signal.network.config.SignalSvr2Url
import org.signal.network.config.TrustStore
import java.util.Optional

/**
 * Tellomi：按区域组装 [SignalServiceConfiguration] 的纯函数（RegionProfile 契约第五节第 3 条；tellomi/tellomi#1055）。
 *
 * 上游 v8.26.4 的域名前置配置每个 CDN 放 3 个 URL；fork 自 #1025 起已经删掉，但那段注释写明以后要按上游的形状重建。
 * 所以组装抽到这里，每一份都过 [requireSingleCdn3]：一次 resumable 上传的落点必须始终是同一个，
 * 多一个「备用 CDN」就会在上传中途换落点，整份附件从 0 重来（ADR-0065 §6.2）。
 */
object TellomiServiceConfigurations {

  fun build(
    profile: TellomiRegionProfile,
    trustStore: TrustStore,
    cdsiUrl: String,
    svr2Url: String,
    networkInterceptors: List<Interceptor>,
    dns: Optional<Dns>,
    signalProxy: Optional<SignalProxy>,
    systemHttpProxy: Optional<HttpProxy>,
    zkGroupServerPublicParams: ByteArray,
    genericServerPublicParams: ByteArray,
    backupServerPublicParams: ByteArray,
    censored: Boolean
  ): SignalServiceConfiguration {
    val configuration = SignalServiceConfiguration(
      signalServiceUrls = arrayOf(SignalServiceUrl(profile.chat, trustStore)),
      signalCdnUrlMap = mapOf(
        0 to arrayOf(SignalCdnUrl(profile.cdn0, trustStore)),
        2 to arrayOf(SignalCdnUrl(profile.cdn2, trustStore)),
        3 to arrayOf(SignalCdnUrl(profile.cdn3, trustStore))
      ),
      signalStorageUrls = arrayOf(SignalStorageUrl(profile.storage, trustStore)),
      // cdsi / svr2 不进区域（没有自建，见 ENCLAVES.md），照常从构建常量取
      signalCdsiUrls = arrayOf(SignalCdsiUrl(cdsiUrl, trustStore)),
      signalSvr2Urls = arrayOf(SignalSvr2Url(svr2Url, trustStore)),
      networkInterceptors = networkInterceptors,
      dns = dns,
      signalProxy = signalProxy,
      systemHttpProxy = systemHttpProxy,
      zkGroupServerPublicParams = zkGroupServerPublicParams,
      genericServerPublicParams = genericServerPublicParams,
      backupServerPublicParams = backupServerPublicParams,
      censored = censored
    )
    requireSingleCdn3(configuration)
    return configuration
  }

  /** 每个区的 cdn3 恰好一个。不满足就是构建缺陷，直接抛。 */
  fun requireSingleCdn3(configuration: SignalServiceConfiguration) {
    val cdn3 = configuration.signalCdnUrlMap[3]
    check(cdn3 != null && cdn3.size == 1) {
      "cdn3 must have exactly one URL per region (ADR-0065 §6.2), got ${cdn3?.size ?: 0}"
    }
  }
}
