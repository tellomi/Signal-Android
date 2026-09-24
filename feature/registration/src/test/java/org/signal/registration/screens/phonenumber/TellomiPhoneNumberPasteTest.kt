/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.phonenumber

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.signal.registration.RegistrationFlowState
import org.signal.registration.RegistrationRepository
import java.util.Locale

/**
 * Tellomi（tellomi/tellomi#1213）：粘贴 / 自动填充进来的一整串号码。上游只在空框、而且整串带「+」或以区号开头时拆出区号；
 * 「0086…」和往已经有字的框里粘完整号码，会被原样接到后面，变成十几位的无效号码（taishi 审查 b13 实跑过）。
 * iOS 的 TellomiPhoneNumberInputTest 测的是同一件事。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TellomiPhoneNumberPasteTest {

  private val testDispatcher = StandardTestDispatcher()
  private val emittedStates = mutableListOf<PhoneNumberEntryState>()

  private lateinit var defaultLocale: Locale
  private lateinit var viewModel: PhoneNumberEntryViewModel

  private val china = PhoneNumberEntryState(regionCode = "CN", countryCode = "86")
  private val chinaWith138 = china.copy(nationalNumber = "138", formattedNumber = "138")

  @Before
  fun setup() {
    defaultLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
    Dispatchers.setMain(testDispatcher)

    val repository: RegistrationRepository = mockk(relaxed = true)
    every { repository.getDefaultRegionCode() } returns "CN"
    viewModel = PhoneNumberEntryViewModel(repository, MutableStateFlow(RegistrationFlowState()), {})
    testDispatcher.scheduler.advanceUntilIdle()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    Locale.setDefault(defaultLocale)
  }

  @Test
  fun `0086 pasted into an empty field is read as +86`() = runTest {
    val result = change(china, oldValue = "", newValue = "0086 138 0013 8000")

    assertThat(result.countryCode).isEqualTo("86")
    assertThat(result.regionCode).isEqualTo("CN")
    assertThat(result.nationalNumber).isEqualTo("13800138000")
    assertThat(result.isNumberInvalid).isFalse()
  }

  @Test
  fun `a full number pasted after digits already in the field replaces them instead of being appended`() = runTest {
    for (pasted in listOf("+86 138 0013 8000", "0086 138 0013 8000", "8613800138000")) {
      val result = change(chinaWith138, oldValue = "138", newValue = "138$pasted")

      assertThat(result.countryCode, pasted).isEqualTo("86")
      assertThat(result.nationalNumber, pasted).isEqualTo("13800138000")
    }
  }

  @Test
  fun `a number copied with direction marks around it still counts as a full number`() = runTest {
    // 有的 App 复制出来的号码两头带 U+202D / U+202C（taishi 审查 b12 的疑问，Android 这边一起覆盖）。
    val result = change(chinaWith138, oldValue = "138", newValue = "138‭+86 138 0013 8000‬")

    assertThat(result.nationalNumber).isEqualTo("13800138000")
  }

  @Test
  fun `a number from another region written with 00 switches the region`() = runTest {
    val result = change(china, oldValue = "", newValue = "00852 9123 4567")

    assertThat(result.countryCode).isEqualTo("852")
    assertThat(result.regionCode).isEqualTo("HK")
    assertThat(result.nationalNumber).isEqualTo("91234567")
  }

  @Test
  fun `a local fragment that starts with 00 is still appended, not read as an international prefix`() = runTest {
    // 「0013 8000」去掉 00 是 +1 38000，不是有效号码，所以照常接在后面。
    val result = change(chinaWith138, oldValue = "138", newValue = "13800138000")

    assertThat(result.countryCode).isEqualTo("86")
    assertThat(result.nationalNumber).isEqualTo("13800138000")
  }

  @Test
  fun `selecting the whole number and pasting a 0086 number splits it even when both numbers end the same way`() = runTest {
    // 插入段按公共前后缀推断：两个号码尾部都是「000」时，推出来的插入段是「0086 139 0013 9」，要靠整框的 00 判断（taishi 审查 b18 不阻塞 2）。
    val filled = china.copy(nationalNumber = "13800138000", formattedNumber = "138 0013 8000")
    val cases = listOf(
      "0086 139 0013 9000" to "13900139000",
      "0086 138 0013 8000" to "13800138000",
      "0086138 0013 8000" to "13800138000"
    )

    for ((newValue, national) in cases) {
      val result = change(filled, oldValue = "138 0013 8000", newValue = newValue)

      assertThat(result.countryCode, newValue).isEqualTo("86")
      assertThat(result.nationalNumber, newValue).isEqualTo(national)
      assertThat(result.isNumberInvalid, newValue).isFalse()
    }
  }

  @Test
  fun `a number starting with the calling code splits when the rest is as long as the region's example number`() = runTest {
    // 两端共用样例，iOS 的 TellomiPhoneNumberInputTest 是同一组：框里已有一位数字，再粘以区号开头的完整号码。
    val cases = listOf(
      Triple(PhoneNumberEntryState(regionCode = "CN", countryCode = "86"), "8613800138000", "13800138000"),
      Triple(PhoneNumberEntryState(regionCode = "HK", countryCode = "852"), "85291234567", "91234567"),
      Triple(PhoneNumberEntryState(regionCode = "US", countryCode = "1"), "14155550100", "4155550100"),
      Triple(PhoneNumberEntryState(regionCode = "TW", countryCode = "886"), "886912345678", "912345678")
    )

    for ((region, pasted, national) in cases) {
      val old = national.take(1)
      val result = change(region.copy(nationalNumber = old, formattedNumber = old), oldValue = old, newValue = old + pasted)

      assertThat(result.countryCode, pasted).isEqualTo(region.countryCode)
      assertThat(result.nationalNumber, pasted).isEqualTo(national)
    }
  }

  @Test
  fun `a local number that happens to start with the calling code keeps the upstream result where number lengths vary`() = runTest {
    // DE / AT：去掉「区号」后也是有效号码，但位数和示例号码不同，不当完整号码拆；结果和上游相同（taishi 审查 b18 不阻塞 3）。
    val cases = listOf(
      Triple(PhoneNumberEntryState(regionCode = "DE", countryCode = "49", nationalNumber = "0", formattedNumber = "0"), "04921123456", "4921123456"),
      Triple(PhoneNumberEntryState(regionCode = "AT", countryCode = "43", nationalNumber = "6", formattedNumber = "6"), "64312345678", "64312345678")
    )

    for ((state, newValue, national) in cases) {
      val result = change(state, oldValue = state.nationalNumber, newValue = newValue)

      assertThat(result.countryCode, newValue).isEqualTo(state.countryCode)
      assertThat(result.nationalNumber, newValue).isEqualTo(national)
    }
  }

  private suspend fun change(state: PhoneNumberEntryState, oldValue: String, newValue: String): PhoneNumberEntryState {
    emittedStates.clear()
    viewModel.applyEvent(
      state,
      PhoneNumberEntryScreenEvents.NationalNumberChanged(oldValue = oldValue, newValue = newValue),
      {},
      { emittedStates += it }
    )
    return emittedStates.last()
  }
}
