/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.logout

/**
 * Tellomi（ADR-0072，tellomi/tellomi#1414）：「退出登录」替代方案页的状态。
 */
data class LogoutState(
  /**
   * 「屏幕锁定」这一条只在还没开屏幕锁定时出现——已经开了，它就帮不上忙。和 Telegram 一样：两端都是没设密码锁时才列「设置密码锁」。
   */
  val showScreenLock: Boolean = true,
  /** 这个账号有已链接的设备（电脑等）时，才出现「同时让已链接的设备退出」。 */
  val hasLinkedDevices: Boolean = false,
  /** 「同时让已链接的设备退出」，默认不勾。 */
  val unlinkDevices: Boolean = false,
  val dialog: Dialog = Dialog.None
) {

  /** 同一时间最多一个弹框。 */
  sealed interface Dialog {
    data object None : Dialog

    /** 「退出登录？」：退出登录（默认）/ 退出并删除本机数据 / 取消。 */
    data object ConfirmLogout : Dialog

    /** 「删除本机数据？」：删除并退出 / 取消。 */
    data object ConfirmDeleteLocalData : Dialog

    /** 正在退出（注销推送令牌、按需让已链接的设备退出）。 */
    data object InProgress : Dialog

    /** 「退出登录需要联网，请稍后再试。」——没退出，App 仍是已登录。 */
    data object NeedsNetwork : Dialog
  }
}
