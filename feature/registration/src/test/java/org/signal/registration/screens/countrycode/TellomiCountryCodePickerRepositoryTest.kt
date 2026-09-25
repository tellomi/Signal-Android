/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.countrycode

import assertk.assertThat
import assertk.assertions.isEmpty
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tellomi：注册选国家 / 地区页不显示「常用」（owner 2026-09-26，两端差异清单 N4）。
 */
class TellomiCountryCodePickerRepositoryTest {

  @Test
  fun `there is no list of common countries at the top`() = runTest {
    assertThat(CountryCodePickerRepository().getCommonCountries()).isEmpty()
  }
}
