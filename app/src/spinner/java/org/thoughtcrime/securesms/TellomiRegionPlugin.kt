/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms

import org.signal.network.util.JsonUtil
import org.signal.spinner.Plugin
import org.signal.spinner.PluginResult
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.region.TellomiRegionId
import org.thoughtcrime.securesms.region.TellomiRegionSwitcher
import org.thoughtcrime.securesms.region.TellomiRegions

/**
 * Tellomi（tellomi/tellomi#1055 第三刀）：只在 spinner 构建里的切区入口，模拟器上验 #1055 判据 2 用。
 * 没注册也能用（Internal 设置要注册后才进得去）。
 *
 * - `GET /tellomi-region`：当前区、重建后的配置用的主机、libsignal `Network` 实例、未认证 websocket 的状态。
 * - `?keepUnauth=true|false`：挂上 / 摘掉一个保活令牌，让未认证 websocket 一直连着，好在 `ss` / 抓包里看它连到哪。
 * - `?testDomain=tellomi.test`：设测试区域名（空 = 关），进程内直接生效，和 Internal 设置里那一项是同一个值。
 * - `?switchTo=global|cn`：切区（[TellomiRegionSwitcher]），再返回同样的状态。
 */
class TellomiRegionPlugin : Plugin {
  companion object {
    const val PATH = "/tellomi-region"
    private const val KEEP_ALIVE_TOKEN = "TellomiRegionPlugin"
  }

  override val name: String = "Tellomi region"
  override val path: String = PATH

  override fun get(parameters: Map<String, List<String>>): PluginResult {
    val actions = mutableListOf<String>()

    parameters["keepUnauth"]?.firstOrNull()?.let { keep ->
      if (keep == "true") {
        AppDependencies.unauthWebSocket.registerKeepAliveToken(KEEP_ALIVE_TOKEN)
      } else {
        AppDependencies.unauthWebSocket.removeKeepAliveToken(KEEP_ALIVE_TOKEN)
      }
      actions += "keepUnauth=$keep"
    }

    parameters["testDomain"]?.firstOrNull()?.let { domain ->
      SignalStore.tellomiRegion.testRegionDomain = domain.trim().ifEmpty { null }
      actions += "testDomain=$domain"
    }

    parameters["switchTo"]?.firstOrNull()?.let { raw ->
      val id = TellomiRegionId.entries.firstOrNull { it.id == raw }
      actions += if (id == null) {
        "switchTo=$raw: unknown region id"
      } else {
        try {
          if (TellomiRegionSwitcher.instance.switchTo(id)) "switchTo=$raw: switched" else "switchTo=$raw: already there"
        } catch (e: TellomiRegionSwitcher.SwitchException) {
          "switchTo=$raw: ${e.reason}"
        }
      }
    }

    val region = TellomiRegions.current()
    val configuration = AppDependencies.signalServiceNetworkAccess.getConfiguration()
    val state = linkedMapOf(
      "actions" to actions,
      "currentRegion" to region.id.id,
      "grpcChatHost" to region.grpcChatHost,
      "storedRegion" to SignalStore.tellomiRegion.currentId,
      "lastSwitchAt" to SignalStore.tellomiRegion.lastSwitchAt,
      "testRegionDomain" to SignalStore.tellomiRegion.testRegionDomain,
      "configChat" to configuration.signalServiceUrls.map { it.url },
      "configCdn3" to configuration.signalCdnUrlMap[3]?.map { it.url },
      "libsignalNetwork" to System.identityHashCode(AppDependencies.libsignalNetwork),
      "unauthWebSocket" to AppDependencies.unauthWebSocket.stateSnapshot.name
    )
    return PluginResult.JsonResult(JsonUtil.toJson(state))
  }
}
