/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.util.billing.OneTimePurchaseApi
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.network.api.RegistrationApiV2.SessionMetadata
import org.signal.registration.fakes.FakeNetworkController
import org.signal.registration.fakes.FakeStorageController
import org.signal.registration.fakes.SystemOutLogger
import org.signal.registration.screens.phonenumber.PhoneNumberEntryScreenEvents
import org.signal.registration.screens.phonenumber.PhoneNumberEntryState
import org.signal.registration.screens.phonenumber.PhoneNumberEntryViewModel
import org.signal.registration.screens.pinentry.PinEntryForReloginViewModel
import org.signal.registration.screens.pinentry.PinEntryScreenEvents
import org.signal.registration.screens.pinentry.PinEntryState
import org.signal.registration.screens.verificationcode.VerificationCodeScreenEvents
import org.signal.registration.screens.verificationcode.VerificationCodeState
import org.signal.registration.screens.verificationcode.VerificationCodeViewModel
import java.util.UUID

/**
 * Tellomi（ADR-0072，tellomi/tellomi#1414）：主设备「退出登录」之后用同一个号码重新登录。
 *
 * 最要紧的一条：**重新登录的路上绝不调 `POST /v1/registration`**。同一个号码再注册，服务端走 reclaimAccount，
 * 退出期间别人发来、在服务器上排队的消息会被清空（ADR-0072 §二）。这里逐个钉住会走到注册的地方：
 * 验证码页、手机号页（恢复密码注册 / SVR 免短信）、注册锁 PIN 页，以及仓库里最后那道闸。
 * 整条界面流程的端到端用例在 [RegistrationEndToEndTest]（「Tellomi - a logged-out phone …」三条）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TellomiReloginTest {

  companion object {
    private const val E164 = "+8613812345678"
    private const val CODE = "123456"
    private const val PIN = "9182"
  }

  private val testDispatcher = StandardTestDispatcher()

  private lateinit var mockRepository: RegistrationRepository
  private lateinit var emittedEvents: MutableList<RegistrationFlowEvent>

  @Before
  fun setup() {
    Log.initialize(SystemOutLogger())
    Dispatchers.setMain(testDispatcher)
    mockRepository = mockk(relaxed = true)
    emittedEvents = mutableListOf()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  // ==================== 打码 ====================

  @Test
  fun `masks a mainland number to country code, first 3 and last 4 digits`() {
    assertThat(TellomiRelogin.maskE164("+8613812345678")).isEqualTo("+86 138****5678")
    assertThat(TellomiRelogin.maskE164("+8613800000010")).isEqualTo("+86 138****0010")
  }

  @Test
  fun `short national numbers keep only the last 2 digits`() {
    assertThat(TellomiRelogin.maskE164("+85261234")).isEqualTo("+852 ****34")
  }

  // ==================== 验证码页 ====================

  @Test
  fun `verified code on a logged-out phone unlocks without POST v1 registration`() = runTest {
    val viewModel = verificationCodeViewModel(loggedOut(E164))
    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns RequestResult.Success(session(verified = true))

    viewModel.applyEvent(VerificationCodeState(sessionMetadata = session(), e164 = E164), VerificationCodeScreenEvents.CodeEntered(CODE)) {}

    coVerify(exactly = 0) { mockRepository.registerAccountWithSession(any(), any(), any(), any()) }
    coVerify(exactly = 0) { mockRepository.registerAccountWithRecoveryPassword(any(), any(), any(), any(), any(), any()) }
    coVerify(exactly = 1) { mockRepository.completeRelogin() }
    assertThat(emittedEvents).contains(RegistrationFlowEvent.NavigateToScreen(RegistrationRoute.FullyComplete))
  }

  @Test
  fun `verified code on a logged-out phone with registration lock goes to the local pin check without registering`() = runTest {
    val viewModel = verificationCodeViewModel(loggedOut(E164, registrationLock = true))
    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns RequestResult.Success(session(verified = true))

    viewModel.applyEvent(VerificationCodeState(sessionMetadata = session(), e164 = E164), VerificationCodeScreenEvents.CodeEntered(CODE)) {}

    coVerify(exactly = 0) { mockRepository.registerAccountWithSession(any(), any(), any(), any()) }
    coVerify(exactly = 0) { mockRepository.completeRelogin() }
    assertThat(emittedEvents).contains(RegistrationFlowEvent.NavigateToScreen(RegistrationRoute.PinEntryForRelogin))
  }

  @Test
  fun `a verified session for a different number neither registers nor unlocks`() = runTest {
    val viewModel = verificationCodeViewModel(loggedOut("+8613900000000"))
    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns RequestResult.Success(session(verified = true))

    viewModel.applyEvent(VerificationCodeState(sessionMetadata = session(), e164 = E164), VerificationCodeScreenEvents.CodeEntered(CODE)) {}

    coVerify(exactly = 0) { mockRepository.registerAccountWithSession(any(), any(), any(), any()) }
    coVerify(exactly = 0) { mockRepository.completeRelogin() }
  }

  // ==================== 手机号页 ====================

  @Test
  fun `the same number on a logged-out phone only opens a verification session`() = runTest {
    val preExisting = loggedOut(E164)
    val viewModel = phoneNumberViewModel(preExisting)
    coEvery { mockRepository.createSession(any()) } returns RequestResult.Success(session())
    coEvery { mockRepository.requestVerificationCode(any(), any(), any()) } returns RequestResult.Success(session())

    viewModel.applyEvent(phoneState(preExisting, nationalNumber = "13812345678"), PhoneNumberEntryScreenEvents.PhoneNumberConfirmed, { emittedEvents += it }) {}

    coVerify(exactly = 1) { mockRepository.createSession(E164) }
    coVerify(exactly = 0) { mockRepository.registerAccountWithRecoveryPassword(any(), any(), any(), any(), any(), any()) }
    coVerify(exactly = 0) { mockRepository.checkSvrCredentials(any(), any()) }
    assertThat(emittedEvents).contains(RegistrationFlowEvent.NavigateToScreen(RegistrationRoute.VerificationCodeEntry))
  }

  @Test
  fun `another number on a logged-out phone asks before wiping and sends nothing`() = runTest {
    val preExisting = loggedOut(E164)
    val viewModel = phoneNumberViewModel(preExisting)
    val states = mutableListOf<PhoneNumberEntryState>()

    viewModel.applyEvent(phoneState(preExisting, nationalNumber = "13900000000"), PhoneNumberEntryScreenEvents.PhoneNumberConfirmed, { emittedEvents += it }) { states += it }

    assertThat(states.last().dialogs.confirmWipeForNewNumber).isEqualTo("+86 138****5678")
    coVerify(exactly = 0) { mockRepository.createSession(any()) }
    coVerify(exactly = 0) { mockRepository.registerAccountWithRecoveryPassword(any(), any(), any(), any(), any(), any()) }
    coVerify(exactly = 0) { mockRepository.clearLocalDataAndRestart() }

    viewModel.applyEvent(states.last(), PhoneNumberEntryScreenEvents.WipeForNewNumberConfirmed, { emittedEvents += it }) { states += it }

    coVerify(exactly = 1) { mockRepository.clearLocalDataAndRestart() }
  }

  // ==================== 注册锁：本机核对 PIN ====================

  @Test
  fun `the right pin unlocks without registering or asking SVR`() = runTest {
    val viewModel = pinViewModel()
    coEvery { mockRepository.getReloginPinAttempts() } returns ReloginPinAttempts(failed = 3)
    coEvery { mockRepository.verifyLocalPinForRelogin(PIN) } returns true

    viewModel.applyEvent(PinEntryState(mode = PinEntryState.Mode.RegistrationLock), PinEntryScreenEvents.PinEntered(PIN)) {}

    coVerify(exactly = 1) { mockRepository.setReloginPinAttempts(ReloginPinAttempts()) }
    coVerify(exactly = 1) { mockRepository.completeRelogin() }
    coVerify(exactly = 0) { mockRepository.restoreMasterKeyFromSvr(any(), any(), any(), any()) }
    coVerify(exactly = 0) { mockRepository.registerAccountWithSession(any(), any(), any(), any()) }
    coVerify(exactly = 0) { mockRepository.registerAccountWithRecoveryPassword(any(), any(), any(), any(), any(), any()) }
    assertThat(emittedEvents).containsExactly(RegistrationFlowEvent.NavigateToScreen(RegistrationRoute.FullyComplete))
  }

  @Test
  fun `a wrong pin is counted and shows the tries left`() = runTest {
    val viewModel = pinViewModel()
    coEvery { mockRepository.getReloginPinAttempts() } returns ReloginPinAttempts(failed = 2)
    coEvery { mockRepository.verifyLocalPinForRelogin(any()) } returns false
    val states = mutableListOf<PinEntryState>()

    viewModel.applyEvent(PinEntryState(mode = PinEntryState.Mode.RegistrationLock), PinEntryScreenEvents.PinEntered("0000")) { states += it }

    coVerify(exactly = 1) { mockRepository.setReloginPinAttempts(ReloginPinAttempts(failed = 3)) }
    coVerify(exactly = 0) { mockRepository.completeRelogin() }
    assertThat(states.last().triesRemaining).isEqualTo(TellomiRelogin.MAX_PIN_ATTEMPTS - 3)
  }

  @Test
  fun `the last wrong pin locks re-login for the lockout period`() = runTest {
    val now = 1_000_000L
    val viewModel = pinViewModel(clock = { now })
    coEvery { mockRepository.getReloginPinAttempts() } returns ReloginPinAttempts(failed = TellomiRelogin.MAX_PIN_ATTEMPTS - 1)
    coEvery { mockRepository.verifyLocalPinForRelogin(any()) } returns false

    viewModel.applyEvent(PinEntryState(mode = PinEntryState.Mode.RegistrationLock), PinEntryScreenEvents.PinEntered("0000")) {}

    val lockout = TellomiRelogin.PIN_LOCKOUT.inWholeMilliseconds
    coVerify(exactly = 1) { mockRepository.setReloginPinAttempts(ReloginPinAttempts(failed = 0, lockedUntilMs = now + lockout)) }
    coVerify(exactly = 0) { mockRepository.completeRelogin() }
    assertThat(emittedEvents).containsExactly(RegistrationFlowEvent.NavigateToScreen(RegistrationRoute.AccountLocked(timeRemainingMs = lockout)))
  }

  @Test
  fun `while locked out even the right pin is not checked`() = runTest {
    val now = 1_000_000L
    val viewModel = pinViewModel(clock = { now })
    coEvery { mockRepository.getReloginPinAttempts() } returns ReloginPinAttempts(lockedUntilMs = now + 60_000L)

    viewModel.applyEvent(PinEntryState(mode = PinEntryState.Mode.RegistrationLock), PinEntryScreenEvents.PinEntered(PIN)) {}

    coVerify(exactly = 0) { mockRepository.verifyLocalPinForRelogin(any()) }
    coVerify(exactly = 0) { mockRepository.completeRelogin() }
    assertThat(emittedEvents).containsExactly(RegistrationFlowEvent.NavigateToScreen(RegistrationRoute.AccountLocked(timeRemainingMs = 60_000L)))
  }

  // ==================== 流程状态 ====================

  @Test
  fun `resetting the flow keeps the logged-out account, so a reset never leads to a fresh registration`() = runTest {
    val preExisting = loggedOut(E164)
    coEvery { mockRepository.restoreFlowState() } returns null
    coEvery { mockRepository.getPreExistingRegistrationData() } returns preExisting
    val viewModel = RegistrationViewModel(mockRepository, SavedStateHandle())

    val reset = viewModel.applyEvent(RegistrationFlowState(preExistingRegistrationData = preExisting, sessionE164 = E164), RegistrationFlowEvent.ResetState)

    assertThat(reset.preExistingRegistrationData).isEqualTo(preExisting)
    assertThat(reset.sessionE164).isNull()
  }

  // ==================== 仓库里的最后一道闸 ====================

  @Test
  fun `the repository refuses to register while the device is logged out, without touching the network`() = runTest {
    val networkController = FakeNetworkController()
    val storageController = FakeStorageController().apply { preExistingRegistrationData = loggedOut(E164) }
    val repository = RegistrationRepository(
      context = mockk<Context>(relaxed = true),
      networkController = networkController,
      storageController = storageController,
      isLinkAndSyncAvailable = false,
      signalLoginPurchaseApi = OneTimePurchaseApi.Empty
    )

    val withSession = repository.registerAccountWithSession(e164 = E164, sessionId = "session")
    val withRecoveryPassword = repository.registerAccountWithRecoveryPassword(e164 = E164, recoveryPassword = "rrp")

    assertThat(withSession).isInstanceOf(RequestResult.ApplicationError::class)
    assertThat(withRecoveryPassword).isInstanceOf(RequestResult.ApplicationError::class)
    assertThat(networkController.lastRegisterAccountRequest).isNull()
  }

  // ==================== helpers ====================

  private fun verificationCodeViewModel(preExisting: PreExistingRegistrationData): VerificationCodeViewModel {
    val parentState = MutableStateFlow(RegistrationFlowState(sessionMetadata = session(), sessionE164 = E164, preExistingRegistrationData = preExisting))
    return VerificationCodeViewModel(mockRepository, parentState, { emittedEvents += it })
  }

  private fun phoneNumberViewModel(preExisting: PreExistingRegistrationData): PhoneNumberEntryViewModel {
    coEvery { mockRepository.getDefaultRegionCode() } returns "CN"
    val parentState = MutableStateFlow(RegistrationFlowState(preExistingRegistrationData = preExisting, isRestoringNavigationState = false))
    return PhoneNumberEntryViewModel(mockRepository, parentState, { emittedEvents += it })
  }

  private fun phoneState(preExisting: PreExistingRegistrationData, nationalNumber: String): PhoneNumberEntryState {
    return PhoneNumberEntryState(
      regionCode = "CN",
      countryCode = "86",
      nationalNumber = nationalNumber,
      formattedNumber = nationalNumber,
      preExistingRegistrationData = preExisting,
      isNumberPossible = true
    )
  }

  private fun pinViewModel(clock: () -> Long = { System.currentTimeMillis() }): PinEntryForReloginViewModel {
    val parentState = MutableStateFlow(RegistrationFlowState(preExistingRegistrationData = loggedOut(E164, registrationLock = true)))
    return PinEntryForReloginViewModel(mockRepository, parentState, { emittedEvents += it }, clock)
  }

  private fun loggedOut(e164: String, registrationLock: Boolean = false): PreExistingRegistrationData {
    return PreExistingRegistrationData(
      e164 = e164,
      aci = ACI.from(UUID.randomUUID()),
      pni = PNI.from(UUID.randomUUID()),
      servicePassword = "service-password",
      aep = AccountEntropyPool.generate(),
      registrationLockEnabled = registrationLock,
      unrestrictedUnidentifiedAccess = false,
      aciIdentityKeyPair = IdentityKeyPair.generate(),
      pniIdentityKeyPair = IdentityKeyPair.generate(),
      loggedOut = true
    )
  }

  private fun session(verified: Boolean = false) = SessionMetadata(
    id = "test-session-id",
    nextSms = null,
    nextCall = null,
    nextVerificationAttempt = null,
    allowedToRequestCode = true,
    requestedInformation = emptyList(),
    verified = verified
  )
}
