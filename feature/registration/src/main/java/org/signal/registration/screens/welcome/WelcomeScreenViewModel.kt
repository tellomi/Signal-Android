/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.welcome

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationFlowState
import org.signal.registration.RegistrationRepository
import org.signal.registration.RegistrationRoute
import org.signal.registration.TellomiRelogin
import org.signal.registration.screens.util.navigateTo

/**
 * Drives the welcome screen. It observes the parent flow state to decide whether to offer the restore-or-transfer
 * option (which depends on asynchronously-loaded pre-existing registration data) and handles the screen's navigation.
 *
 * Tellomi（tellomi/tellomi#1112）：注册流程里**一个权限都不要**。上游在这里按需插一页 [RegistrationRoute.Permissions]
 * （通知 / 通讯录 / 电话 / 存储）或 [RegistrationRoute.AllowNotifications]（链接设备前要通知）；Tellomi 一律直达下一页。
 * 通知改到注册完成、第一次进首屏时的说明页（tellomi/tellomi#1218 F-01），通讯录等有了按号码找人再在联系人页里要，
 * 电话、存储不要（`docs/legal/permissions.md` §三、§十）。两个路由本身保留，只是不再有人导航过去。
 */
class WelcomeScreenViewModel(
  private val repository: RegistrationRepository,
  private val parentState: StateFlow<RegistrationFlowState>,
  private val parentEventEmitter: (RegistrationFlowEvent) -> Unit
) : EventDrivenViewModel<WelcomeScreenEvents>(TAG) {

  companion object {
    private val TAG = Log.tag(WelcomeScreenViewModel::class)
  }

  private val _state = MutableStateFlow(WelcomeScreenState(isLinkAndSyncAvailable = repository.isLinkAndSyncAvailable, showRestoreOrTransfer = false))
  val state: StateFlow<WelcomeScreenState> = _state.asStateFlow()

  private val _actions = Channel<WelcomeScreenActions>(Channel.BUFFERED)
  val actions: Flow<WelcomeScreenActions> = _actions.receiveAsFlow()

  init {
    _state
      .onEach { Log.d(TAG, "[State] $it") }
      .launchIn(viewModelScope)

    parentState
      .onEach { onEvent(WelcomeScreenEvents.ParentStateChanged(it)) }
      .launchIn(viewModelScope)
  }

  override suspend fun processEvent(event: WelcomeScreenEvents) {
    applyEvent(_state.value, event, parentEventEmitter) { _state.value = it }
  }

  @VisibleForTesting
  fun applyEvent(state: WelcomeScreenState, event: WelcomeScreenEvents, parentEventEmitter: (RegistrationFlowEvent) -> Unit, stateEmitter: (WelcomeScreenState) -> Unit) {
    when (event) {
      is WelcomeScreenEvents.ParentStateChanged -> stateEmitter(applyParentState(state, event.parentState))
      WelcomeScreenEvents.Continue -> parentEventEmitter.navigateTo(RegistrationRoute.PhoneNumberEntry)
      WelcomeScreenEvents.HasOldPhone -> parentEventEmitter.navigateTo(RegistrationRoute.QuickRestoreQrScan)
      WelcomeScreenEvents.DoesNotHaveOldPhone -> parentEventEmitter.navigateTo(RegistrationRoute.ArchiveRestoreSelection.forManualRestore())
      WelcomeScreenEvents.LinkDevice -> parentEventEmitter.navigateTo(RegistrationRoute.LinkAccount())
      WelcomeScreenEvents.ViewTermsAndPrivacy -> _actions.trySend(WelcomeScreenActions.ViewTermsAndPrivacy)
      WelcomeScreenEvents.ReloginClicked -> {
        // Tellomi（ADR-0072 §4.2）：手机号页一打开就用本机账号的号码去要验证码，不用再输、再点「下一步」。
        parentEventEmitter(RegistrationFlowEvent.ReloginRequested)
        parentEventEmitter.navigateTo(RegistrationRoute.PhoneNumberEntry)
      }
    }
  }

  private fun applyParentState(state: WelcomeScreenState, parentState: RegistrationFlowState): WelcomeScreenState {
    if (parentState.isRestoringNavigationState) {
      return state
    }

    // Tellomi（ADR-0072 §4.1 第 4 步）：主动退出登录的账号，欢迎页上方显示「上次登录」（打码的号码 + 头像）。
    val loggedOutAccount = parentState.preExistingRegistrationData?.takeIf { it.loggedOut }
    val lastLogin = loggedOutAccount?.let {
      state.lastLogin ?: WelcomeScreenState.LastLogin(maskedE164 = TellomiRelogin.maskE164(it.e164)).also { loadLastLoginProfile() }
    }

    return state.copy(
      showRestoreOrTransfer = parentState.preExistingRegistrationData == null,
      lastLogin = lastLogin
    )
  }

  /** 头像和名字从本机读（不联网），读到了再补进「上次登录」。 */
  private fun loadLastLoginProfile() {
    viewModelScope.launch {
      val profile = repository.getStoredProfileData()
      val name = profile.givenName.trim()
      val initial = if (name.isEmpty()) "" else String(Character.toChars(name.codePointAt(0)))
      _state.update { state ->
        state.copy(lastLogin = state.lastLogin?.copy(avatar = profile.avatar, initial = initial))
      }
    }
  }
}
