/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.TellomiNames
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.RequestResult
import org.signal.network.service.UsernameService.ConfirmUsernameError
import org.signal.network.service.UsernameService.ReserveUsernameError
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationRepository
import org.signal.registration.RegistrationRoute
import org.signal.registration.screens.util.navigateTo
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for the registration profile-creation screen. Holds the user's typed name and selected
 * avatar bytes and submits them to [RegistrationRepository.setProfile] when the user advances.
 */
@OptIn(FlowPreview::class)
class CreateProfileViewModel(
  private val repository: RegistrationRepository,
  private val parentEventEmitter: (RegistrationFlowEvent) -> Unit,
  /** Tellomi（tellomi/tellomi#1215 第二刀）：候选用户名用的随机数，单测注入固定种子。 */
  private val random: Random = Random.Default
) : EventDrivenViewModel<CreateProfileScreenEvents>(TAG) {

  companion object {
    private val TAG = Log.tag(CreateProfileViewModel::class)

    /** Tellomi：与上游 AddUsernameViewModel 一样，停顿 500ms 再查。 */
    private val USERNAME_DEBOUNCE = 500.milliseconds
  }

  private val _state = MutableStateFlow(CreateProfileState())
  val state: StateFlow<CreateProfileState> = _state

  /** Tellomi：用户名框的输入，去抖后变成 [CreateProfileScreenEvents.UsernameSettled]。不去重：保留过期后要能用同一个名字重查。 */
  private val usernameEntries = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /** 进行中的保留请求，同一时间只留一个。 */
  private var reserveJob: Job? = null

  init {
    usernameEntries
      .debounce(USERNAME_DEBOUNCE)
      .onEach { onEvent(CreateProfileScreenEvents.UsernameSettled(it)) }
      .launchIn(viewModelScope)

    viewModelScope.launch {
      val stored = repository.getStoredProfileData()
      Log.i(TAG, "[init] Loaded stored profile data. givenName=${stored.givenName.isNotEmpty()}, familyName=${stored.familyName.isNotEmpty()}, avatar=${stored.avatar != null}")

      val seeded = _state.value.copy(
        givenName = stored.givenName,
        familyName = stored.familyName,
        avatar = stored.avatar,
        discoverableByPhoneNumber = stored.discoverableByPhoneNumber ?: true,
        isLoading = false
      )

      if (stored.givenName.isNotEmpty() && stored.avatar != null) {
        Log.i(TAG, "[init] Profile name + avatar already present. Auto-submitting and skipping screen.")
        _state.value = seeded.copy(isSubmitting = true)
        submitProfile(seeded)
      } else {
        // Tellomi（tellomi/tellomi#1215）：页面上只有一个「名字」框——已有的名 + 姓合成一个串放进去，保存时全进 given name。
        // 上面自动提交那条不动：恢复出来的资料原样保存，不替用户改名字。
        _state.value = seeded.copy(
          givenName = TellomiNames.joinForSingleField(stored.givenName, stored.familyName),
          familyName = ""
        )
      }
    }
  }

  override suspend fun processEvent(event: CreateProfileScreenEvents) {
    applyEvent(state.value, event, parentEventEmitter, repository) { _state.value = it }
  }

  @VisibleForTesting
  suspend fun applyEvent(
    state: CreateProfileState,
    event: CreateProfileScreenEvents,
    parentEventEmitter: (RegistrationFlowEvent) -> Unit,
    repository: RegistrationRepository,
    stateEmitter: (CreateProfileState) -> Unit
  ) {
    when (event) {
      is CreateProfileScreenEvents.GivenNameChanged -> {
        stateEmitter(state.copy(givenName = event.value))
      }
      is CreateProfileScreenEvents.FamilyNameChanged -> {
        stateEmitter(state.copy(familyName = event.value))
      }
      is CreateProfileScreenEvents.AvatarSelected -> {
        stateEmitter(state.copy(avatar = event.bytes))
      }
      CreateProfileScreenEvents.AvatarCleared -> {
        stateEmitter(state.copy(avatar = null))
      }
      CreateProfileScreenEvents.WhoCanFindMeClicked -> {
        parentEventEmitter.navigateTo(RegistrationRoute.PhoneNumberDiscoverability(state.discoverableByPhoneNumber))
      }
      is CreateProfileScreenEvents.DiscoverabilityChanged -> {
        stateEmitter(state.copy(discoverableByPhoneNumber = event.discoverable))
      }
      CreateProfileScreenEvents.NextClicked -> {
        if (state.isSubmitting || !state.isFormValid) {
          return
        }
        val submitting = state.copy(isSubmitting = true)
        stateEmitter(submitting)
        // Tellomi（tellomi/tellomi#1215 第二刀）：先确认用户名，成功了才保存资料——确认失败人还在这一页，能改
        val confirmed = confirmUsernameIfNeeded(submitting, repository, stateEmitter) ?: return
        submitProfile(confirmed, parentEventEmitter, repository, stateEmitter)
      }
      is CreateProfileScreenEvents.UsernameChanged -> applyUsernameChanged(state, event.value, stateEmitter)
      is CreateProfileScreenEvents.UsernameCandidateClicked -> applyUsernameChanged(state, event.value, stateEmitter)
      is CreateProfileScreenEvents.UsernameSettled -> applyUsernameSettled(state, event.nickname, repository, stateEmitter)
      is CreateProfileScreenEvents.UsernameReservationCompleted -> applyUsernameReservationCompleted(state, event, stateEmitter)
      CreateProfileScreenEvents.UploadFailedDialogDismissed -> {
        stateEmitter(state.copy(showUploadFailedDialog = false))
      }
    }
  }

  // region Tellomi（tellomi/tellomi#1215 第二刀）：选填用户名

  /**
   * 与 Telegram 两端同一个节奏（Android `ChangeUsernameActivity.checkUserName`、iOS `validateAddressNameInteractive`）：
   * 去掉习惯打的 `@`；格式不对立刻说；格式对了立刻显示「正在检查…」，停顿之后才去服务端。
   */
  private fun applyUsernameChanged(state: CreateProfileState, value: String, stateEmitter: (CreateProfileState) -> Unit) {
    val text = value.removePrefix("@")
    val entry = state.usernameEntry
    if (text == entry.text || entry.confirmed != null) {
      return
    }

    reserveJob?.cancel()

    if (text.isBlank()) {
      stateEmitter(state.copy(usernameEntry = TellomiUsernameEntry(text = text)))
      return
    }

    val error = TellomiUsernameEntry.check(text)
    if (error != null) {
      stateEmitter(state.copy(usernameEntry = TellomiUsernameEntry(text = text, error = error)))
      return
    }

    stateEmitter(state.copy(usernameEntry = TellomiUsernameEntry(text = text, isChecking = true)))
    usernameEntries.tryEmit(text)
  }

  private fun applyUsernameSettled(state: CreateProfileState, nickname: String, repository: RegistrationRepository, stateEmitter: (CreateProfileState) -> Unit) {
    val entry = state.usernameEntry
    if (nickname != entry.text || nickname.isBlank() || entry.confirmed != null) {
      return
    }

    val error = TellomiUsernameEntry.check(nickname)
    if (error != null) {
      stateEmitter(state.copy(usernameEntry = entry.copy(error = error, isChecking = false, reservation = null)))
      return
    }

    stateEmitter(state.copy(usernameEntry = entry.copy(error = null, isChecking = true, reservation = null, candidates = emptyList())))

    reserveJob?.cancel()
    reserveJob = viewModelScope.launch {
      // 不传判别位 = 保留 `<nickname>.01`（UsernameService 里 ADR-0066 的改法）
      val result = repository.reserveUsername(nickname)
      onEvent(CreateProfileScreenEvents.UsernameReservationCompleted(nickname, result))
    }
  }

  private fun applyUsernameReservationCompleted(
    state: CreateProfileState,
    event: CreateProfileScreenEvents.UsernameReservationCompleted,
    stateEmitter: (CreateProfileState) -> Unit
  ) {
    val entry = state.usernameEntry
    if (event.nickname != entry.text || entry.confirmed != null) {
      return
    }

    val updated = when (val result = event.result) {
      is RequestResult.Success -> {
        Log.i(TAG, "[reserveUsername] Reserved.")
        entry.copy(isChecking = false, reservation = result.result, error = null, candidates = emptyList())
      }
      is RequestResult.NonSuccess -> when (val error = result.error) {
        is ReserveUsernameError.NotAvailable, is ReserveUsernameError.NicknameInvalid -> {
          Log.w(TAG, "[reserveUsername] Not available: $error")
          entry.copy(isChecking = false, error = TellomiUsernameEntry.Error.NOT_AVAILABLE, candidates = TellomiUsernameEntry.candidates(event.nickname, random))
        }
        is ReserveUsernameError.RateLimited -> {
          Log.w(TAG, "[reserveUsername] Rate limited. Retry after: ${error.retryAfter}")
          entry.rateLimited(error.retryAfter)
        }
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[reserveUsername] Network error.", result.networkError)
        entry.copy(isChecking = false, error = TellomiUsernameEntry.Error.CHECK_FAILED)
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[reserveUsername] Application error.", result.cause)
        entry.copy(isChecking = false, error = TellomiUsernameEntry.Error.CHECK_FAILED)
      }
    }
    stateEmitter(state.copy(usernameEntry = updated))
  }

  /**
   * 填了用户名、还没确认过就确认它。返回确认之后的状态；没确认成时已经把原因放进状态（人留在这一页），返回 null。
   * 确认成功后用户名框锁住：之后保存资料失败再点「进入」不会再确认一次（再确认就算改名，会开始 30 天冷却）。
   */
  private suspend fun confirmUsernameIfNeeded(
    state: CreateProfileState,
    repository: RegistrationRepository,
    stateEmitter: (CreateProfileState) -> Unit
  ): CreateProfileState? {
    val entry = state.usernameEntry
    val reservation = entry.reservation
    if (entry.text.isBlank() || entry.confirmed != null || reservation == null) {
      return state
    }

    return when (val result = repository.confirmUsername(reservation)) {
      is RequestResult.Success -> {
        Log.i(TAG, "[confirmUsername] Confirmed.")
        state.copy(usernameEntry = entry.copy(confirmed = reservation)).also(stateEmitter)
      }
      is RequestResult.NonSuccess -> {
        when (result.error) {
          is ConfirmUsernameError.ReservationInvalid -> {
            // 保留约 5 分钟就过期：重新保留一次，好了再点「进入」
            Log.w(TAG, "[confirmUsername] Reservation lapsed. Reserving again.")
            stateEmitter(state.copy(isSubmitting = false, usernameEntry = entry.copy(reservation = null, isChecking = true)))
            usernameEntries.tryEmit(entry.text)
          }
          is ConfirmUsernameError.NotAvailable -> {
            Log.w(TAG, "[confirmUsername] No longer available.")
            stateEmitter(
              state.copy(
                isSubmitting = false,
                usernameEntry = entry.copy(reservation = null, error = TellomiUsernameEntry.Error.NOT_AVAILABLE, candidates = TellomiUsernameEntry.candidates(entry.text, random))
              )
            )
          }
          else -> {
            Log.w(TAG, "[confirmUsername] Failed: ${result.error}")
            stateEmitter(state.copy(isSubmitting = false, showUploadFailedDialog = true))
          }
        }
        null
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[confirmUsername] Network error.", result.networkError)
        stateEmitter(state.copy(isSubmitting = false, showUploadFailedDialog = true))
        null
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[confirmUsername] Application error.", result.cause)
        stateEmitter(state.copy(isSubmitting = false, showUploadFailedDialog = true))
        null
      }
    }
  }

  // endregion

  private suspend fun submitProfile(state: CreateProfileState) {
    submitProfile(state, parentEventEmitter, repository) { _state.value = it }
  }

  private suspend fun submitProfile(
    state: CreateProfileState,
    parentEventEmitter: (RegistrationFlowEvent) -> Unit,
    repository: RegistrationRepository,
    stateEmitter: (CreateProfileState) -> Unit
  ) {
    val result = repository.setProfile(
      givenName = state.givenName.trim(),
      familyName = state.familyName.trim(),
      avatar = state.avatar,
      discoverableByPhoneNumber = state.discoverableByPhoneNumber
    )
    when (result) {
      is RequestResult.Success -> {
        Log.i(TAG, "[submitProfile] Profile saved.")
        parentEventEmitter(RegistrationFlowEvent.RegistrationComplete)
      }
      is RequestResult.NonSuccess -> {
        Log.w(TAG, "[submitProfile] Profile save failed: ${result.error}")
        stateEmitter(state.copy(isSubmitting = false, showUploadFailedDialog = true))
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[submitProfile] Network error saving profile.", result.networkError)
        stateEmitter(state.copy(isSubmitting = false, showUploadFailedDialog = true))
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[submitProfile] Application error saving profile.", result.cause)
        stateEmitter(state.copy(isSubmitting = false, showUploadFailedDialog = true))
      }
    }
  }

  class Factory(
    private val repository: RegistrationRepository,
    private val parentEventEmitter: (RegistrationFlowEvent) -> Unit
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return CreateProfileViewModel(repository, parentEventEmitter) as T
    }
  }
}
