package org.thoughtcrime.securesms.components.settings.app.subscription.currency

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Currency
import java.util.Locale

@Suppress("ClassName")
@RunWith(JUnit4::class)
class SetCurrencyViewModel__CurrencyComparatorTest {

  private val currencyComparator = SetCurrencyViewModel.CurrencyComparator(listOf("AUD", "EUR", "CAD"))

  private lateinit var defaultLocale: Locale

  // Tellomi（#1022 同类）：CurrencyComparator 用 getDisplayName(Locale.getDefault()) 排序，
  // 下面这些用例断言的是**英文显示名的字典序**（Australian Dollar < Canadian < Euro）。
  // Pro 这台 Mac 是 zh_TH，显示名成了「澳大利亚元 / 加拿大元 / 欧元」，顺序跟着变，于是本机必红、
  // CI 与英文环境的机器上绿。钉死 locale 而不是改断言：排序确实按显示名走，这层覆盖值得留着。
  @Before
  fun setUp() {
    defaultLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
  }

  @After
  fun tearDown() {
    Locale.setDefault(defaultLocale)
  }

  @Test
  fun givenAListOfCurrencies_whenISort_thenIExpectTheProperOrder() {
    // GIVEN
    val currencies = listOf("EUR", "AUD", "JPY", "USD", "CAD", "BWP", "BIF").map { Currency.getInstance(it) }
    val expected = listOf("USD", "AUD", "CAD", "EUR", "BWP", "BIF", "JPY").map { Currency.getInstance(it) }

    // WHEN
    val sorted: List<Currency> = currencies.sortedWith(currencyComparator)

    // THEN
    assertEquals(expected, sorted)
  }

  @Test
  fun givenUSDAndADefaultCurrency_whenISort_thenIExpectUSDFirst() {
    // GIVEN
    val currencies = listOf("EUR", "USD").map { Currency.getInstance(it) }
    val expected = listOf("USD", "EUR").map { Currency.getInstance(it) }

    // WHEN
    val sorted: List<Currency> = currencies.sortedWith(currencyComparator)

    // THEN
    assertEquals(expected, sorted)
  }

  @Test
  fun givenADefaultCurrencyAndANonDefaultCurrency_whenISort_thenIExpectUSDFirst() {
    // GIVEN
    val currencies = listOf("JPY", "EUR").map { Currency.getInstance(it) }
    val expected = listOf("EUR", "JPY").map { Currency.getInstance(it) }

    // WHEN
    val sorted: List<Currency> = currencies.sortedWith(currencyComparator)

    // THEN
    assertEquals(expected, sorted)
  }

  @Test
  fun givenTwoDefaultCurrencies_whenISort_thenIExpectOrderedByDisplayName() {
    // GIVEN
    val currencies = listOf("EUR", "AUD").map { Currency.getInstance(it) }
    val expected = listOf("AUD", "EUR").map { Currency.getInstance(it) }

    // WHEN
    val sorted = currencies.sortedWith(currencyComparator)

    // THEN
    assertEquals(expected, sorted)
  }

  @Test
  fun givenTwoNonDefaultCurrencies_whenISort_thenIExpectOrderedByDisplayName() {
    // GIVEN
    val currencies = listOf("XPF", "BIF").map { Currency.getInstance(it) }
    val expected = listOf("BIF", "XPF").map { Currency.getInstance(it) }

    // WHEN
    val sorted = currencies.sortedWith(currencyComparator)

    // THEN
    assertEquals(expected, sorted)
  }
}
