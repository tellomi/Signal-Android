/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.account.logout

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.signal.appsettings.logout.LogoutAction
import org.signal.appsettings.logout.LogoutEvent
import org.signal.appsettings.logout.LogoutState
import org.signal.appsettings.logout.LogoutState.Dialog
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.logout.TellomiLogout

/**
 * Tellomi（ADR-0072，tellomi/tellomi#1414）：「退出登录」替代方案页。
 *
 * 保留聊天记录的退出（默认）要先让服务端停推送（`DELETE /v1/accounts/gcm`），失败就不退出、提示要联网；
 * 「退出并删除本机数据」尽量注销推送，没网也继续，然后走现有的 `clearApplicationUserData()`。
 */
class LogoutViewModel(
  private val repository: LogoutRepository = LogoutRepository()
) : EventDrivenViewModel<LogoutEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(LogoutViewModel::class)
  }

  private val _state = MutableStateFlow(LogoutState())
  private val _actions = Channel<LogoutAction>(Channel.BUFFERED)

  val state: StateFlow<LogoutState> = _state.asStateFlow()
  val actions: Flow<LogoutAction> = _actions.receiveAsFlow()

  init {
    viewModelScope.launch { refresh() }
  }

  override suspend fun processEvent(event: LogoutEvent) {
    when (event) {
      LogoutEvent.ScreenResumed -> refresh()
      LogoutEvent.NavigateBackClicked -> _actions.send(LogoutAction.NavigateBack)
      LogoutEvent.ScreenLockClicked -> _actions.send(LogoutAction.NavigateToScreenLock)
      LogoutEvent.ManageStorageClicked -> _actions.send(LogoutAction.NavigateToManageStorage)
      LogoutEvent.ChangePhoneNumberClicked -> _actions.send(LogoutAction.NavigateToChangePhoneNumber)
      is LogoutEvent.UnlinkDevicesToggled -> _state.update { it.copy(unlinkDevices = event.enabled) }
      LogoutEvent.LogoutClicked -> _state.update { it.copy(dialog = Dialog.ConfirmLogout) }
      LogoutEvent.LogoutConfirmed -> applyLogoutConfirmed()
      LogoutEvent.LogoutAndDeleteClicked -> _state.update { it.copy(dialog = Dialog.ConfirmDeleteLocalData) }
      LogoutEvent.DeleteLocalDataConfirmed -> applyDeleteLocalDataConfirmed()
      LogoutEvent.DataWipeFailed -> {
        _state.update { it.copy(dialog = Dialog.None) }
        _actions.send(LogoutAction.ShowDataWipeFailed)
      }
      LogoutEvent.DialogDismissed -> _state.update { it.copy(dialog = Dialog.None) }
    }
  }

  private suspend fun applyLogoutConfirmed() {
    _state.update { it.copy(dialog = Dialog.InProgress) }

    when (repository.logOut(unlinkDevices = _state.value.unlinkDevices)) {
      TellomiLogout.Result.Success -> {
        Log.i(TAG, "Logged out. Going to the welcome screen.")
        _state.update { it.copy(dialog = Dialog.None) }
        _actions.send(LogoutAction.NavigateToWelcome)
      }
      TellomiLogout.Result.NeedsNetwork -> {
        Log.w(TAG, "Couldn't log out. Still logged in.")
        _state.update { it.copy(dialog = Dialog.NeedsNetwork) }
      }
    }
  }

  private suspend fun applyDeleteLocalDataConfirmed() {
    _state.update { it.copy(dialog = Dialog.InProgress) }
    repository.prepareForLocalDataDeletion(unlinkDevices = _state.value.unlinkDevices)
    _actions.send(LogoutAction.WipeAllData)
  }

  private fun refresh() {
    _state.update {
      it.copy(
        showScreenLock = !repository.isScreenLockEnabled(),
        hasLinkedDevices = repository.hasLinkedDevices()
      )
    }
  }
}
