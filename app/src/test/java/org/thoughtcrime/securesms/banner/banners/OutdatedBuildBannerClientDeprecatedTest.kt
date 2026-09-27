/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.banner.banners

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.util.Util
import org.thoughtcrime.securesms.keyvalue.MiscellaneousValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

/**
 * Tellomi（tellomi/tellomi#1138，taishi 审查 b14 包 8 不阻塞 2）：「客户端已弃用」置上之后 getTimeUntilBuildExpiry 恒为 0，
 * 到期提醒横幅会说「此版本将在今天过期」、按钮去浏览器；这时只留只读横幅（DeprecatedBuildBanner）说话。
 * 文件名与 b16 的 OutdatedBuildBannerTest 分开，两个 PR 谁先合都不冲突。
 */
class OutdatedBuildBannerClientDeprecatedTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(MiscellaneousValues::class))

  @Before
  fun setUp() {
    mockkStatic(Util::class)
    every { Util.getTimeUntilBuildExpiry(any()) } returns 0L
  }

  @After
  fun tearDown() {
    unmockkStatic(Util::class)
  }

  @Test
  fun `a build that expires today shows the expiry warning`() {
    assertThat(OutdatedBuildBanner().enabled).isTrue()
  }

  @Test
  fun `once the client is deprecated only the read-only banner is shown`() {
    every { SignalStore.misc.isClientDeprecated } returns true

    assertThat(OutdatedBuildBanner().enabled).isFalse()
    assertThat(DeprecatedBuildBanner().enabled).isTrue()
  }
}
