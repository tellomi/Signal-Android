/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.util.RemoteConfig

/**
 * Tellomi（tellomi/tellomi#1233）：付款（MobileCoin 加密货币钱包）在 Tellomi 构建里永远不可用——
 * 不管远程开关、有没有钱包账户、号码在不在地区黑名单里，一律 NOT_IN_REGION（设置里不出钱包入口）。
 * 上游 [PaymentsValuesTest] 里依赖远程开关的那几条因此不再成立，已删掉。
 */
class TellomiPaymentsDisabledTest {

  private lateinit var paymentValues: PaymentsValues

  @Before
  fun setup() {
    mockkObject(RemoteConfig)
    mockkObject(SignalStore)

    paymentValues = mockk()
    every { paymentValues.paymentsAvailability } answers { callOriginal() }

    every { SignalStore.payments } returns paymentValues

    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.e164 } returns "+8613800138000"
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `payments are never available whatever the remote flag, the wallet and the blocklist say`() {
    for (remoteFlag in listOf(true, false)) {
      for (hasWallet in listOf(true, false)) {
        // "" 与 "1"：+86 不在黑名单里；"86"：在
        for (blocklist in listOf("", "1", "86")) {
          every { RemoteConfig.payments } returns remoteFlag
          every { paymentValues.mobileCoinPaymentsEnabled() } returns hasWallet
          every { RemoteConfig.paymentsCountryBlocklist } returns blocklist

          assertEquals("payments=$remoteFlag wallet=$hasWallet blocklist=\"$blocklist\"", PaymentsAvailability.NOT_IN_REGION, SignalStore.payments.paymentsAvailability)
        }
      }
    }
  }
}
