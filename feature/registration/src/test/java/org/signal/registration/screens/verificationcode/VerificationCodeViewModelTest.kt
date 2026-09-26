/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.verificationcode

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.signal.core.models.ServiceId.ACI
import org.signal.libsignal.net.RequestResult
import org.signal.network.api.RegistrationApiV2.RegisterAccountError
import org.signal.network.api.RegistrationApiV2.RegisterAccountResponse
import org.signal.network.api.RegistrationApiV2.RequestVerificationCodeError
import org.signal.network.api.RegistrationApiV2.SessionMetadata
import org.signal.network.api.RegistrationApiV2.SubmitVerificationCodeError
import org.signal.network.api.RegistrationApiV2.ThirdPartyServiceErrorResponse
import org.signal.network.api.RegistrationApiV2.VerificationCodeTransport
import org.signal.registration.KeyMaterial
import org.signal.registration.PendingRestoreOption
import org.signal.registration.PreExistingRegistrationData
import org.signal.registration.RegisteredAccountData
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationFlowState
import org.signal.registration.RegistrationRepository
import org.signal.registration.RegistrationRoute
import org.signal.registration.RegistrationViewModel
import org.signal.registration.VerificationCodeRequest
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VerificationCodeViewModelTest {

  private val testAci = ACI.from(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))

  private val testDispatcher = StandardTestDispatcher()

  private lateinit var viewModel: VerificationCodeViewModel
  private lateinit var mockRepository: RegistrationRepository
  private lateinit var parentState: MutableStateFlow<RegistrationFlowState>
  private lateinit var emittedEvents: MutableList<RegistrationFlowEvent>
  private lateinit var parentEventEmitter: (RegistrationFlowEvent) -> Unit
  private lateinit var emittedStates: MutableList<VerificationCodeState>
  private lateinit var stateEmitter: (VerificationCodeState) -> Unit

  @Before
  fun setup() {
    Dispatchers.setMain(testDispatcher)
    mockRepository = mockk(relaxed = true)
    // Initialize with valid session data to prevent ResetState emission during ViewModel initialization
    parentState = MutableStateFlow(
      RegistrationFlowState(
        sessionMetadata = createSessionMetadata(),
        sessionE164 = "+15551234567"
      )
    )
    emittedEvents = mutableListOf()
    parentEventEmitter = { event -> emittedEvents.add(event) }
    emittedStates = mutableListOf()
    stateEmitter = { state -> emittedStates.add(state) }
    viewModel = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  // ==================== ParentStateChanged Tests ====================

  @Test
  fun `ParentStateChanged with null sessionMetadata emits ResetState`() = runTest {
    val state = VerificationCodeState()
    val parentFlowState = RegistrationFlowState(
      sessionMetadata = null,
      sessionE164 = "+15551234567"
    )

    viewModel.applyEvent(state, VerificationCodeScreenEvents.ParentStateChanged(parentFlowState), stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first()).isEqualTo(RegistrationFlowEvent.ResetState)
  }

  @Test
  fun `ParentStateChanged with null sessionE164 emits ResetState`() = runTest {
    val state = VerificationCodeState()
    val parentFlowState = RegistrationFlowState(
      sessionMetadata = createSessionMetadata(),
      sessionE164 = null
    )

    viewModel.applyEvent(state, VerificationCodeScreenEvents.ParentStateChanged(parentFlowState), stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first()).isEqualTo(RegistrationFlowEvent.ResetState)
  }

  @Test
  fun `ParentStateChanged with both null values emits ResetState`() = runTest {
    val state = VerificationCodeState()
    val parentFlowState = RegistrationFlowState(
      sessionMetadata = null,
      sessionE164 = null
    )

    viewModel.applyEvent(state, VerificationCodeScreenEvents.ParentStateChanged(parentFlowState), stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first()).isEqualTo(RegistrationFlowEvent.ResetState)
  }

  @Test
  fun `ParentStateChanged with valid session copies metadata and e164`() = runTest {
    val state = VerificationCodeState()
    val sessionMetadata = createSessionMetadata(id = "test-session")
    val e164 = "+15551234567"
    val parentFlowState = RegistrationFlowState(
      sessionMetadata = sessionMetadata,
      sessionE164 = e164
    )

    viewModel.applyEvent(state, VerificationCodeScreenEvents.ParentStateChanged(parentFlowState), stateEmitter)

    assertThat(emittedEvents).hasSize(0)
    assertThat(emittedStates.last().sessionMetadata).isEqualTo(sessionMetadata)
    assertThat(emittedStates.last().e164).isEqualTo(e164)
  }

  @Test
  fun `ParentStateChanged preserves existing snackbars`() = runTest {
    val state = VerificationCodeState(snackbars = VerificationCodeState.Snackbars(networkError = true))
    val sessionMetadata = createSessionMetadata()
    val parentFlowState = RegistrationFlowState(
      sessionMetadata = sessionMetadata,
      sessionE164 = "+15551234567"
    )

    viewModel.applyEvent(state, VerificationCodeScreenEvents.ParentStateChanged(parentFlowState), stateEmitter)

    assertThat(emittedStates.last().snackbars.networkError).isTrue()
  }

  // ==================== applyEvent: Snackbar Dismissal Tests ====================

  @Test
  fun `NetworkErrorSnackbarDismissed clears only the network error snackbar`() = runTest {
    val initialState = VerificationCodeState(
      snackbars = VerificationCodeState.Snackbars(networkError = true, incorrectVerificationCode = true)
    )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.NetworkErrorSnackbarDismissed,
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars).isEqualTo(VerificationCodeState.Snackbars(incorrectVerificationCode = true))
  }

  @Test
  fun `NetworkErrorSnackbarDismissed with no snackbars showing leaves snackbars cleared`() = runTest {
    val initialState = VerificationCodeState()

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.NetworkErrorSnackbarDismissed,
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars).isEqualTo(VerificationCodeState.Snackbars())
  }

  // ==================== applyEvent: SMS Auto-Fill Tests ====================

  @Test
  fun `CodeAutoFilled stores the code in autoFillCode`() = runTest {
    val initialState = VerificationCodeState()

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeAutoFilled("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().autoFillCode).isEqualTo("123456")
  }

  @Test
  fun `DigitChanged with pasted hyphenated text populates all digits and submits`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.InvalidSessionIdOrVerificationCode("Wrong code")
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(0, "123-456"),
      stateEmitter
    )

    coVerify { mockRepository.submitVerificationCode(sessionMetadata.id, "123456") }
    assertThat(emittedStates.first().digits).isEqualTo(listOf("1", "2", "3", "4", "5", "6"))
    assertThat(emittedStates.first().isSubmittingCode).isTrue()
  }

  @Test
  fun `DigitChanged with a pasted plain code populates all digits and submits`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.InvalidSessionIdOrVerificationCode("Wrong code")
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(0, "123456"),
      stateEmitter
    )

    coVerify { mockRepository.submitVerificationCode(sessionMetadata.id, "123456") }
    assertThat(emittedStates.first().digits).isEqualTo(listOf("1", "2", "3", "4", "5", "6"))
    assertThat(emittedStates.first().isSubmittingCode).isTrue()
  }

  @Test
  fun `DigitChanged with a pasted SMS that contains other digits extracts the code`() = runTest {
    // Tellomi（tellomi/tellomi#1214）：「5 分钟内有效」里的 5 让数字凑成 7 位，上游会把整串忽略。
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+8613800138000"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.InvalidSessionIdOrVerificationCode("Wrong code")
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(0, "【Tellomi】您的验证码是 482913，5 分钟内有效。"),
      stateEmitter
    )

    coVerify { mockRepository.submitVerificationCode(sessionMetadata.id, "482913") }
    assertThat(emittedStates.first().digits).isEqualTo(listOf("4", "8", "2", "9", "1", "3"))
  }

  @Test
  fun `typing after an incorrect code hides the inline error`() = runTest {
    // Tellomi（tellomi/tellomi#1214）：错码提示是行内的，不再是会自己消失的 Snackbar；重新输入就收起。
    val initialState = VerificationCodeState(
      sessionMetadata = createSessionMetadata(),
      e164 = "+8613800138000",
      snackbars = VerificationCodeState.Snackbars(incorrectVerificationCode = true)
    )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.DigitChanged(0, "4"), stateEmitter)

    assertThat(emittedStates.last().snackbars.incorrectVerificationCode).isFalse()
    assertThat(emittedStates.last().digits.first()).isEqualTo("4")
  }

  @Test
  fun `DigitChanged with pasted text of the wrong length is ignored`() = runTest {
    val initialState = VerificationCodeState(
      sessionMetadata = createSessionMetadata(),
      e164 = "+15551234567"
    )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(0, "12-345"),
      stateEmitter
    )

    coVerify(exactly = 0) { mockRepository.submitVerificationCode(any(), any()) }
    assertThat(emittedStates.last().digits).isEqualTo(listOf("", "", "", "", "", ""))
  }

  @Test
  fun `ConsumeAutoFillCode clears autoFillCode`() = runTest {
    val initialState = VerificationCodeState(autoFillCode = "123456")

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.ConsumeAutoFillCode,
      stateEmitter
    )

    assertThat(emittedStates.last().autoFillCode).isNull()
  }

  @Test
  fun `codes from the SMS retriever flow are pushed into the state`() = runTest(testDispatcher) {
    val smsCodes = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val vm = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, smsCodes)

    backgroundScope.launch { vm.state.collect {} }
    advanceUntilIdle()

    smsCodes.emit("123456")
    advanceUntilIdle()

    assertThat(vm.state.value.autoFillCode).isEqualTo("123456")
  }

  @Test
  fun `DigitChanged with a full code dispatched through the event channel submits it in a single pass`() = runTest(testDispatcher) {
    val sessionMetadata = createSessionMetadata()
    parentState.value = RegistrationFlowState(
      sessionMetadata = sessionMetadata,
      sessionE164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.InvalidSessionIdOrVerificationCode("Wrong code")
      )

    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.onEvent(VerificationCodeScreenEvents.DigitChanged(0, "123456"))
    advanceUntilIdle()

    coVerify { mockRepository.submitVerificationCode(sessionMetadata.id, "123456") }
  }

  // ==================== applyEvent: DigitChanged Tests ====================

  @Test
  fun `DigitChanged records the value at the given index`() = runTest {
    val initialState = VerificationCodeState()

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(2, "7"),
      stateEmitter
    )

    assertThat(emittedStates.last().digits).isEqualTo(listOf("", "", "7", "", "", ""))
  }

  @Test
  fun `DigitChanged advances the focused digit index`() = runTest {
    val initialState = VerificationCodeState()

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(2, "7"),
      stateEmitter
    )

    assertThat(emittedStates.last().focusedDigitIndex).isEqualTo(3)
  }

  @Test
  fun `DigitChanged with an empty value moves the focused digit index back`() = runTest {
    val initialState = VerificationCodeState(digits = listOf("1", "2", "3", "", "", ""))

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(2, ""),
      stateEmitter
    )

    assertThat(emittedStates.last().focusedDigitIndex).isEqualTo(1)
  }

  @Test
  fun `DigitChanged with an out-of-bounds index throws`() = runTest {
    var threw = false
    try {
      viewModel.applyEvent(
        VerificationCodeState(),
        VerificationCodeScreenEvents.DigitChanged(9, "7"),
        stateEmitter
      )
    } catch (e: IllegalStateException) {
      threw = true
    }

    assertThat(threw).isTrue()
  }

  @Test
  fun `DigitChanged completing the code submits it`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567",
      digits = listOf("1", "2", "3", "4", "5", "")
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.InvalidSessionIdOrVerificationCode("Wrong code")
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(5, "6"),
      stateEmitter
    )

    coVerify { mockRepository.submitVerificationCode(sessionMetadata.id, "123456") }
    assertThat(emittedStates.first().isSubmittingCode).isTrue()
    assertThat(emittedStates.last().isSubmittingCode).isEqualTo(false)
  }

  @Test
  fun `DigitChanged does not submit until the code is complete`() = runTest {
    val initialState = VerificationCodeState(
      sessionMetadata = createSessionMetadata(),
      e164 = "+15551234567",
      digits = listOf("1", "2", "3", "4", "", "")
    )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(4, "5"),
      stateEmitter
    )

    coVerify(exactly = 0) { mockRepository.submitVerificationCode(any(), any()) }
    assertThat(emittedStates.last().isSubmittingCode).isEqualTo(false)
  }

  @Test
  fun `an incorrect code clears the entered digits`() = runTest {
    val initialState = VerificationCodeState(
      sessionMetadata = createSessionMetadata(),
      e164 = "+15551234567",
      digits = listOf("1", "2", "3", "4", "5", "")
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.InvalidSessionIdOrVerificationCode("Wrong code")
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(5, "6"),
      stateEmitter
    )

    assertThat(emittedStates.last().digits).isEqualTo(listOf("", "", "", "", "", ""))
    assertThat(emittedStates.last().snackbars.incorrectVerificationCode).isTrue()
  }

  @Test
  fun `DigitChanged with an empty value clears the digit at the index`() = runTest {
    val initialState = VerificationCodeState(digits = listOf("1", "2", "3", "", "", ""))

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(2, ""),
      stateEmitter
    )

    assertThat(emittedStates.last().digits).isEqualTo(listOf("1", "2", "", "", "", ""))
  }

  @Test
  fun `DigitChanged with an empty value shifts the following digits left`() = runTest {
    val initialState = VerificationCodeState(digits = listOf("1", "2", "3", "4", "5", "6"))

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(2, ""),
      stateEmitter
    )

    assertThat(emittedStates.last().digits).isEqualTo(listOf("1", "2", "4", "5", "6", ""))
  }

  @Test
  fun `DigitChanged with an empty value on an empty field clears the previous digit`() = runTest {
    val initialState = VerificationCodeState(digits = listOf("1", "2", "", "", "", ""))

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.DigitChanged(2, ""),
      stateEmitter
    )

    assertThat(emittedStates.last().digits).isEqualTo(listOf("1", "", "", "", "", ""))
  }

  // ==================== applyEvent: WrongNumber Tests ====================

  @Test
  fun `WrongNumber navigates to PhoneNumberEntry`() = runTest {
    val initialState = VerificationCodeState()

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.WrongNumber, stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first())
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isInstanceOf<RegistrationRoute.PhoneNumberEntry>()
  }

  // ==================== applyEvent: CodeEntered Tests ====================

  @Test
  fun `CodeEntered emits isSubmittingCode true then false`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.InvalidSessionIdOrVerificationCode("Wrong code")
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    // First emitted state should have isSubmittingCode = true
    assertThat(emittedStates.first().isSubmittingCode).isTrue()
    // Final emitted state should have isSubmittingCode = false
    assertThat(emittedStates.last().isSubmittingCode).isEqualTo(false)
  }

  @Test
  fun `CodeEntered emits ResetState when sessionMetadata is null`() = runTest {
    val initialState = VerificationCodeState(sessionMetadata = null)

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last()).isEqualTo(initialState)
    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first())
      .isInstanceOf<RegistrationFlowEvent.ResetState>()
  }

  @Test
  fun `CodeEntered with success registers account and navigates to PinCreate for new user`() = runTest {
    val sessionMetadata = createSessionMetadata(verified = true)
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    val registerResponse = createRegisterAccountResponse(storageCapable = false)
    val keyMaterial = mockk<KeyMaterial>(relaxed = true)

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.Success(RegisteredAccountData(registerResponse, keyMaterial, testAci))

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CodeEntered("123456"), stateEmitter)

    assertThat(emittedEvents).hasSize(3)
    assertThat(emittedEvents[0])
      .isInstanceOf<RegistrationFlowEvent.VerificationCodeAccepted>()
      .prop(RegistrationFlowEvent.VerificationCodeAccepted::code)
      .isEqualTo("123456")
    assertThat(emittedEvents[1]).isInstanceOf<RegistrationFlowEvent.Registered>()
    assertThat(emittedEvents[2])
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isInstanceOf<RegistrationRoute.PinCreate>()
  }

  @Test
  fun `CodeEntered reregistration with no pending restore option navigates to ArchiveRestoreSelection`() = runTest {
    val sessionMetadata = createSessionMetadata(verified = true)
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    val registerResponse = createRegisterAccountResponse(storageCapable = true, reregistration = true)
    val keyMaterial = mockk<KeyMaterial>(relaxed = true)

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.Success(RegisteredAccountData(registerResponse, keyMaterial, testAci))

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CodeEntered("123456"), stateEmitter)

    assertThat(emittedEvents[2])
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isInstanceOf<RegistrationRoute.ArchiveRestoreSelection>()
  }

  @Test
  fun `CodeEntered reregistration after pre-registration restore skips ArchiveRestoreSelection`() = runTest {
    parentState.value = parentState.value.copy(pendingRestoreOption = PendingRestoreOption.LocalBackup)

    val sessionMetadata = createSessionMetadata(verified = true)
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    val registerResponse = createRegisterAccountResponse(storageCapable = true, reregistration = true)
    val keyMaterial = mockk<KeyMaterial>(relaxed = true)

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.Success(RegisteredAccountData(registerResponse, keyMaterial, testAci))

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CodeEntered("123456"), stateEmitter)

    assertThat(emittedEvents[2])
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isInstanceOf<RegistrationRoute.PinEntryForSvrRestore>()
  }

  @Test
  fun `CodeEntered reregistration with preExistingRegistrationData skips ArchiveRestoreSelection`() = runTest {
    parentState.value = parentState.value.copy(preExistingRegistrationData = mockk<PreExistingRegistrationData>(relaxed = true))

    val sessionMetadata = createSessionMetadata(verified = true)
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    val registerResponse = createRegisterAccountResponse(storageCapable = true, reregistration = true)
    val keyMaterial = mockk<KeyMaterial>(relaxed = true)

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.Success(RegisteredAccountData(registerResponse, keyMaterial, testAci))

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CodeEntered("123456"), stateEmitter)

    assertThat(emittedEvents[2])
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isInstanceOf<RegistrationRoute.PinEntryForSvrRestore>()
  }

  @Test
  fun `CodeEntered with incorrect code returns IncorrectVerificationCode event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.InvalidSessionIdOrVerificationCode("Wrong code")
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars.incorrectVerificationCode).isTrue()
  }

  @Test
  fun `CodeEntered with session not found says it expired, then navigates back to phone number entry`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.SessionNotFound("Session expired")
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CodeEntered("123456"), stateEmitter)

    // Tellomi（tellomi/tellomi#1214）：上游不声不响地退回；先弹框说「验证已过期」，关掉才退回。
    assertThat(emittedEvents).isEmpty()
    assertThat(emittedStates.last().dialogs.sessionExpired).isTrue()

    viewModel.applyEvent(emittedStates.last(), VerificationCodeScreenEvents.SessionExpiredDialogDismissed, stateEmitter)

    assertThat(emittedEvents).containsExactly(RegistrationFlowEvent.SessionExpired, RegistrationFlowEvent.NavigateBack)
    assertThat(emittedStates.last().dialogs.sessionExpired).isFalse()
  }

  @Test
  fun `CodeEntered where registration finds the session gone says it expired, then navigates back to phone number entry`() = runTest {
    // Tellomi（tellomi/tellomi#1214，taishi 审查 b19 不阻塞 1）：验证码对了、注册时会话却没了。上游一声不响地退回；
    // 现在和提交验证码时一样先弹框，关掉后清会话（SessionExpired）再退回。
    val sessionMetadata = createSessionMetadata(verified = true)
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.NonSuccess(RegisterAccountError.SessionNotFoundOrNotVerified("Session not found"))

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CodeEntered("123456"), stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents[0]).isInstanceOf<RegistrationFlowEvent.VerificationCodeAccepted>()
    assertThat(emittedStates.last().dialogs.sessionExpired).isTrue()

    viewModel.applyEvent(emittedStates.last(), VerificationCodeScreenEvents.SessionExpiredDialogDismissed, stateEmitter)

    assertThat(emittedEvents.drop(1)).containsExactly(RegistrationFlowEvent.SessionExpired, RegistrationFlowEvent.NavigateBack)
    assertThat(emittedStates.last().dialogs.sessionExpired).isFalse()
  }

  @Test
  fun `CodeEntered with already verified session continues to register`() = runTest {
    val verifiedSession = createSessionMetadata(verified = true)
    val initialState = VerificationCodeState(
      sessionMetadata = createSessionMetadata(verified = false),
      e164 = "+15551234567"
    )

    val registerResponse = createRegisterAccountResponse(storageCapable = false)
    val keyMaterial = mockk<KeyMaterial>(relaxed = true)

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.SessionAlreadyVerifiedOrNoCodeRequested(verifiedSession)
      )
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.Success(RegisteredAccountData(registerResponse, keyMaterial, testAci))

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CodeEntered("123456"), stateEmitter)

    assertThat(emittedEvents).hasSize(3)
    assertThat(emittedEvents[0]).isInstanceOf<RegistrationFlowEvent.VerificationCodeAccepted>()
    assertThat(emittedEvents[1]).isInstanceOf<RegistrationFlowEvent.Registered>()
    assertThat(emittedEvents[2])
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isInstanceOf<RegistrationRoute.PinCreate>()
  }

  @Test
  fun `CodeEntered with no code requested and not verified says the code is no longer valid, then navigates back`() = runTest {
    val unverifiedSession = createSessionMetadata(verified = false)
    val initialState = VerificationCodeState(
      sessionMetadata = createSessionMetadata(),
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.SessionAlreadyVerifiedOrNoCodeRequested(unverifiedSession)
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CodeEntered("123456"), stateEmitter)

    // Tellomi（tellomi/tellomi#1214）：同上，先说清楚再退回。
    assertThat(emittedEvents).isEmpty()
    assertThat(emittedStates.last().dialogs.codeNoLongerValid).isTrue()

    viewModel.applyEvent(emittedStates.last(), VerificationCodeScreenEvents.SessionExpiredDialogDismissed, stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first()).isEqualTo(RegistrationFlowEvent.NavigateBack)
  }

  @Test
  fun `CodeEntered with rate limit from submitVerificationCode returns RateLimited event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.NonSuccess(
        SubmitVerificationCodeError.RateLimited(60.seconds, sessionMetadata)
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars.rateLimitedRetryAfter).isEqualTo(60.seconds)
  }

  @Test
  fun `CodeEntered with network error from submitVerificationCode returns NetworkError event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.RetryableNetworkError(java.io.IOException("Network error"))

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars.networkError).isTrue()
  }

  @Test
  fun `CodeEntered with application error from submitVerificationCode returns UnknownError event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.ApplicationError(RuntimeException("Unexpected"))

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars.unknownError).isTrue()
  }

  // ==================== applyEvent: CodeEntered - Registration Errors ====================

  @Ignore
  @Test
  fun `CodeEntered with DeviceTransferPossible emits ResetState`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.NonSuccess(
        RegisterAccountError.DeviceTransferPossible
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CodeEntered("123456"), stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first()).isEqualTo(RegistrationFlowEvent.ResetState)
  }

  @Ignore
  @Test
  fun `CodeEntered with rate limit from registerAccount returns RateLimited event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.NonSuccess(
        RegisterAccountError.RateLimited(30.seconds)
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars.rateLimitedRetryAfter).isEqualTo(30.seconds)
  }

  @Ignore
  @Test
  fun `CodeEntered with InvalidRequest from registerAccount returns RegistrationError event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.NonSuccess(
        RegisterAccountError.InvalidRequest("Bad request")
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars.registrationError).isTrue()
  }

  @Ignore
  @Test
  fun `CodeEntered with RegistrationRecoveryPasswordIncorrect returns RegistrationError event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.NonSuccess(
        RegisterAccountError.RegistrationRecoveryPasswordIncorrect("Wrong password")
      )

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars.registrationError).isTrue()
  }

  @Ignore
  @Test
  fun `CodeEntered with network error from registerAccount returns NetworkError event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.RetryableNetworkError(java.io.IOException("Network error"))

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars.networkError).isTrue()
  }

  @Ignore
  @Test
  fun `CodeEntered with application error from registerAccount returns UnknownError event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(
      sessionMetadata = sessionMetadata,
      e164 = "+15551234567"
    )

    coEvery { mockRepository.submitVerificationCode(any(), any()) } returns
      RequestResult.Success(sessionMetadata)
    coEvery { mockRepository.registerAccountWithSession(any(), any(), any()) } returns
      RequestResult.ApplicationError(RuntimeException("Unexpected"))

    viewModel.applyEvent(
      initialState,
      VerificationCodeScreenEvents.CodeEntered("123456"),
      stateEmitter
    )

    assertThat(emittedStates.last().snackbars.unknownError).isTrue()
  }

  // ==================== applyEvent: ResendSms Tests ====================

  @Test
  fun `ResendSms with null sessionMetadata emits ResetState`() = runTest {
    val initialState = VerificationCodeState(sessionMetadata = null)

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first()).isEqualTo(RegistrationFlowEvent.ResetState)
    assertThat(emittedStates.last()).isEqualTo(initialState)
  }

  @Test
  fun `ResendSms with success updates sessionMetadata`() = runTest {
    val sessionMetadata = createSessionMetadata(id = "original-session")
    val updatedSession = createSessionMetadata(id = "updated-session")
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.Success(updatedSession)

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().sessionMetadata).isEqualTo(updatedSession)
  }

  @Test
  fun `ResendSms success with a null nextCall marks calls unavailable instead of a bogus countdown`() = runTest {
    val updatedSession = createSessionMetadata(nextSms = 0L, nextCall = null)
    val initialState = VerificationCodeState(sessionMetadata = createSessionMetadata(), e164 = "+15551234567")

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.Success(updatedSession)

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().rateLimits).isEqualTo(
      SmsAndCallRateLimits(smsResendTimeRemaining = 0.seconds, callRequestTimeRemaining = null)
    )
  }

  @Test
  fun `ResendSms success with a null nextSms marks SMS resend unavailable instead of a bogus countdown`() = runTest {
    val updatedSession = createSessionMetadata(nextSms = null, nextCall = 30L)
    val initialState = VerificationCodeState(sessionMetadata = createSessionMetadata(), e164 = "+15551234567")

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.Success(updatedSession)

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().rateLimits).isEqualTo(
      SmsAndCallRateLimits(smsResendTimeRemaining = null, callRequestTimeRemaining = 30.seconds)
    )
  }

  @Test
  fun `ResendSms with success emits VerificationCodeRequested with the next allowed timestamp`() = runTest {
    val fixedNow = 1_000_000L
    val clockedViewModel = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { fixedNow })
    testDispatcher.scheduler.advanceUntilIdle()
    emittedEvents.clear()

    val updatedSession = createSessionMetadata(nextSms = 45L)
    val initialState = VerificationCodeState(sessionMetadata = createSessionMetadata(), e164 = "+15551234567")

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.Success(updatedSession)

    clockedViewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedEvents.filterIsInstance<RegistrationFlowEvent.VerificationCodeRequested>())
      .isEqualTo(listOf(RegistrationFlowEvent.VerificationCodeRequested("+15551234567", nextSmsAllowedTimestamp = fixedNow + 45_000, nextCallAllowedTimestamp = null)))
  }

  @Test
  fun `CallMe with success emits VerificationCodeRequested using nextCall`() = runTest {
    val fixedNow = 1_000_000L
    val clockedViewModel = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { fixedNow })
    testDispatcher.scheduler.advanceUntilIdle()
    emittedEvents.clear()

    val updatedSession = createSessionMetadata(nextCall = 90L)
    val initialState = VerificationCodeState(sessionMetadata = createSessionMetadata(), e164 = "+15551234567")

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.VOICE)) } returns
      RequestResult.Success(updatedSession)

    clockedViewModel.applyEvent(initialState, VerificationCodeScreenEvents.CallMe, stateEmitter)

    assertThat(emittedEvents.filterIsInstance<RegistrationFlowEvent.VerificationCodeRequested>())
      .isEqualTo(listOf(RegistrationFlowEvent.VerificationCodeRequested("+15551234567", nextSmsAllowedTimestamp = null, nextCallAllowedTimestamp = fixedNow + 90_000)))
  }

  // ==================== applyEvent: Rate Limit Seeding Tests ====================

  @Test
  fun `ParentStateChanged seeds resend countdowns from the recorded request windows`() = runTest {
    val fixedNow = 1_000_000L
    val clockedViewModel = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { fixedNow })
    testDispatcher.scheduler.advanceUntilIdle()

    val parentFlowState = RegistrationFlowState(
      sessionMetadata = createSessionMetadata(nextSms = 60L, nextCall = 60L),
      sessionE164 = "+15551234567",
      lastSmsVerificationCodeRequest = VerificationCodeRequest("+15551234567", fixedNow + 30_000),
      lastCallVerificationCodeRequest = VerificationCodeRequest("+15551234567", fixedNow + 10_000)
    )

    clockedViewModel.applyEvent(VerificationCodeState(), VerificationCodeScreenEvents.ParentStateChanged(parentFlowState), stateEmitter)

    assertThat(emittedStates.last().rateLimits).isEqualTo(
      SmsAndCallRateLimits(smsResendTimeRemaining = 30.seconds, callRequestTimeRemaining = 10.seconds)
    )
  }

  @Test
  fun `ParentStateChanged falls back to session metadata when no request windows are recorded`() = runTest {
    val fixedNow = 1_000_000L
    val clockedViewModel = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { fixedNow })
    testDispatcher.scheduler.advanceUntilIdle()

    val parentFlowState = RegistrationFlowState(
      sessionMetadata = createSessionMetadata(nextSms = 60L, nextCall = 15L),
      sessionE164 = "+15551234567"
    )

    clockedViewModel.applyEvent(VerificationCodeState(), VerificationCodeScreenEvents.ParentStateChanged(parentFlowState), stateEmitter)

    assertThat(emittedStates.last().rateLimits).isEqualTo(
      SmsAndCallRateLimits(smsResendTimeRemaining = 60.seconds, callRequestTimeRemaining = 15.seconds)
    )
  }

  @Test
  fun `ParentStateChanged ignores request windows recorded for a different number`() = runTest {
    val fixedNow = 1_000_000L
    val clockedViewModel = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { fixedNow })
    testDispatcher.scheduler.advanceUntilIdle()

    val parentFlowState = RegistrationFlowState(
      sessionMetadata = createSessionMetadata(nextSms = 5L),
      sessionE164 = "+15551234567",
      lastSmsVerificationCodeRequest = VerificationCodeRequest("+15559999999", fixedNow + 30_000)
    )

    clockedViewModel.applyEvent(VerificationCodeState(), VerificationCodeScreenEvents.ParentStateChanged(parentFlowState), stateEmitter)

    assertThat(emittedStates.last().rateLimits).isEqualTo(
      SmsAndCallRateLimits(smsResendTimeRemaining = 5.seconds, callRequestTimeRemaining = null)
    )
  }

  @Test
  fun `ParentStateChanged treats an expired request window as ready to resend`() = runTest {
    val fixedNow = 1_000_000L
    val clockedViewModel = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { fixedNow })
    testDispatcher.scheduler.advanceUntilIdle()

    val parentFlowState = RegistrationFlowState(
      sessionMetadata = createSessionMetadata(),
      sessionE164 = "+15551234567",
      lastSmsVerificationCodeRequest = VerificationCodeRequest("+15551234567", fixedNow - 1_000)
    )

    clockedViewModel.applyEvent(VerificationCodeState(), VerificationCodeScreenEvents.ParentStateChanged(parentFlowState), stateEmitter)

    assertThat(emittedStates.last().rateLimits).isEqualTo(
      SmsAndCallRateLimits(smsResendTimeRemaining = 0.seconds, callRequestTimeRemaining = null)
    )
  }

  @Test
  fun `ResendSms passes registerSmsListener result as smsAutoRetrieveCodeSupported`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.registerSmsListener() } returns true
    coEvery { mockRepository.requestVerificationCode(any(), any(), any()) } returns
      RequestResult.Success(sessionMetadata)

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    coVerify {
      mockRepository.requestVerificationCode(
        sessionId = sessionMetadata.id,
        smsAutoRetrieveCodeSupported = true,
        transport = VerificationCodeTransport.SMS
      )
    }
  }

  @Test
  fun `ResendSms with rate limit returns RateLimited event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.RateLimited(45.seconds, sessionMetadata)
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().dialogs.rateLimitedRetryAfter).isEqualTo(45.seconds)
  }

  @Test
  fun `ResendSms with InvalidRequest returns UnknownError event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.InvalidRequest("Bad request")
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().dialogs.unknownError).isTrue()
  }

  @Test
  fun `ResendSms with CouldNotFulfillWithRequestedTransport returns appropriate event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.CouldNotFulfillWithRequestedTransport(sessionMetadata)
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().dialogs.couldNotRequestCodeWithSelectedTransport).isTrue()
  }

  @Test
  fun `ResendSms with InvalidSessionId explains the expired session before navigating back`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.InvalidSessionId("Invalid session")
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    // Tellomi（tellomi/tellomi#1214，taishi 审查 b8）：先说清楚，用户关掉对话框才退回手机号页。
    assertThat(emittedEvents).isEmpty()
    assertThat(emittedStates.last().dialogs.sessionExpired).isTrue()

    viewModel.applyEvent(emittedStates.last(), VerificationCodeScreenEvents.SessionExpiredDialogDismissed, stateEmitter)

    // Tellomi（taishi 审查 b8-v2 不阻塞 1）：先清掉父状态里的旧会话，手机号页才会开新会话。
    assertThat(emittedEvents).containsExactly(RegistrationFlowEvent.SessionExpired, RegistrationFlowEvent.NavigateBack)
  }

  @Test
  fun `ResendSms with SessionNotFound explains the expired session before navigating back`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.SessionNotFound("Session not found")
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    // Tellomi（tellomi/tellomi#1214，taishi 审查 b8）：先说清楚，用户关掉对话框才退回手机号页。
    assertThat(emittedEvents).isEmpty()
    assertThat(emittedStates.last().dialogs.sessionExpired).isTrue()

    viewModel.applyEvent(emittedStates.last(), VerificationCodeScreenEvents.SessionExpiredDialogDismissed, stateEmitter)

    assertThat(emittedEvents).containsExactly(RegistrationFlowEvent.SessionExpired, RegistrationFlowEvent.NavigateBack)
  }

  @Test
  fun `ResendSms with MissingRequestInformationOrAlreadyVerified returns UnableToSendSms event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.MissingRequestInformationOrAlreadyVerified(sessionMetadata)
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().dialogs.unableToSendSms).isTrue()
  }

  @Test
  fun `ResendSms with ThirdPartyServiceError shows provider rejected dialog for SMS`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.ThirdPartyServiceError(
          ThirdPartyServiceErrorResponse("Provider error", false)
        )
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().dialogs.providerRejectedTransport).isEqualTo(VerificationCodeTransport.SMS)
  }

  @Test
  fun `ResendSms with network error returns NetworkError event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.RetryableNetworkError(java.io.IOException("Network error"))

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().dialogs.networkError).isTrue()
  }

  @Test
  fun `ResendSms with application error returns UnknownError event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.ApplicationError(RuntimeException("Unexpected"))

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.ResendSms, stateEmitter)

    assertThat(emittedStates.last().dialogs.unknownError).isTrue()
  }

  // ==================== applyEvent: CallMe Tests ====================

  @Test
  fun `CallMe with null sessionMetadata emits ResetState`() = runTest {
    val initialState = VerificationCodeState(sessionMetadata = null)

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CallMe, stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first()).isEqualTo(RegistrationFlowEvent.ResetState)
    assertThat(emittedStates.last()).isEqualTo(initialState)
  }

  @Test
  fun `CallMe with success updates sessionMetadata`() = runTest {
    val sessionMetadata = createSessionMetadata(id = "original-session")
    val updatedSession = createSessionMetadata(id = "updated-session")
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.VOICE)) } returns
      RequestResult.Success(updatedSession)

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CallMe, stateEmitter)

    assertThat(emittedStates.last().sessionMetadata).isEqualTo(updatedSession)
  }

  @Test
  fun `CallMe with rate limit returns RateLimited event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.VOICE)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.RateLimited(90.seconds, sessionMetadata)
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CallMe, stateEmitter)

    assertThat(emittedStates.last().dialogs.rateLimitedRetryAfter).isEqualTo(90.seconds)
  }

  @Test
  fun `CallMe with CouldNotFulfillWithRequestedTransport returns appropriate event`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.VOICE)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.CouldNotFulfillWithRequestedTransport(sessionMetadata)
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CallMe, stateEmitter)

    assertThat(emittedStates.last().dialogs.couldNotRequestCodeWithSelectedTransport).isTrue()
  }

  @Test
  fun `CallMe with ThirdPartyServiceError shows provider rejected dialog for VOICE`() = runTest {
    val sessionMetadata = createSessionMetadata()
    val initialState = VerificationCodeState(sessionMetadata = sessionMetadata)

    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.VOICE)) } returns
      RequestResult.NonSuccess(
        RequestVerificationCodeError.ThirdPartyServiceError(
          ThirdPartyServiceErrorResponse("Voice provider error", true)
        )
      )

    viewModel.applyEvent(initialState, VerificationCodeScreenEvents.CallMe, stateEmitter)

    assertThat(emittedStates.last().dialogs.providerRejectedTransport).isEqualTo(VerificationCodeTransport.VOICE)
  }

  // ==================== applyEvent: Foregrounded Tests ====================

  @Test
  fun `Foregrounded emits ResetState when in-progress data is older than the timeout`() = runTest {
    val now = 100.minutes.inWholeMilliseconds
    coEvery { mockRepository.getInProgressRegistrationDataLastUpdated() } returns now - 16.minutes.inWholeMilliseconds

    val vm = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { now })
    vm.applyEvent(VerificationCodeState(), VerificationCodeScreenEvents.Foregrounded, stateEmitter)

    assertThat(emittedEvents).hasSize(1)
    assertThat(emittedEvents.first()).isEqualTo(RegistrationFlowEvent.ResetState)
  }

  @Test
  fun `Foregrounded does not emit ResetState when in-progress data is within the timeout`() = runTest {
    val now = 100.minutes.inWholeMilliseconds
    coEvery { mockRepository.getInProgressRegistrationDataLastUpdated() } returns now - 14.minutes.inWholeMilliseconds

    val vm = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { now })
    vm.applyEvent(VerificationCodeState(), VerificationCodeScreenEvents.Foregrounded, stateEmitter)

    assertThat(emittedEvents).hasSize(0)
  }

  // Tellomi（tellomi/tellomi#1214，ADR-0051 §二「App 回前台重算」，taishi 审查 b8）

  @Test
  fun `Foregrounded recomputes the resend countdown from the recorded deadline`() = runTest {
    val now = 100.minutes.inWholeMilliseconds
    coEvery { mockRepository.getInProgressRegistrationDataLastUpdated() } returns now - 1.minutes.inWholeMilliseconds
    parentState.value = parentState.value.copy(lastSmsVerificationCodeRequest = VerificationCodeRequest("+15551234567", now + 10.seconds.inWholeMilliseconds))

    val vm = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { now })
    // App 在后台被冻结期间界面没往下数：还显示 50 秒，按截止时刻其实只剩 10 秒。
    val frozen = VerificationCodeState(rateLimits = SmsAndCallRateLimits(smsResendTimeRemaining = 50.seconds, callRequestTimeRemaining = null))
    vm.applyEvent(frozen, VerificationCodeScreenEvents.Foregrounded, stateEmitter)

    assertThat(emittedStates.last().rateLimits.smsResendTimeRemaining).isEqualTo(10.seconds)
    assertThat(emittedStates.last().rateLimits.callRequestTimeRemaining).isNull()
  }

  @Test
  fun `a rate-limited resend records the retry-after deadline, so coming back later still counts down`() = runTest {
    // Tellomi（tellomi/tellomi#1214，taishi 审查 b8-v2 不阻塞 2）：限流给 60 秒，10 秒后回到前台应显示 50 秒，而不是 0。
    // 会话里的 nextSms 故意和 retryAfter 不同（45 对 60），才钉得住「被限流的那一路用 retryAfter」（taishi 审查 b19 不阻塞 2）。
    val e164 = "+15551234567"
    var now = 100.minutes.inWholeMilliseconds
    coEvery { mockRepository.getInProgressRegistrationDataLastUpdated() } answers { now - 1.minutes.inWholeMilliseconds }
    val session = createSessionMetadata(nextSms = 45, nextCall = 30)
    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(RequestVerificationCodeError.RateLimited(60.seconds, session))

    val vm = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { now })
    vm.applyEvent(VerificationCodeState(sessionMetadata = session, e164 = e164), VerificationCodeScreenEvents.ResendSms, stateEmitter)

    val requested = emittedEvents.filterIsInstance<RegistrationFlowEvent.VerificationCodeRequested>().single()
    assertThat(requested).isEqualTo(
      RegistrationFlowEvent.VerificationCodeRequested(e164 = e164, nextSmsAllowedTimestamp = now + 60_000, nextCallAllowedTimestamp = now + 30_000)
    )
    // 界面上马上显示的倒计时和记下的截止时刻同源：短信 60 秒（不是会话里的 45 秒），电话 30 秒（taishi 审查 b19 不阻塞 2）。
    assertThat(emittedStates.last().rateLimits.smsResendTimeRemaining).isEqualTo(60.seconds)
    assertThat(emittedStates.last().rateLimits.callRequestTimeRemaining).isEqualTo(30.seconds)

    // 父状态照 RegistrationViewModel 那样记下截止时刻，然后 App 在后台待了 10 秒。
    parentState.value = parentState.value.copy(
      sessionE164 = e164,
      lastSmsVerificationCodeRequest = VerificationCodeRequest(e164, requested.nextSmsAllowedTimestamp!!),
      lastCallVerificationCodeRequest = VerificationCodeRequest(e164, requested.nextCallAllowedTimestamp!!)
    )
    now += 10.seconds.inWholeMilliseconds
    vm.applyEvent(emittedStates.last(), VerificationCodeScreenEvents.Foregrounded, stateEmitter)

    assertThat(emittedStates.last().rateLimits.smsResendTimeRemaining).isEqualTo(50.seconds)
    assertThat(emittedStates.last().rateLimits.callRequestTimeRemaining).isEqualTo(20.seconds)
  }

  @Test
  fun `a rate-limited call request records the retry-after deadline for calls`() = runTest {
    val e164 = "+15551234567"
    val now = 100.minutes.inWholeMilliseconds
    val session = createSessionMetadata(nextSms = 20, nextCall = null)
    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.VOICE)) } returns
      RequestResult.NonSuccess(RequestVerificationCodeError.RateLimited(90.seconds, session))

    val vm = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { now })
    vm.applyEvent(VerificationCodeState(sessionMetadata = session, e164 = e164), VerificationCodeScreenEvents.CallMe, stateEmitter)

    assertThat(emittedEvents.filterIsInstance<RegistrationFlowEvent.VerificationCodeRequested>().single()).isEqualTo(
      RegistrationFlowEvent.VerificationCodeRequested(e164 = e164, nextSmsAllowedTimestamp = now + 20_000, nextCallAllowedTimestamp = now + 90_000)
    )
    // 会话里 nextCall 为空，但这一路是被限流、不是不给打：界面按 retryAfter 倒数 90 秒，和记下的截止时刻一致，
    // 不再显示成「不可用」、离开再回来又变成 90 秒倒计时（taishi 审查 b19 不阻塞 2）。
    assertThat(emittedStates.last().rateLimits.callRequestTimeRemaining).isEqualTo(90.seconds)
    assertThat(emittedStates.last().rateLimits.smsResendTimeRemaining).isEqualTo(20.seconds)
  }

  @Test
  fun `Foregrounded leaves a countdown without a recorded deadline alone`() = runTest {
    val now = 100.minutes.inWholeMilliseconds
    coEvery { mockRepository.getInProgressRegistrationDataLastUpdated() } returns now - 1.minutes.inWholeMilliseconds

    val vm = VerificationCodeViewModel(mockRepository, parentState, parentEventEmitter, clock = { now })
    val frozen = VerificationCodeState(rateLimits = SmsAndCallRateLimits(smsResendTimeRemaining = 50.seconds, callRequestTimeRemaining = 0.seconds))
    vm.applyEvent(frozen, VerificationCodeScreenEvents.Foregrounded, stateEmitter)

    assertThat(emittedStates.last().rateLimits.smsResendTimeRemaining).isEqualTo(50.seconds)
    assertThat(emittedStates.last().rateLimits.callRequestTimeRemaining).isEqualTo(0.seconds)
  }

  @Test
  fun `Foregrounded does not emit ResetState when there is no in-progress data`() = runTest {
    coEvery { mockRepository.getInProgressRegistrationDataLastUpdated() } returns null

    viewModel.applyEvent(VerificationCodeState(), VerificationCodeScreenEvents.Foregrounded, stateEmitter)

    assertThat(emittedEvents).hasSize(0)
  }

  @Test
  fun `dismissing the expired dialog does not reset the flow when the parent drops the session`() = runTest {
    // taishi 审查 b19 要改 1 附的用例：RegistrationViewModel 处理完 SessionExpired 后父状态没有会话、号码还在，
    // 出栈动画结束前本 ViewModel 还在收；这时不能发 ResetState。
    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(RequestVerificationCodeError.SessionNotFound("gone"))
    viewModel.applyEvent(VerificationCodeState(sessionMetadata = createSessionMetadata(), e164 = "+15551234567"), VerificationCodeScreenEvents.ResendSms, stateEmitter)
    viewModel.applyEvent(emittedStates.last(), VerificationCodeScreenEvents.SessionExpiredDialogDismissed, stateEmitter)
    viewModel.applyEvent(emittedStates.last(), VerificationCodeScreenEvents.ParentStateChanged(RegistrationFlowState(sessionMetadata = null, sessionE164 = "+15551234567")), stateEmitter)
    assertThat(emittedEvents).containsExactly(RegistrationFlowEvent.SessionExpired, RegistrationFlowEvent.NavigateBack)
  }

  @Test
  fun `dismissing the expired dialog with the real parent flow lands on phone number entry with the number kept`() = runTest(testDispatcher) {
    // taishi 审查 b19 要改 1：把真的 RegistrationViewModel 接上，父状态真的跟着事件变，本页 ViewModel 也真的在收。
    val e164 = "+15551234567"
    val session = createSessionMetadata()
    coEvery { mockRepository.restoreFlowState() } returns RegistrationFlowState(
      backStack = listOf(RegistrationRoute.Welcome, RegistrationRoute.PhoneNumberEntry, RegistrationRoute.VerificationCodeEntry),
      sessionMetadata = session,
      sessionE164 = e164
    )
    coEvery { mockRepository.validateSession(session.id) } returns session
    coEvery { mockRepository.requestVerificationCode(any(), any(), eq(VerificationCodeTransport.SMS)) } returns
      RequestResult.NonSuccess(RequestVerificationCodeError.SessionNotFound("gone"))

    val registrationViewModel = RegistrationViewModel(mockRepository, SavedStateHandle())
    advanceUntilIdle()
    val codeViewModel = VerificationCodeViewModel(mockRepository, registrationViewModel.state, registrationViewModel::onEvent)
    try {
      advanceUntilIdle()
      assertThat(codeViewModel.state.value.sessionMetadata).isNotNull()

      codeViewModel.onEvent(VerificationCodeScreenEvents.ResendSms)
      advanceUntilIdle()
      assertThat(codeViewModel.state.value.dialogs.sessionExpired).isTrue()

      codeViewModel.onEvent(VerificationCodeScreenEvents.SessionExpiredDialogDismissed)
      advanceUntilIdle()

      val parent = registrationViewModel.state.value
      assertThat(parent.backStack).isEqualTo(listOf(RegistrationRoute.Welcome, RegistrationRoute.PhoneNumberEntry))
      assertThat(parent.sessionE164).isEqualTo(e164)
      assertThat(parent.sessionMetadata).isNull()
    } finally {
      codeViewModel.viewModelScope.cancel()
      registrationViewModel.viewModelScope.cancel()
    }
  }

  // ==================== Helper Functions ====================

  private fun createSessionMetadata(
    id: String = "test-session-id",
    requestedInformation: List<String> = emptyList(),
    verified: Boolean = false,
    nextSms: Long? = null,
    nextCall: Long? = null
  ) = SessionMetadata(
    id = id,
    nextSms = nextSms,
    nextCall = nextCall,
    nextVerificationAttempt = null,
    allowedToRequestCode = true,
    requestedInformation = requestedInformation,
    verified = verified
  )

  private fun createRegisterAccountResponse(
    aci: String = "test-aci",
    pni: String = "test-pni",
    e164: String = "+15551234567",
    storageCapable: Boolean = false,
    reregistration: Boolean = false
  ) = RegisterAccountResponse(
    aci = aci,
    pni = pni,
    e164 = e164,
    usernameHash = null,
    usernameLinkHandle = null,
    storageCapable = storageCapable,
    entitlements = null,
    reregistration = reregistration
  )
}
