/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.util

/**
 * Tellomi（tellomi/tellomi#1106，ADR-0066）：用户名不带「.数字」。
 *
 * 协议一行不动：libsignal 的 `Username` 仍然必须是 `<nickname>.<判别位>`，服务端只存 hash。
 * Tellomi 客户端把判别位固定成 [FIXED_DISCRIMINATOR]、界面只露 nickname，于是 hash 唯一性就是 nickname 唯一。
 * 这里放的是「用户输入 / 链接里的名字 → 协议层的完整用户名」这一步；显示侧（去掉 `.01`）另做。
 */
object TellomiUsernames {

  /** ADR-0066：三端新建用户名时判别位固定为 `01`。 */
  const val FIXED_DISCRIMINATOR = "01"

  private const val DELIMITER = '.'

  /**
   * 搜索框 / 链接里拿到的名字 → 可以拿去 `Username(…)` 的完整用户名。
   *
   * - 去掉首尾空白和前导 `@`；
   * - **没有 `.` 就补 `.01`**：`kaixin` → `kaixin.01`（ADR-0066 §八「搜索与链接解析补后缀」）；
   * - **已经带后缀的原样保留**：`kaixin.57` 这类旧账号必须还能按全名找到（非 `01` 后缀完整显示、完整查找）。
   *
   * 不做合法性校验：不合法的名字交给 `Username(…)` 抛异常，调用方照旧当「找不到」处理。
   */
  @JvmStatic
  fun toProtocolUsername(input: String): String {
    val trimmed = input.trim().removePrefix("@")
    return if (trimmed.contains(DELIMITER)) trimmed else "$trimmed$DELIMITER$FIXED_DISCRIMINATOR"
  }

  /**
   * 协议层的完整用户名 → 界面上显示的样子：**只有 `.01` 结尾的去掉后缀**（`kaixin.01` → `kaixin`）；
   * 别的后缀**完整显示**（`kaixin.57` 原样）——ADR-0066 §九的反向用例：别人用 `kaixin.57` 注册，
   * 官方客户端必须显示 `kaixin.57`，不能显示成 `kaixin`，否则就是冒充。
   */
  @JvmStatic
  fun toDisplayUsername(username: String): String {
    return username.removeSuffix("$DELIMITER$FIXED_DISCRIMINATOR")
  }
}
