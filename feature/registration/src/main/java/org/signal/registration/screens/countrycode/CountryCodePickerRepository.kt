/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.countrycode

import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.E164Util
import java.text.Collator
import java.util.Locale

/**
 * Repository for fetching country data used by the country code picker.
 */
class CountryCodePickerRepository {

  companion object {
    /**
     * A hardcoded list of countries to suggest during registration. Can change at any time.
     *
     * Tellomi（tellomi/tellomi#1210）：上游是 US / DE / IN / NL / UA；Tellomi 的用户在中国大陆、香港、澳门、台湾。
     * 显示名走系统语言的 CLDR 名称。
     */
    private val COMMON_COUNTRIES = listOf("CN", "HK", "MO", "TW")
  }

  suspend fun getCountries(): List<Country> = withContext(Dispatchers.IO) {
    val collator = Collator.getInstance(Locale.getDefault())
    collator.strength = Collator.PRIMARY

    PhoneNumberUtil.getInstance().supportedRegions
      .map { region ->
        Country(
          name = E164Util.getRegionDisplayName(region).orElse(""),
          emoji = countryToEmoji(region),
          countryCode = PhoneNumberUtil.getInstance().getCountryCodeForRegion(region),
          regionCode = region
        )
      }.sortedWith { lhs, rhs ->
        collator.compare(lhs.name.lowercase(Locale.getDefault()), rhs.name.lowercase(Locale.getDefault()))
      }
  }

  suspend fun getCommonCountries(): List<Country> = withContext(Dispatchers.IO) {
    COMMON_COUNTRIES.map { region ->
      Country(
        name = E164Util.getRegionDisplayName(region).orElse(""),
        emoji = countryToEmoji(region),
        countryCode = PhoneNumberUtil.getInstance().getCountryCodeForRegion(region),
        regionCode = region
      )
    }
  }

  private fun countryToEmoji(countryCode: String): String {
    return if (countryCode.isNotEmpty()) {
      countryCode
        .uppercase(Locale.US)
        .map { char -> Character.codePointAt("$char", 0) - 0x41 + 0x1F1E6 }
        .map { codePoint -> Character.toChars(codePoint) }
        .joinToString(separator = "") { charArray -> String(charArray) }
    } else {
      ""
    }
  }
}
