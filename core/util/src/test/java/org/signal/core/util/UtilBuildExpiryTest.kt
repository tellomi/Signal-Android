/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import io.mockk.mockk
import org.junit.BeforeClass
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tellomi（tellomi/tellomi#1142，需求第 3.6 节）：构建兜底有效期三端统一 180 天，到期前 14 天开始提示。
 */
class UtilBuildExpiryTest {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpDependencies() {
      // init 只生效一次；下面按实际生效的 buildTimestamp 推算，不依赖是谁先初始化的。
      CoreUtilDependencies.init(
        mockk(relaxed = true),
        object : CoreUtilDependencies.Provider {
          override fun provideIsClientDeprecated(): Boolean = false
          override fun provideTimeUntilRemoteDeprecation(currentTime: Long): Long = -1
        },
        CoreUtilDependencies.BuildInfo(canonicalVersionCode = 1, buildTimestamp = 1_790_000_000_000L)
      )
    }
  }

  private val builtAt: Long
    get() = CoreUtilDependencies.buildInfo.buildTimestamp

  @Test
  fun `a build still works 179 days after it was made`() {
    assertThat(Util.getTimeUntilBuildExpiry(builtAt + 179.days.inWholeMilliseconds)).isGreaterThan(0L)
  }

  @Test
  fun `a build stops once it is past 180 days`() {
    assertThat(Util.getTimeUntilBuildExpiry(builtAt + 181.days.inWholeMilliseconds)).isEqualTo(0L)
  }

  @Test
  fun `166 days after the build there are 14 days left, when the warning starts`() {
    val remaining = Util.getTimeUntilBuildExpiry(builtAt + 166.days.inWholeMilliseconds).milliseconds
    assertThat(remaining.inWholeDays).isEqualTo(14L)
  }
}
