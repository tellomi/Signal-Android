/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import org.signal.core.util.UsernameUtil
import org.signal.libsignal.usernames.Username
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.DurationUnit

/**
 * Tellomi（tellomi/tellomi#1215 第二刀）：注册资料页上的「用户名（选填）」。
 *
 * 停顿后先在本地按 TR-ID-01 查（字母开头、3–20 位、a–z 0–9 _），通过了再向服务端保留 `<名字>.01`（ADR-0066 判别位固定 01，
 * `UsernameService.reserveUsername` 不传判别位时就是这一个）。被占用、命中保留词服务端都回「不可用」，这时给 [candidates]。
 * 点「进入」时先确认保留，确认成功才保存资料——确认失败人还在这一页，能改。
 */
data class TellomiUsernameEntry(
  val text: String = "",
  val error: Error? = null,
  /** 服务端替我们保留着的用户名（约 5 分钟），点「进入」时确认它。 */
  val reservation: Username? = null,
  val isChecking: Boolean = false,
  val candidates: List<String> = emptyList(),
  /** 已经确认成了账号的用户名：之后保存资料失败再点「进入」时不再确认第二次。 */
  val confirmed: Username? = null,
  /** [Error.RENAME_COOLDOWN] 时还要等几天。 */
  val cooldownDays: Int = 0
) {
  enum class Error {
    TOO_SHORT,
    TOO_LONG,
    INVALID_CHARACTERS,
    MUST_START_WITH_LETTER,

    /** 被占用或是保留名（服务端不区分）。 */
    NOT_AVAILABLE,

    /** 网络不好 / 服务端出错，没查成。改一下或稍后再输入会重查；不想要用户名可以清空直接进入。 */
    CHECK_FAILED,

    /** 保留被限流（每号 100 次，之后每 15 分钟 1 次），`Retry-After` 在一小时以内。 */
    TOO_MANY_ATTEMPTS,

    /**
     * 改名冷却：回收号码的新主人会继承上一个人的 30 天冷却（ADR-0066 §6.2，服务端重新注册时带过来），
     * 这时任何用户名都保留不了，要等 [cooldownDays] 天。不想等就清空，直接进入。
     */
    RENAME_COOLDOWN
  }

  /** 没填 = 不设用户名，可以进入；填了就要保留成功才行。 */
  val isAcceptable: Boolean
    get() = text.isBlank() || (reservation != null && error == null && !isChecking)

  /** reserve 回了 429：按 `Retry-After` 分成「尝试次数过多」和「N 天后才能设置」（见 [renameCooldownDays]）。 */
  fun rateLimited(retryAfter: Duration?): TellomiUsernameEntry {
    val days = renameCooldownDays(retryAfter)
    return copy(
      isChecking = false,
      reservation = null,
      candidates = emptyList(),
      error = if (days != null) Error.RENAME_COOLDOWN else Error.TOO_MANY_ATTEMPTS,
      cooldownDays = days ?: 0
    )
  }

  override fun toString(): String {
    return "TellomiUsernameEntry(text=${text.length} chars, error=$error, reserved=${reservation != null}, isChecking=$isChecking, candidates=${candidates.size}, confirmed=${confirmed != null}, cooldownDays=$cooldownDays)"
  }

  companion object {
    /**
     * 本地规则（TR-ID-01）。长度、字符、数字开头交给上游的 [UsernameUtil.checkNickname]（上限已是 20）；
     * 它放行下划线开头，Tellomi 要求首字符是字母（ADR-0066），这里补上。
     */
    @JvmStatic
    fun check(nickname: String): Error? {
      if (nickname.isNotEmpty() && !nickname.first().isAsciiLetter()) {
        return if (nickname.first().isDigit() || nickname.first() == '_') Error.MUST_START_WITH_LETTER else Error.INVALID_CHARACTERS
      }
      return when (UsernameUtil.checkNickname(nickname)) {
        null -> null
        UsernameUtil.InvalidReason.TOO_SHORT -> Error.TOO_SHORT
        UsernameUtil.InvalidReason.TOO_LONG -> Error.TOO_LONG
        UsernameUtil.InvalidReason.STARTS_WITH_NUMBER -> Error.MUST_START_WITH_LETTER
        else -> Error.INVALID_CHARACTERS
      }
    }

    /**
     * 不可用时给的候选：原名后面**直接接数字**（两位、三位、再一个不同的两位），三个互不相同、都过得了 [check]。
     * 候选本身不预先向服务端查（保留有频率限制），用户点了照常走一遍检查。
     *
     * 候选只留原名里的字母和数字，`_` 一个不留（中间的也去掉），截到 17 位（taishi 审查包 4、中转包 8）：词库的 PREFIX 规则以
     * 「非字母数字」为边界（libsignal fork `rust/policy`），`kefu_58`、`tellomi_support27` 正是要拦的形状；而服务端拒绝表只收
     * 「保留词 × 01–99」（ADR-0062 §5.4），Android 还没接客户端引擎，这种候选能保留成功——等于客户端主动把冒充词推给恰好输了
     * `kefu`、`tellomi_support` 的人。紧跟数字不算边界，`kefu27` 不命中。以后接上客户端引擎，候选再过一遍它。
     */
    @JvmStatic
    fun candidates(nickname: String, random: Random = Random.Default, count: Int = 3): List<String> {
      val base = nickname.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(UsernameUtil.MAX_NICKNAME_LENGTH - 3)
      if (base.isEmpty() || !base.first().isAsciiLetter()) {
        return emptyList()
      }

      val result = LinkedHashSet<String>()
      var attempts = 0
      while (result.size < count && attempts < count * 10) {
        val candidate = when (attempts % 3) {
          1 -> "$base${random.nextInt(100, 1000)}"
          else -> "$base${random.nextInt(10, 100)}"
        }
        if (!candidate.equals(nickname, ignoreCase = true) && check(candidate) == null) {
          result += candidate
        }
        attempts++
      }
      return result.toList()
    }

    /**
     * reserve 回 429 时分辨改名冷却和普通限流：保留的限流桶 `Retry-After` 是秒级到 15 分钟，冷却是天级，
     * **超过一小时就是冷却**；天数向上取整、至少 1（`Retry-After` 2591999 秒 = 30 天，还剩两小时 = 1 天）。不是冷却返回 null。
     * 与 Desktop `isRenameCooldown` / `getRenameCooldownDays`、编辑页（tellomi/Signal-Android#27）同一条线，三端一致。
     *
     * TODO(tellomi/Signal-Android#27 合并后)：改用 core/util-jvm 的 `TellomiUsernames.isRenameCooldown` /
     *  `renameCooldownDaysLeft`，删掉这里的算法，免得两份以后改岔。
     */
    @JvmStatic
    fun renameCooldownDays(retryAfter: Duration?): Int? {
      if (retryAfter == null || retryAfter <= 1.hours) {
        return null
      }
      return ceil(retryAfter.toDouble(DurationUnit.DAYS)).toInt().coerceAtLeast(1)
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
  }
}
