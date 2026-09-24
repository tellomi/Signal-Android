/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import org.signal.core.util.UsernameUtil
import org.signal.libsignal.usernames.Username
import kotlin.random.Random

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
  val confirmed: Username? = null
) {
  enum class Error {
    TOO_SHORT,
    TOO_LONG,
    INVALID_CHARACTERS,
    MUST_START_WITH_LETTER,

    /** 被占用或是保留名（服务端不区分）。 */
    NOT_AVAILABLE,

    /** 网络不好 / 被限流，没查成。改一下或稍后再输入会重查；不想要用户名可以清空直接进入。 */
    CHECK_FAILED
  }

  /** 没填 = 不设用户名，可以进入；填了就要保留成功才行。 */
  val isAcceptable: Boolean
    get() = text.isBlank() || (reservation != null && error == null && !isChecking)

  override fun toString(): String {
    return "TellomiUsernameEntry(text=${text.length} chars, error=$error, reserved=${reservation != null}, isChecking=$isChecking, candidates=${candidates.size}, confirmed=${confirmed != null})"
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
     * 不可用时给的候选：在原名后面加数字 / 下划线加数字，三个互不相同、都过得了 [check]。
     * 候选本身不预先向服务端查（保留有频率限制），用户点了照常走一遍检查。
     */
    @JvmStatic
    fun candidates(nickname: String, random: Random = Random.Default, count: Int = 3): List<String> {
      val base = nickname.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(UsernameUtil.MAX_NICKNAME_LENGTH - 3)
      if (base.isEmpty() || !base.first().isAsciiLetter()) {
        return emptyList()
      }

      val result = LinkedHashSet<String>()
      var attempts = 0
      while (result.size < count && attempts < count * 10) {
        val candidate = when (attempts % 3) {
          0 -> "$base${random.nextInt(10, 100)}"
          1 -> "${base}_${random.nextInt(10, 100)}"
          else -> "$base${random.nextInt(100, 1000)}"
        }
        if (!candidate.equals(nickname, ignoreCase = true) && check(candidate) == null) {
          result += candidate
        }
        attempts++
      }
      return result.toList()
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
  }
}
