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
 * Tellomi（tellomi/tellomi#1233）：付款写死关闭，上游可用性矩阵里依赖远程开关的 5 条（DISABLED_REMOTELY、
 * WITHDRAW_ONLY ×2、REGISTRATION_AVAILABLE、WITHDRAW_AND_SEND）不再成立，删掉；全组合一律 NOT_IN_REGION 见 [TellomiPaymentsDisabledTest]。
 */
class PaymentsValuesTest {

  private lateinit var paymentValues: PaymentsValues

  @Before
  fun setup() {
    mockkObject(RemoteConfig)
    mockkObject(SignalStore)

    paymentValues = mockk()
    every { paymentValues.paymentsAvailability } answers { callOriginal() }

    every { SignalStore.payments } returns paymentValues

    every { SignalStore.account.isRegistered } returns true
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `when unregistered, expect NOT_IN_REGION`() {
    every { SignalStore.account.isRegistered } returns false

    assertEquals(PaymentsAvailability.NOT_IN_REGION, SignalStore.payments.paymentsAvailability)
  }

  @Test
  fun `when flag enabled and no account and in the country blocklist, expect NOT_IN_REGION`() {
    every { SignalStore.account.e164 } returns "+15551234567"
    every { paymentValues.mobileCoinPaymentsEnabled() } returns false
    every { RemoteConfig.payments } returns true
    every { RemoteConfig.paymentsCountryBlocklist } returns "1"

    assertEquals(PaymentsAvailability.NOT_IN_REGION, SignalStore.payments.paymentsAvailability)
  }
}
