/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.account.logout

import android.content.Context
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.logout.TellomiLogout

/**
 * Tellomi（ADR-0072）：「退出登录」替代方案页背后的存储与网络，逻辑在 [TellomiLogout]。
 */
open class LogoutRepository(private val context: Context = AppDependencies.application) {

  open fun isScreenLockEnabled(): Boolean = SignalStore.settings.screenLockEnabled

  /** 本机记得这个账号有已链接的设备（链接设备时置位，设备列表为空时清掉），页面打开时不联网。 */
  open fun hasLinkedDevices(): Boolean = SignalStore.account.isMultiDevice

  open suspend fun logOut(unlinkDevices: Boolean): TellomiLogout.Result = TellomiLogout.logOut(context, unlinkDevices)

  open suspend fun prepareForLocalDataDeletion(unlinkDevices: Boolean) = TellomiLogout.prepareForLocalDataDeletion(unlinkDevices)
}
