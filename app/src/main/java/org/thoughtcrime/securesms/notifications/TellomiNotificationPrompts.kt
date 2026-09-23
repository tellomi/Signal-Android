/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.notifications

/**
 * Tellomi（tellomi/tellomi#1218 F-01、#1112）：通知权限什么时候问、问过之后怎么提示——两条判定放在一处，纯函数便于单测。
 *
 * 顺序是：注册流程里不问 → 第一次进首屏出说明页（只一个「继续」，点了才出系统框）→ 拒了的，会话列表顶部常驻
 * 「通知已关闭 · 去设置」。两条判定共用「说明页出过没有」：Android 13+ 上说明页还没出过时，横幅不能先挂出来。
 */
object TellomiNotificationPrompts {

  /** 需要运行时授权才能发通知的最低系统版本（Android 13，`POST_NOTIFICATIONS`）。 */
  const val RUNTIME_PERMISSION_SDK = 33

  /**
   * 首屏说明页该不该弹：Android 13+、还没授通知、这台设备上还没弹过。
   * 更早的系统通知默认开着，没有可问的。
   */
  @JvmStatic
  fun shouldShowPrimer(sdkInt: Int, isPermissionGranted: Boolean, hasSeenPrimer: Boolean): Boolean {
    return sdkInt >= RUNTIME_PERMISSION_SDK && !isPermissionGranted && !hasSeenPrimer
  }

  /**
   * 「通知已关闭」横幅该不该挂：系统层面通知关着，并且（Android 13+ 上）说明页已经问过。
   * 更早的系统上关着就是用户自己在系统设置里关的，直接挂。
   */
  @JvmStatic
  fun shouldShowDisabledBanner(sdkInt: Int, areNotificationsEnabled: Boolean, hasSeenPrimer: Boolean): Boolean {
    return !areNotificationsEnabled && (sdkInt < RUNTIME_PERMISSION_SDK || hasSeenPrimer)
  }
}
