/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.usernames.Username
import org.signal.network.service.UsernameService.ConfirmUsernameError
import org.signal.network.service.UsernameService.ReserveUsernameError
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationRepository
import org.signal.registration.StoredProfileData
import org.signal.registration.fakes.SystemOutLogger
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Tellomi（tellomi/tellomi#1215 第二刀）：资料页上的选填用户名。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateProfileUsernameTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private lateinit var repository: RegistrationRepository
  private lateinit var parentEvents: MutableList<RegistrationFlowEvent>
  private lateinit var viewModel: CreateProfileViewModel

  @Before
  fun setup() {
    Log.initialize(SystemOutLogger())
    Dispatchers.setMain(testDispatcher)
    repository = mockk(relaxed = true)
    coEvery { repository.getStoredProfileData() } returns StoredProfileData()
    coEvery { repository.setProfile(any(), any(), any(), any()) } returns RequestResult.Success(Unit)
    parentEvents = mutableListOf()
    viewModel = CreateProfileViewModel(repository, { parentEvents.add(it) }, Random(7))
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private val entry: TellomiUsernameEntry
    get() = viewModel.state.value.usernameEntry

  @Test
  fun `a valid username is reserved as nickname dot 01 after the pause`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.Success(Username("kaixin.01"))

    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    advanceUntilIdle()

    // 不传判别位 = UsernameService 保留 `<nickname>.01`（ADR-0066）
    coVerify(exactly = 1) { repository.reserveUsername("kaixin", null) }
    assertThat(entry.reservation).isEqualTo(Username("kaixin.01"))
    assertThat(entry.isChecking).isFalse()
    assertThat(viewModel.state.value.isFormValid).isTrue()
  }

  @Test
  fun `format errors show while typing and a valid name shows checking before the pause ends`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.Success(Username("kaixin.01"))

    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("ka"))
    assertThat(entry.error).isEqualTo(TellomiUsernameEntry.Error.TOO_SHORT)

    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    assertThat(entry.error).isNull()
    assertThat(entry.isChecking).isTrue()
    coVerify(exactly = 0) { repository.reserveUsername(any(), any()) }

    advanceUntilIdle()
    coVerify(exactly = 1) { repository.reserveUsername("kaixin", null) }
  }

  @Test
  fun `a typed at sign is dropped`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.Success(Username("kaixin.01"))

    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("@kaixin"))
    advanceUntilIdle()

    assertThat(entry.text).isEqualTo("kaixin")
    coVerify(exactly = 1) { repository.reserveUsername("kaixin", null) }
  }

  @Test
  fun `invalid usernames never reach the service`() = runTest(testDispatcher) {
    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("_kaixin"))
    advanceUntilIdle()

    assertThat(entry.error).isEqualTo(TellomiUsernameEntry.Error.MUST_START_WITH_LETTER)
    assertThat(viewModel.state.value.isFormValid).isFalse()
    coVerify(exactly = 0) { repository.reserveUsername(any(), any()) }
  }

  @Test
  fun `a taken or reserved username offers three candidates and a candidate is checked when tapped`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.NonSuccess(ReserveUsernameError.NotAvailable)

    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    advanceUntilIdle()

    assertThat(entry.error).isEqualTo(TellomiUsernameEntry.Error.NOT_AVAILABLE)
    assertThat(entry.candidates).hasSize(3)
    assertThat(viewModel.state.value.isFormValid).isFalse()

    val candidate = entry.candidates.first()
    coEvery { repository.reserveUsername(candidate) } returns RequestResult.Success(Username("$candidate.01"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameCandidateClicked(candidate))
    advanceUntilIdle()

    assertThat(entry.text).isEqualTo(candidate)
    assertThat(entry.reservation).isEqualTo(Username("$candidate.01"))
    assertThat(entry.candidates).isEmpty()
    assertThat(viewModel.state.value.isFormValid).isTrue()
  }

  @Test
  fun `a network failure while checking blocks entering until the username is fixed or cleared`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.RetryableNetworkError(IOException("offline"))

    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    advanceUntilIdle()

    assertThat(entry.error).isEqualTo(TellomiUsernameEntry.Error.CHECK_FAILED)
    assertThat(viewModel.state.value.isFormValid).isFalse()

    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged(""))
    advanceUntilIdle()

    assertThat(viewModel.state.value.isFormValid).isTrue()
  }

  /** taishi 审查包 4：回收号码的新主人继承了 30 天改名冷却，reserve 回 429 + 以天计的 Retry-After，要说还剩几天。 */
  @Test
  fun `an inherited rename cooldown shows the days left and blocks entering until cleared`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.NonSuccess(ReserveUsernameError.RateLimited(2591999.seconds))

    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    advanceUntilIdle()

    assertThat(entry.error).isEqualTo(TellomiUsernameEntry.Error.RENAME_COOLDOWN)
    assertThat(entry.cooldownDays).isEqualTo(30)
    assertThat(entry.candidates).isEmpty()
    assertThat(viewModel.state.value.isFormValid).isFalse()

    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged(""))
    advanceUntilIdle()

    assertThat(viewModel.state.value.isFormValid).isTrue()
  }

  @Test
  fun `a short rate limit is too many attempts`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.NonSuccess(ReserveUsernameError.RateLimited(9.seconds))

    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    advanceUntilIdle()

    assertThat(entry.error).isEqualTo(TellomiUsernameEntry.Error.TOO_MANY_ATTEMPTS)
    assertThat(viewModel.state.value.isFormValid).isFalse()
  }

  @Test
  fun `entering confirms the username before saving the profile`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.Success(Username("kaixin.01"))
    coEvery { repository.confirmUsername(Username("kaixin.01")) } returns RequestResult.Success(Unit)

    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    advanceUntilIdle()
    viewModel.onEvent(CreateProfileScreenEvents.NextClicked)
    advanceUntilIdle()

    coVerifyOrder {
      repository.confirmUsername(Username("kaixin.01"))
      repository.setProfile("开心", "", null, any())
    }
    assertThat(parentEvents).containsExactly(RegistrationFlowEvent.RegistrationComplete)
  }

  @Test
  fun `if the username was taken in the meantime the profile is not saved`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.Success(Username("kaixin.01"))
    coEvery { repository.confirmUsername(any()) } returns RequestResult.NonSuccess(ConfirmUsernameError.NotAvailable)

    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    advanceUntilIdle()
    viewModel.onEvent(CreateProfileScreenEvents.NextClicked)
    advanceUntilIdle()

    coVerify(exactly = 0) { repository.setProfile(any(), any(), any(), any()) }
    assertThat(parentEvents).isEmpty()
    assertThat(entry.error).isEqualTo(TellomiUsernameEntry.Error.NOT_AVAILABLE)
    assertThat(entry.reservation).isNull()
    assertThat(entry.candidates).hasSize(3)
    assertThat(viewModel.state.value.isSubmitting).isFalse()
  }

  @Test
  fun `a lapsed reservation is reserved again instead of failing`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.Success(Username("kaixin.01"))
    coEvery { repository.confirmUsername(any()) } returns RequestResult.NonSuccess(ConfirmUsernameError.ReservationInvalid)

    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    advanceUntilIdle()
    viewModel.onEvent(CreateProfileScreenEvents.NextClicked)
    advanceUntilIdle()

    coVerify(exactly = 2) { repository.reserveUsername("kaixin", null) }
    coVerify(exactly = 0) { repository.setProfile(any(), any(), any(), any()) }
    assertThat(entry.reservation).isEqualTo(Username("kaixin.01"))
    assertThat(viewModel.state.value.isSubmitting).isFalse()
  }

  @Test
  fun `without a username entering only saves the profile`() = runTest(testDispatcher) {
    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.NextClicked)
    advanceUntilIdle()

    coVerify(exactly = 0) { repository.confirmUsername(any()) }
    coVerify(exactly = 1) { repository.setProfile("开心", "", null, any()) }
    assertThat(parentEvents).containsExactly(RegistrationFlowEvent.RegistrationComplete)
  }

  @Test
  fun `once confirmed the username is not confirmed again when saving the profile is retried`() = runTest(testDispatcher) {
    coEvery { repository.reserveUsername("kaixin") } returns RequestResult.Success(Username("kaixin.01"))
    coEvery { repository.confirmUsername(any()) } returns RequestResult.Success(Unit)
    coEvery { repository.setProfile(any(), any(), any(), any()) } returns RequestResult.RetryableNetworkError(IOException("offline")) andThen RequestResult.Success(Unit)

    viewModel.onEvent(CreateProfileScreenEvents.GivenNameChanged("开心"))
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin"))
    advanceUntilIdle()
    viewModel.onEvent(CreateProfileScreenEvents.NextClicked)
    advanceUntilIdle()
    viewModel.onEvent(CreateProfileScreenEvents.UploadFailedDialogDismissed)
    // 确认过之后用户名框锁住：再改也不会变
    viewModel.onEvent(CreateProfileScreenEvents.UsernameChanged("kaixin2"))
    viewModel.onEvent(CreateProfileScreenEvents.NextClicked)
    advanceUntilIdle()

    assertThat(entry.text).isEqualTo("kaixin")
    coVerify(exactly = 1) { repository.confirmUsername(any()) }
    coVerify(exactly = 2) { repository.setProfile(any(), any(), any(), any()) }
    assertThat(parentEvents).containsExactly(RegistrationFlowEvent.RegistrationComplete)
  }
}
