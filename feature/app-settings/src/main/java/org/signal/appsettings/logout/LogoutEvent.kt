/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.logout

/**
 * Tellomi（ADR-0072）：「退出登录」替代方案页上用户做的事。会写日志，toString 里不放敏感内容。
 */
sealed interface LogoutEvent {

  /** 页面回到前台，屏幕锁定、已链接设备这些可能变了。 */
  data object ScreenResumed : LogoutEvent

  data object NavigateBackClicked : LogoutEvent

  data object ScreenLockClicked : LogoutEvent

  data object ManageStorageClicked : LogoutEvent

  data object ChangePhoneNumberClicked : LogoutEvent

  data class UnlinkDevicesToggled(val enabled: Boolean) : LogoutEvent

  /** 页面最下面红色的「退出登录」，先弹确认。 */
  data object LogoutClicked : LogoutEvent

  /** 确认框里的「退出登录」：保留本机聊天记录。 */
  data object LogoutConfirmed : LogoutEvent

  /** 确认框里的「退出并删除本机数据」，还要再确认一次。 */
  data object LogoutAndDeleteClicked : LogoutEvent

  /** 第二次确认里的「删除并退出」。 */
  data object DeleteLocalDataConfirmed : LogoutEvent

  /** Fragment 报告清空本机数据失败了。 */
  data object DataWipeFailed : LogoutEvent

  data object DialogDismissed : LogoutEvent
}
