/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Tellomi（tellomi/tellomi#1106，ADR-0066）：输入里的名字怎么变成协议层的完整用户名。 */
class TellomiUsernamesTest {

  @Test
  fun bareNicknameGetsTheFixedDiscriminator() {
    assertThat(TellomiUsernames.toProtocolUsername("kaixin")).isEqualTo("kaixin.01")
  }

  @Test
  fun leadingAtAndWhitespaceAreDropped() {
    assertThat(TellomiUsernames.toProtocolUsername("@kaixin")).isEqualTo("kaixin.01")
    assertThat(TellomiUsernames.toProtocolUsername("  kaixin \n")).isEqualTo("kaixin.01")
    assertThat(TellomiUsernames.toProtocolUsername(" @kaixin.57 ")).isEqualTo("kaixin.57")
  }

  /** 旧账号带随机后缀：必须原样保留，否则按全名永远找不到它。 */
  @Test
  fun anExistingDiscriminatorIsKept() {
    assertThat(TellomiUsernames.toProtocolUsername("kaixin.57")).isEqualTo("kaixin.57")
    assertThat(TellomiUsernames.toProtocolUsername("kaixin.01")).isEqualTo("kaixin.01")
    assertThat(TellomiUsernames.toProtocolUsername("kaixin.123")).isEqualTo("kaixin.123")
  }

  /** 不做合法性校验：带点但不合法的原样交回去，由 `Username(…)` 去拒。 */
  @Test
  fun malformedInputIsPassedThroughForUsernameToReject() {
    assertThat(TellomiUsernames.toProtocolUsername("kaixin.")).isEqualTo("kaixin.")
  }

  @Test
  fun displayDropsOnlyTheFixedDiscriminator() {
    assertThat(TellomiUsernames.toDisplayUsername("kaixin.01")).isEqualTo("kaixin")
  }

  /** ADR-0066 §九反向用例：别人的 `kaixin.57` 必须完整显示，不能显示成 `kaixin`。 */
  @Test
  fun otherDiscriminatorsAreShownInFull() {
    assertThat(TellomiUsernames.toDisplayUsername("kaixin.57")).isEqualTo("kaixin.57")
    assertThat(TellomiUsernames.toDisplayUsername("kaixin.101")).isEqualTo("kaixin.101")
    assertThat(TellomiUsernames.toDisplayUsername("kaixin.001")).isEqualTo("kaixin.001")
  }

  @Test
  fun renameCooldownIsTheLong429() {
    // 限流桶的 Retry-After 是秒级；改名冷却是天级。一小时是分界线（与 Desktop 相同）
    assertThat(TellomiUsernames.isRenameCooldown(null)).isFalse()
    assertThat(TellomiUsernames.isRenameCooldown(9.seconds)).isFalse()
    assertThat(TellomiUsernames.isRenameCooldown(3600.seconds)).isFalse()
    assertThat(TellomiUsernames.isRenameCooldown(3601.seconds)).isTrue()
    assertThat(TellomiUsernames.isRenameCooldown(2591999.seconds)).isTrue()
  }

  @Test
  fun renameCooldownDaysRoundUp() {
    assertThat(TellomiUsernames.renameCooldownDaysLeft(2591999.seconds)).isEqualTo(30)
    assertThat(TellomiUsernames.renameCooldownDaysLeft(86400.seconds)).isEqualTo(1)
    assertThat(TellomiUsernames.renameCooldownDaysLeft(86401.seconds)).isEqualTo(2)
    assertThat(TellomiUsernames.renameCooldownDaysLeft(2.hours)).isEqualTo(1)
  }

  /** ADR-0066 §6.2：删掉的用户名保留 30 天；没有记录不算，时钟往回拨算在内（宁可多提示一次）。与 Desktop 同一判法。 */
  @Test
  fun usernameHoldWindow() {
    val day = 24L * 60 * 60 * 1000
    val deletedAt = 1_700_000_000_000L
    assertThat(TellomiUsernames.isWithinUsernameHold(0, deletedAt)).isFalse()
    assertThat(TellomiUsernames.isWithinUsernameHold(deletedAt, deletedAt)).isTrue()
    assertThat(TellomiUsernames.isWithinUsernameHold(deletedAt, deletedAt + 30 * day - 1)).isTrue()
    assertThat(TellomiUsernames.isWithinUsernameHold(deletedAt, deletedAt + 30 * day)).isFalse()
    assertThat(TellomiUsernames.isWithinUsernameHold(deletedAt, deletedAt - day)).isTrue()
  }

  /** 与 Desktop `Username_test.dom.ts` 里 shouldRecordUsernameDeletion 的 5 条断言一一对应。 */
  @Test
  fun usernameDeletionFromSync() {
    // 别的设备删了：同步回来的 AccountRecord 没有用户名（proto 里是空串）
    assertThat(TellomiUsernames.isUsernameDeletion("kaixin.01", null)).isTrue()
    assertThat(TellomiUsernames.isUsernameDeletion("kaixin.01", "")).isTrue()
    // 首次同步、改名、两边都空：都不算删
    assertThat(TellomiUsernames.isUsernameDeletion("", "kaixin.01")).isFalse()
    assertThat(TellomiUsernames.isUsernameDeletion("kaixin.01", "bob.01")).isFalse()
    assertThat(TellomiUsernames.isUsernameDeletion("", "")).isFalse()
    // 本机原来就没有用户名（previous 为 null，比如刚装好还没同步过）：同步回来什么都不算删（taishi 包 7 不阻塞②）
    assertThat(TellomiUsernames.isUsernameDeletion(null, null)).isFalse()
    assertThat(TellomiUsernames.isUsernameDeletion(null, "")).isFalse()
    assertThat(TellomiUsernames.isUsernameDeletion(null, "kaixin.01")).isFalse()
  }
}
