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
     * Tellomi（tellomi/tellomi#1210）：上游是 US / DE / IN / NL / UA；#1210 曾改成中国大陆、香港、澳门、台湾。
     * owner 2026-09-26（两端差异清单 N4）定：不显示「常用」，和 iOS 一样只留按字母排的完整列表，打开时滚到默认选中的国家。
     */
    private val COMMON_COUNTRIES = emptyList<String>()
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
