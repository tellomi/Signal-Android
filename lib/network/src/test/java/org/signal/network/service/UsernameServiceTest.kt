/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.network.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.net.UsernameNotAvailableException
import org.signal.libsignal.usernames.Username
import org.signal.network.api.AccountApiV2

/**
 * Tellomi（tellomi/tellomi#1106，ADR-0066）：新建用户名时判别位固定 `01`，只试这一个候选。
 * 上游这里用 `Username.candidatesFrom` 随机生成一批——01–99 被拒绝表挡住时还会落到三位数，名字就「换了个数字」成功了。
 */
class UsernameServiceTest {

  private val accountApi: AccountApiV2 = mockk()
  private val service = UsernameService(accountApi)

  @Test
  fun `without a discriminator only nickname dot 01 is sent`() = runTest {
    val expected = Username("kaixin.01")
    val sent = slot<List<ByteArray>>()
    coEvery { accountApi.reserveUsername(capture(sent)) } returns RequestResult.Success(expected.hash)

    val result = service.reserveUsername("kaixin")

    assertThat(sent.captured.size).isEqualTo(1)
    assertThat(sent.captured.single().contentEquals(expected.hash)).isTrue()
    assertThat(result).isInstanceOf(RequestResult.Success::class)
    assertThat((result as RequestResult.Success).result.username).isEqualTo("kaixin.01")
  }

  /** 被占 / 命中保留词：就是「不可用」，只请求一次，不换别的数字再试。 */
  @Test
  fun `taken or reserved is NotAvailable after a single attempt`() = runTest {
    coEvery { accountApi.reserveUsername(any()) } returns RequestResult.NonSuccess(mockk<UsernameNotAvailableException>())

    val result = service.reserveUsername("admin")

    assertThat(result).isEqualTo(RequestResult.NonSuccess(UsernameService.ReserveUsernameError.NotAvailable))
    coVerify(exactly = 1) { accountApi.reserveUsername(match { it.size == 1 }) }
  }

  /** 显式给了判别位（上游的「自己填数字」路径，Tellomi 界面上已经没有入口）照旧按给的来。 */
  @Test
  fun `an explicit discriminator is used as given`() = runTest {
    val expected = Username("kaixin.57")
    val sent = slot<List<ByteArray>>()
    coEvery { accountApi.reserveUsername(capture(sent)) } returns RequestResult.Success(expected.hash)

    service.reserveUsername("kaixin", "57")

    assertThat(sent.captured.single().contentEquals(expected.hash)).isTrue()
  }

  /** 昵称不合法：不发请求，直接 NicknameInvalid（33 位超过任何一档上限）。 */
  @Test
  fun `an invalid nickname is rejected without a request`() = runTest {
    val result = service.reserveUsername("a".repeat(33))

    assertThat(result).isEqualTo(RequestResult.NonSuccess(UsernameService.ReserveUsernameError.NicknameInvalid))
    coVerify(exactly = 0) { accountApi.reserveUsername(any()) }
  }
}
