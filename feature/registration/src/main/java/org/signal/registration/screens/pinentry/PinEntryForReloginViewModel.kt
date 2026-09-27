/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.pinentry

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationFlowState
import org.signal.registration.RegistrationRepository
import org.signal.registration.RegistrationRoute
import org.signal.registration.ReloginPinAttempts
import org.signal.registration.TellomiRelogin
import org.signal.registration.screens.util.navigateTo

/**
 * Tellomi（ADR-0072 §4.2 第 3 步）：已退出登录的账号开着注册锁，号码验证通过之后在**本机**核对 PIN——用本地保存的 PIN 哈希，
 * 不联网，也不走上游注册锁那条「从 SVR 取回主密钥、带着注册锁令牌再注册」的路（那会调 `POST /v1/registration`）。
 *
 * 输错按现有的次数限制处理：连续 [TellomiRelogin.MAX_PIN_ATTEMPTS] 次（与 SVR 默认一致）之后锁 [TellomiRelogin.PIN_LOCKOUT]，
 * 显示上游的「账号已锁定」页。记录跨进程保留，杀掉 App 重来不会清零。核对通过就解锁本机、结束流程。
 */
class PinEntryForReloginViewModel(
  private val repository: RegistrationRepository,
  private val parentState: StateFlow<RegistrationFlowState>,
  private val parentEventEmitter: (RegistrationFlowEvent) -> Unit,
  private val clock: () -> Long = { System.currentTimeMillis() }
) : EventDrivenViewModel<PinEntryScreenEvents>(TAG) {

  companion object {
    private val TAG = Log.tag(PinEntryForReloginViewModel::class)
  }

  private val _state = MutableStateFlow(PinEntryState(mode = PinEntryState.Mode.RegistrationLock))
  val state: StateFlow<PinEntryState> = _state.asStateFlow()

  init {
    _state
      .onEach { Log.d(TAG, "[State] $it") }
      .launchIn(viewModelScope)

    parentState
      .onEach { onEvent(PinEntryScreenEvents.ParentStateChanged(it)) }
      .launchIn(viewModelScope)
  }

  override suspend fun processEvent(event: PinEntryScreenEvents) {
    applyEvent(state.value, event) { _state.value = it }
  }

  @VisibleForTesting
  suspend fun applyEvent(state: PinEntryState, event: PinEntryScreenEvents, stateEmitter: (PinEntryState) -> Unit) {
    when (event) {
      is PinEntryScreenEvents.PinEntered -> {
        val localState = state.copy(loading = true)
        stateEmitter(localState)
        stateEmitter(applyPinEntered(localState, event.pin))
      }
      is PinEntryScreenEvents.ParentStateChanged -> {
        stateEmitter(state.copy(submittedVerificationCode = event.parentState.submittedVerificationCode))
      }
      // 注册锁模式不显示「跳过」，也没有「新建 PIN」：跳过 = 不核对就解锁，正是注册锁要挡住的事。
      is PinEntryScreenEvents.Skip,
      is PinEntryScreenEvents.CreateNewPin,
      is PinEntryScreenEvents.ContactSupport -> Unit
      is PinEntryScreenEvents.ToggleKeyboard,
      is PinEntryScreenEvents.NetworkErrorDialogDismissed,
      is PinEntryScreenEvents.RateLimitedDialogDismissed,
      is PinEntryScreenEvents.UnknownErrorDialogDismissed,
      is PinEntryScreenEvents.DismissContactSupport -> {
        stateEmitter(PinEntryScreenEventHandler.applyEvent(state, event))
      }
    }
  }

  private suspend fun applyPinEntered(state: PinEntryState, pin: String): PinEntryState {
    val now = clock()
    val attempts = repository.getReloginPinAttempts()

    if (attempts.lockedUntilMs > now) {
      Log.w(TAG, "[PinEntered] Still locked out from earlier wrong PINs.")
      parentEventEmitter.navigateTo(RegistrationRoute.AccountLocked(timeRemainingMs = attempts.lockedUntilMs - now))
      return state.copy(loading = false)
    }

    if (repository.verifyLocalPinForRelogin(pin)) {
      Log.i(TAG, "[PinEntered] PIN matches the local hash. Unlocking without re-registering.")
      repository.setReloginPinAttempts(ReloginPinAttempts())
      repository.completeRelogin()
      parentEventEmitter.navigateTo(RegistrationRoute.FullyComplete)
      return state
    }

    val failed = attempts.failed + 1
    return if (failed >= TellomiRelogin.MAX_PIN_ATTEMPTS) {
      Log.w(TAG, "[PinEntered] Wrong PIN. Out of attempts; locking for ${TellomiRelogin.PIN_LOCKOUT}.")
      val lockout = TellomiRelogin.PIN_LOCKOUT.inWholeMilliseconds
      repository.setReloginPinAttempts(ReloginPinAttempts(failed = 0, lockedUntilMs = now + lockout))
      parentEventEmitter.navigateTo(RegistrationRoute.AccountLocked(timeRemainingMs = lockout))
      state.copy(loading = false)
    } else {
      Log.w(TAG, "[PinEntered] Wrong PIN. Tries remaining: ${TellomiRelogin.MAX_PIN_ATTEMPTS - failed}")
      repository.setReloginPinAttempts(ReloginPinAttempts(failed = failed))
      state.copy(
        loading = false,
        triesRemaining = TellomiRelogin.MAX_PIN_ATTEMPTS - failed,
        enteredVerificationCode = pin == state.submittedVerificationCode
      )
    }
  }
}
