/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

/**
 * View state for [UpdateRequiredScreen]（tellomi/tellomi#1138，需求说明 app-update-and-version-policy 第 3.4 节「必须更新」）。
 */
data class UpdateRequiredState(
  val reason: Reason = Reason.SERVER_REJECTED,
  /** 官网版在 App 内下载安装；其它渠道（Play 等）按钮直接去商店。 */
  val managesAppUpdates: Boolean = true,
  val download: Download = Download.Idle,
  /** 清单里比当前安装的新的版本号；不知道时为 null。 */
  val newVersionName: String? = null,
  /** 安装包大小（字节）；下载开始前不知道时为 null。 */
  val totalBytes: Long? = null,
  val isOffline: Boolean = false
) {

  enum class Reason {
    /** 服务端拒绝了这个版本（499），或远程配置宣布它到期。 */
    SERVER_REJECTED,

    /** 构建自身过了有效期（Signal 自带的构建过期兜底）。 */
    BUILD_EXPIRED
  }

  sealed interface Download {
    /** 还没开始：主按钮「立即更新」。 */
    data object Idle : Download

    /** 没有「安装未知应用」权限：先说明，再去系统设置。 */
    data object NeedsInstallPermission : Download

    /** 检查清单 / 排队中 / 下载中；[percent] 为 null 表示还不知道进度。 */
    data class InProgress(val percent: Int?) : Download

    /** 下载完成并已调起系统安装器；用户在系统安装界面取消时可以再点一次。 */
    data object ReadyToInstall : Download

    /** 清单里没有比当前安装更新的版本。 */
    data object NoNewerVersion : Download

    /** 检查或下载失败：主按钮「重试」，下方「去官网下载」。 */
    data object Failed : Download
  }
}

sealed interface UpdateRequiredScreenEvent {
  data object ScreenResumed : UpdateRequiredScreenEvent
  data object PrimaryClicked : UpdateRequiredScreenEvent
  data object DownloadFromWebsiteClicked : UpdateRequiredScreenEvent
}

/** 需要 Activity 才能做的一次性动作。 */
sealed interface UpdateRequiredScreenAction {
  data object OpenInstallPermissionSettings : UpdateRequiredScreenAction

  /** 官网下载页（官网版）或应用商店（其它渠道）。 */
  data object OpenDownloadPage : UpdateRequiredScreenAction
}
