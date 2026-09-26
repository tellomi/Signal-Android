package org.thoughtcrime.securesms.profiles.manage

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository.ReserveFailure
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository.UsernameSetResult
import kotlin.time.Duration.Companion.seconds

class UsernameRepositoryTest {
  @Test
  fun parseLink_one_character_base64_ref() {
    val url = "https://signal.me/#eu/A"
    assertThat(UsernameRepository.parseLink(url)).isNull()
  }

  /** Tellomi（tellomi/tellomi#1106 第四刀，ADR-0066 §6.2）：reserve 的 429 按 Retry-After 分成改名冷却和普通限流。 */
  @Test
  fun reserveRateLimit_long_retry_after_is_rename_cooldown() {
    assertThat(UsernameRepository.rateLimitedReserveFailure(2591999.seconds)).isEqualTo(ReserveFailure(UsernameSetResult.CHANGE_COOLDOWN, 30))
    assertThat(UsernameRepository.rateLimitedReserveFailure(2.seconds * 86400 + 1.seconds)).isEqualTo(ReserveFailure(UsernameSetResult.CHANGE_COOLDOWN, 3))
  }

  @Test
  fun reserveRateLimit_short_or_missing_retry_after_is_plain_rate_limit() {
    assertThat(UsernameRepository.rateLimitedReserveFailure(9.seconds)).isEqualTo(ReserveFailure(UsernameSetResult.RATE_LIMIT_ERROR))
    assertThat(UsernameRepository.rateLimitedReserveFailure(3600.seconds)).isEqualTo(ReserveFailure(UsernameSetResult.RATE_LIMIT_ERROR))
    assertThat(UsernameRepository.rateLimitedReserveFailure(null)).isEqualTo(ReserveFailure(UsernameSetResult.RATE_LIMIT_ERROR))
  }
}
