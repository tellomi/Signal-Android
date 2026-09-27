/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

/**
 * Tellomi：链接卡片的本机设置（ADR-0063 §8.1 第 9 行、§九.4；tellomi/tellomi#1422）。
 *
 * 「展开短链接」owner 2026-09-27 定：**默认开、只存本机、不跨设备同步**——不进 storage service（要同步得给账号记录加字段，P1 不做），
 * 也不进本地备份。「生成链接预览」总开关还是上游的 [SettingsValues.isLinkPreviewsEnabled]，照旧同步。
 */
class TellomiLinkValues(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    const val EXPAND_SHORT_LINKS = "tellomi_links.expand_short_links"
  }

  public override fun onFirstEverAppLaunch() = Unit
  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  /** 发送端生成预览时，要不要先请求注册表声明过的短链域名、读它的 `Location`。 */
  var expandShortLinks: Boolean by booleanValue(EXPAND_SHORT_LINKS, true)
}
