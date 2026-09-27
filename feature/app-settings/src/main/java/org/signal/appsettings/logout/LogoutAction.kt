/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.logout

/**
 * Tellomi（ADR-0072）：要 Activity 或导航图才能做的一次性动作，由 Fragment 执行。会写日志，toString 里不放敏感内容。
 */
sealed interface LogoutAction {

  data object NavigateBack : LogoutAction

  /** 打开现有的「屏幕锁定」设置页。 */
  data object NavigateToScreenLock : LogoutAction

  /** 打开现有的「管理存储空间」页。 */
  data object NavigateToManageStorage : LogoutAction

  /** 打开现有的「更换手机号」流程。 */
  data object NavigateToChangePhoneNumber : LogoutAction

  /** 已经退出登录：清掉整个任务栈，回到欢迎页（带「上次登录」）。 */
  data object NavigateToWelcome : LogoutAction

  /** 走现有的「删除所有数据」：`clearApplicationUserData()`。 */
  data object WipeAllData : LogoutAction

  data object ShowDataWipeFailed : LogoutAction
}
