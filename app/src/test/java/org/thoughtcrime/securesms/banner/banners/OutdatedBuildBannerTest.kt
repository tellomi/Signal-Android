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
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import kotlin.time.Duration.Companion.days

/**
 * Tellomi（tellomi/tellomi#1142，需求第 3.6 节）：构建到期前 14 天在会话列表提示（上游 10 天）。
 */
class OutdatedBuildBannerTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(MiscellaneousValues::class))

  @Before
  fun setUp() {
    mockkStatic(Util::class)
  }

  @After
  fun tearDown() {
    unmockkStatic(Util::class)
  }

  @Test
  fun `the warning shows 14 days before the build expires`() {
    every { Util.getTimeUntilBuildExpiry(any()) } returns 14.days.inWholeMilliseconds

    assertThat(OutdatedBuildBanner().enabled).isTrue()
  }

  @Test
  fun `no warning 15 days before`() {
    every { Util.getTimeUntilBuildExpiry(any()) } returns 15.days.inWholeMilliseconds

    assertThat(OutdatedBuildBanner().enabled).isFalse()
  }
}
