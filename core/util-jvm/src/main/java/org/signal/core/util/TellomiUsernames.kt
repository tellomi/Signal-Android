/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.util

import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.DurationUnit

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

  /**
   * ADR-0066 §6.2：换用户名之后 30 天内不能再换（服务端 `USERNAME_CHANGE_COOLDOWN`，tellomi/Signal-Server#4；首次设置不计）。
   * 只用在改名前的提醒；还剩多久永远以服务端 429 的 `Retry-After` 为准。
   */
  const val RENAME_COOLDOWN_DAYS = 30

  /**
   * reserve 回 429 时分辨「改名冷却」和普通限流：限流桶（`usernameReserve`，100 次 / 15 分钟）的 `Retry-After` 是秒级，
   * 冷却的是天级，**超过一小时就是冷却**。与 Desktop `isRenameCooldown`（tellomi/Signal-Desktop#2）同一条线，三端一致。
   */
  fun isRenameCooldown(retryAfter: Duration?): Boolean {
    return retryAfter != null && retryAfter > 1.hours
  }

  /** 冷却还剩几天：向上取整、至少 1（刚改完的 `Retry-After` 2591999 秒是 30 天，还剩两小时是 1 天）。与 Desktop 同一算法。 */
  fun renameCooldownDaysLeft(retryAfter: Duration): Int {
    return ceil(retryAfter.toDouble(DurationUnit.DAYS)).toInt().coerceAtLeast(1)
  }
}
