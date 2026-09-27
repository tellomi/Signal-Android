/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import assertk.assertThat
import assertk.assertions.each
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Tellomi（tellomi/tellomi#1215 第二刀）：用户名的本地规则（TR-ID-01）与候选。
 */
class TellomiUsernameEntryTest {

  @Test
  fun `valid nicknames pass`() {
    assertThat(TellomiUsernameEntry.check("kaixin")).isNull()
    assertThat(TellomiUsernameEntry.check("Kai_xin2026")).isNull()
    assertThat(TellomiUsernameEntry.check("abc")).isNull()
    assertThat(TellomiUsernameEntry.check("a".repeat(20))).isNull()
  }

  @Test
  fun `must start with a letter, including no leading underscore`() {
    assertThat(TellomiUsernameEntry.check("_kaixin")).isEqualTo(TellomiUsernameEntry.Error.MUST_START_WITH_LETTER)
    assertThat(TellomiUsernameEntry.check("2kaixin")).isEqualTo(TellomiUsernameEntry.Error.MUST_START_WITH_LETTER)
  }

  @Test
  fun `length and characters follow the upstream check`() {
    assertThat(TellomiUsernameEntry.check("ab")).isEqualTo(TellomiUsernameEntry.Error.TOO_SHORT)
    assertThat(TellomiUsernameEntry.check("a".repeat(21))).isEqualTo(TellomiUsernameEntry.Error.TOO_LONG)
    assertThat(TellomiUsernameEntry.check("kai-xin")).isEqualTo(TellomiUsernameEntry.Error.INVALID_CHARACTERS)
    assertThat(TellomiUsernameEntry.check("kai.xin")).isEqualTo(TellomiUsernameEntry.Error.INVALID_CHARACTERS)
    assertThat(TellomiUsernameEntry.check("张三")).isEqualTo(TellomiUsernameEntry.Error.INVALID_CHARACTERS)
  }

  @Test
  fun `three distinct valid candidates that differ from the nickname`() {
    val candidates = TellomiUsernameEntry.candidates("Kaixin", Random(1))

    assertThat(candidates).hasSize(3)
    assertThat(candidates.toSet()).hasSize(3)
    assertThat(candidates).each { it.transform { candidate -> TellomiUsernameEntry.check(candidate) == null && !candidate.equals("kaixin", ignoreCase = true) && candidate.startsWith("kaixin") }.isTrue() }
  }

  @Test
  fun `candidates stay within 20 characters`() {
    val candidates = TellomiUsernameEntry.candidates("a".repeat(20), Random(2))

    assertThat(candidates).hasSize(3)
    assertThat(candidates).each { it.transform { candidate -> candidate.length <= 20 }.isTrue() }
  }

  /**
   * taishi 审查包 4、中转包 8：词库的 PREFIX 规则以「非字母数字」为边界，`kefu_58`、`tellomi_support27` 都是要拦的形状，
   * 而服务端拒绝表只收「保留词 × 01–99」，这种候选能保留成功。所以候选只留原名里的字母和数字（中间的 `_` 也去掉），
   * 后面直接接两位或三位数字。
   */
  @Test
  fun `candidates keep only letters and digits of the nickname and append digits`() {
    val pattern = Regex("^[a-z][a-z0-9]*[0-9]{2,3}$")
    for (nickname in listOf("kefu", "tellomi", "admin", "Kaixin", "admin_", "kefu__", "tellomi_support", "kefu_tellomi", "kai_xin")) {
      val base = nickname.lowercase().filter { it != '_' }
      for (seed in 0 until 200) {
        val candidates = TellomiUsernameEntry.candidates(nickname, Random(seed))

        assertThat(candidates).hasSize(3)
        assertThat(candidates).each { it.transform { candidate -> pattern.matches(candidate) && candidate.startsWith(base) && '_' !in candidate }.isTrue() }
      }
    }
  }

  /** 超长的原名：去掉 `_` 之后截到 17 位，候选不超 20 位（中转包 7 的两个样例）。 */
  @Test
  fun `a long nickname is cut to seventeen letters or digits`() {
    for ((nickname, base) in listOf("xitongguanliyuan_ab" to "xitongguanliyuana", "customer_service_x" to "customerservicex")) {
      for (seed in 0 until 200) {
        val candidates = TellomiUsernameEntry.candidates(nickname, Random(seed))

        assertThat(candidates).hasSize(3)
        assertThat(candidates).each { it.transform { candidate -> Regex("$base[0-9]{2,3}").matches(candidate) && candidate.length <= 20 }.isTrue() }
      }
    }
  }

  @Test
  fun `no candidates when nothing usable is left of the nickname`() {
    assertThat(TellomiUsernameEntry.candidates("张三", Random(3))).isEmpty()
    assertThat(TellomiUsernameEntry.candidates("_2kaixin", Random(3))).isEmpty()
  }

  /** taishi 审查包 4：429 的 `Retry-After` 超过一小时是改名冷却，按天显示；与 Desktop、编辑页（#27）同一条线。 */
  @Test
  fun `retry after longer than an hour is the rename cooldown in whole days`() {
    assertThat(TellomiUsernameEntry.renameCooldownDays(2591999.seconds)).isEqualTo(30)
    assertThat(TellomiUsernameEntry.renameCooldownDays(86401.seconds)).isEqualTo(2)
    assertThat(TellomiUsernameEntry.renameCooldownDays(86400.seconds)).isEqualTo(1)
    assertThat(TellomiUsernameEntry.renameCooldownDays(2.hours)).isEqualTo(1)
    assertThat(TellomiUsernameEntry.renameCooldownDays(3601.seconds)).isEqualTo(1)
  }

  @Test
  fun `retry after within an hour is just too many attempts`() {
    assertThat(TellomiUsernameEntry.renameCooldownDays(9.seconds)).isNull()
    assertThat(TellomiUsernameEntry.renameCooldownDays(3600.seconds)).isNull()
    assertThat(TellomiUsernameEntry.renameCooldownDays(null)).isNull()
  }

  @Test
  fun `rate limited entry carries the cooldown or too many attempts`() {
    val checking = TellomiUsernameEntry(text = "kaixin", isChecking = true)

    val cooldown = checking.rateLimited(2591999.seconds)
    assertThat(cooldown.error).isEqualTo(TellomiUsernameEntry.Error.RENAME_COOLDOWN)
    assertThat(cooldown.cooldownDays).isEqualTo(30)
    assertThat(cooldown.isChecking).isFalse()
    assertThat(cooldown.isAcceptable).isFalse()

    val tooMany = checking.rateLimited(9.seconds)
    assertThat(tooMany.error).isEqualTo(TellomiUsernameEntry.Error.TOO_MANY_ATTEMPTS)
    assertThat(tooMany.isAcceptable).isFalse()

    assertThat(checking.rateLimited(null).error).isEqualTo(TellomiUsernameEntry.Error.TOO_MANY_ATTEMPTS)
  }
}
