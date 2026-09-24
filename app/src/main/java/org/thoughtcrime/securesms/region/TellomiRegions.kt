/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import org.thoughtcrime.securesms.BuildConfig

/**
 * Tellomi：区域 id（RegionProfile 契约 v2 第二节，超级仓库 `docs/signal/REGION_PROFILE.md`；tellomi/tellomi#1055）。
 * 和 Desktop、ADR-0062 / ADR-0064、policy 引擎的 `Region::{Global, Cn}` 是同一套取值。
 */
enum class TellomiRegionId(val id: String) {
  GLOBAL("global"),
  CN("cn")
}

/**
 * 一个区域 = 一组端点 + 一个 `enabled` 位（契约第一、三节）。
 * 客户端连的每一个服务端地址都属于且只属于一个区域；各字段对应契约第三节表里的一行。
 * URL 字段带 scheme；名字以 `Host` 结尾的只写主机名。
 *
 * 不进区域的：`svr2` / `cdsi`（没有自建，见 `ENCLAVES.md`）、zk 参数、UD 信任根、CA——两个区连的是同一套服务端。
 */
data class TellomiRegionProfile(
  val id: TellomiRegionId,
  val enabled: Boolean,
  /** REST + WebSocket；captcha 页、`/callingService`、`/debuglogs` 也挂在这台主机上。 */
  val chat: String,
  /** libsignal `Network` 的 custom server，只写主机名。 */
  val grpcChatHost: String,
  val storage: String,
  /** 头像 / 群头像 / 贴纸。 */
  val cdn0: String,
  /** 遗留；客户端配置校验要求它存在。 */
  val cdn2: String,
  /** 附件；每个区恰好一个（契约第五节第 3 条，见 [TellomiServiceConfigurations.requireSingleCdn3]）。 */
  val cdn3: String,
  /** 动态资源、自动更新的主机（`UPDATES2_HOST`）。 */
  val updatesHost: String,
  val badgeStaticRoot: String,
  /** 只有 website 档有（大陆的分发渠道），其余档是 null。 */
  val apkUpdateManifestUrl: String?,
  /** GIF 内容代理。三端都先和代理建 TLS 再 CONNECT。 */
  val contentProxyHost: String,
  val contentProxyPort: Int,
  val captchaRegistration: String,
  val captchaChallenge: String,
  /** 群通话会合点。两个区的入口可以不同，会合点只能有一个（契约第五节第 6 条）。 */
  val sfu: String,
  /** 只做 DNS 解析，不连接（#1101）。 */
  val uptimeHost: String,
  /** 调试日志上传（#931）。 */
  val debugLog: String
) {
  /** 这个区所有的端点（URL 或主机名），校验和测试用。 */
  fun endpoints(): List<String> {
    return listOfNotNull(
      chat,
      grpcChatHost,
      storage,
      cdn0,
      cdn2,
      cdn3,
      updatesHost,
      badgeStaticRoot,
      apkUpdateManifestUrl,
      contentProxyHost,
      captchaRegistration,
      captchaChallenge,
      sfu,
      uptimeHost,
      debugLog
    )
  }
}

/**
 * 编进包里的区域表。
 *
 * 这一刀只把表立起来、把不变量钉成测试：global 档就是今天的常量（逐字节一致），CN 档按契约第四节生成、`enabled = false`。
 * 选区、切区（`AppDependencies.resetNetwork()`）和各调用点改从当前区取，在 #1055 的后面几刀。
 */
object TellomiRegions {
  private const val GLOBAL_DOMAIN = ".tellomi.app"
  private const val CN_DOMAIN = ".tellomi.cn"

  /** 原来写死在 `SubmitDebugLogRepository` 里，收进这里（契约第三节「写死」那几处之一）。 */
  private const val DEBUG_LOG_URL = "https://chat.tellomi.app/debuglogs"

  /** global 档 = 今天的常量（契约第五节第 1 条）。 */
  @JvmField
  val GLOBAL = TellomiRegionProfile(
    id = TellomiRegionId.GLOBAL,
    enabled = true,
    chat = BuildConfig.SIGNAL_URL,
    grpcChatHost = BuildConfig.LIBSIGNAL_CUSTOM_SERVER_HOST,
    storage = BuildConfig.STORAGE_URL,
    cdn0 = BuildConfig.SIGNAL_CDN_URL,
    cdn2 = BuildConfig.SIGNAL_CDN2_URL,
    cdn3 = BuildConfig.SIGNAL_CDN3_URL,
    updatesHost = BuildConfig.UPDATES2_HOST,
    badgeStaticRoot = BuildConfig.BADGE_STATIC_ROOT,
    apkUpdateManifestUrl = BuildConfig.APK_UPDATE_MANIFEST_URL,
    contentProxyHost = BuildConfig.CONTENT_PROXY_HOST,
    contentProxyPort = BuildConfig.CONTENT_PROXY_PORT,
    captchaRegistration = BuildConfig.SIGNAL_CAPTCHA_URL,
    captchaChallenge = BuildConfig.RECAPTCHA_PROOF_URL,
    sfu = BuildConfig.SIGNAL_SFU_URL,
    uptimeHost = BuildConfig.SIGNAL_SERVICE_STATUS_URL,
    debugLog = DEBUG_LOG_URL
  )

  /** CN 档：`enabled = false`，直到备案完成（契约第五节第 2 条）。 */
  @JvmField
  val CN = cnOf(GLOBAL)

  @JvmField
  val ALL: List<TellomiRegionProfile> = listOf(GLOBAL, CN)

  /**
   * 契约第四节：同名标签挂到 `tellomi.cn`。scheme、端口、路径都不变，只把主机名里的 `.tellomi.app` 换成 `.tellomi.cn`。
   * 这样 CN 档的每一个主机都在 `tellomi.cn` 下（App 备案要填运行时连接的全部域名，漏一条就是漏报）。
   */
  fun cnOf(global: TellomiRegionProfile): TellomiRegionProfile {
    return global.copy(
      id = TellomiRegionId.CN,
      enabled = false,
      chat = toCnHost(global.chat),
      grpcChatHost = toCnHost(global.grpcChatHost),
      storage = toCnHost(global.storage),
      cdn0 = toCnHost(global.cdn0),
      cdn2 = toCnHost(global.cdn2),
      cdn3 = toCnHost(global.cdn3),
      updatesHost = toCnHost(global.updatesHost),
      badgeStaticRoot = toCnHost(global.badgeStaticRoot),
      apkUpdateManifestUrl = global.apkUpdateManifestUrl?.let { toCnHost(it) },
      contentProxyHost = toCnHost(global.contentProxyHost),
      captchaRegistration = toCnHost(global.captchaRegistration),
      captchaChallenge = toCnHost(global.captchaChallenge),
      sfu = toCnHost(global.sfu),
      uptimeHost = toCnHost(global.uptimeHost),
      debugLog = toCnHost(global.debugLog)
    )
  }

  /** URL 或主机名 → 主机名（去掉 scheme、端口、路径）。 */
  fun hostOf(urlOrHost: String): String {
    val start = urlOrHost.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val end = urlOrHost.indexOfAny(charArrayOf('/', ':', '?', '#'), startIndex = start).let { if (it < 0) urlOrHost.length else it }
    return urlOrHost.substring(start, end)
  }

  /** 只换主机部分；主机不在 `tellomi.app` 下的原样返回，由 [problems] 挑出来。 */
  fun toCnHost(urlOrHost: String): String {
    val host = hostOf(urlOrHost)
    if (!host.endsWith(GLOBAL_DOMAIN)) {
      return urlOrHost
    }
    val start = urlOrHost.indexOf(host)
    return urlOrHost.substring(0, start) + host.removeSuffix(GLOBAL_DOMAIN) + CN_DOMAIN + urlOrHost.substring(start + host.length)
  }

  /**
   * 包内区域表的不变量（契约第四节，第五节第 2、4 条）。空列表 = 合法。
   * 表是编进包里的，不合法是构建缺陷，由单测把关（契约第五节第 8 条：包内配置坏了应该直接失败）。
   */
  fun problems(profiles: List<TellomiRegionProfile>): List<String> {
    val problems = mutableListOf<String>()

    val ids = profiles.map { it.id }
    if (ids.size != ids.toSet().size) {
      problems += "duplicate region ids: $ids"
    }

    val global = profiles.firstOrNull { it.id == TellomiRegionId.GLOBAL }
    if (global == null) {
      problems += "missing the global region"
    } else if (!global.enabled) {
      problems += "the global region must be enabled"
    }

    profiles.firstOrNull { it.id == TellomiRegionId.CN }?.let { cn ->
      if (cn.enabled) {
        problems += "the cn region must stay disabled until the ICP filing is done"
      }
      cn.endpoints().filterNot { hostOf(it).endsWith(CN_DOMAIN) }.forEach {
        problems += "cn endpoint outside tellomi.cn: $it"
      }
    }

    return problems
  }
}
