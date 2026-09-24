/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

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
}
