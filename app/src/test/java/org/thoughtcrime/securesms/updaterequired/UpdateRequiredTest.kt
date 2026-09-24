/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.signal.core.util.Util

/**
 * Tellomi（tellomi/tellomi#1138）：阻断页的原因文案——构建过期说「版本过旧」，其它（服务端 499 等）说「无法与服务器通信」。
 */
class UpdateRequiredTest {

  private val buildTimestamp = 1_790_000_000_000L

  @Test
  fun `a build past its lifespan is too old`() {
    val reason = UpdateRequired.reason(serverTime = buildTimestamp + Util.getBuildLifespan(), buildTimestamp = buildTimestamp)

    assertThat(reason).isEqualTo(UpdateRequiredState.Reason.BUILD_EXPIRED)
  }

  @Test
  fun `a build within its lifespan was turned away by the server`() {
    val reason = UpdateRequired.reason(serverTime = buildTimestamp + Util.getBuildLifespan() - 1, buildTimestamp = buildTimestamp)

    assertThat(reason).isEqualTo(UpdateRequiredState.Reason.SERVER_REJECTED)
  }
}
