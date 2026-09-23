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
}
