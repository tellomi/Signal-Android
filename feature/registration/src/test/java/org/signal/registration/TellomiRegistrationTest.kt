/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tellomi（tellomi/tellomi#1210）：限流等待时长给人看的样子（[TellomiRegistration.retryAfterText]）。
 * 上游把 Kotlin 的 `Duration.toString()`（「1m 30s」）原样填进「请在 %s 后再试」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiRegistrationTest {

  @Test
  fun chineseUsesLocalizedUnits() {
    // 中文用 SHORT 宽度：WIDE 会是「1分钟30秒钟」
    assertThat(TellomiRegistration.retryAfterText(90.seconds, Locale.SIMPLIFIED_CHINESE)).isEqualTo("1分钟30秒")
    assertThat(TellomiRegistration.retryAfterText(45.seconds, Locale.SIMPLIFIED_CHINESE)).isEqualTo("45秒")
    assertThat(TellomiRegistration.retryAfterText(2.hours + 5.minutes, Locale.SIMPLIFIED_CHINESE)).isEqualTo("2小时5分钟")
  }

  @Test
  fun englishUsesWords() {
    assertThat(TellomiRegistration.retryAfterText(90.seconds, Locale.US)).isEqualTo("1 minute, 30 seconds")
    assertThat(TellomiRegistration.retryAfterText(2.minutes, Locale.US)).isEqualTo("2 minutes")
  }

  @Test
  fun hoursDropSecondsAndSubSecondRoundsUpToOneSecond() {
    assertThat(TellomiRegistration.retryAfterText(2.hours + 5.minutes + 7.seconds, Locale.US)).isEqualTo("2 hours, 5 minutes")
    assertThat(TellomiRegistration.retryAfterText(300.milliseconds, Locale.US)).isEqualTo("1 second")
  }

  @Test
  fun neverTheKotlinDurationFormat() {
    // 上游的样子：「1m 30s」
    assertThat(TellomiRegistration.retryAfterText(90.seconds, Locale.SIMPLIFIED_CHINESE) == 90.seconds.toString()).isFalse()
  }
}
