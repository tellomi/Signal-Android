/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.phonenumber

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.i18n.phonenumbers.AsYouTypeFormatter
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.signal.core.models.AccountEntropyPool
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.E164Util
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.RequestResult
import org.signal.network.api.RegistrationApiV2.CheckSvrCredentialsError
import org.signal.network.api.RegistrationApiV2.CreateSessionError
import org.signal.network.api.RegistrationApiV2.RegisterAccountError
import org.signal.network.api.RegistrationApiV2.RequestVerificationCodeError
import org.signal.network.api.RegistrationApiV2.SessionMetadata
import org.signal.network.api.RegistrationApiV2.UpdateSessionError
import org.signal.network.api.RegistrationApiV2.VerificationCodeTransport
import org.signal.registration.PendingRestoreOption
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationFlowState
import org.signal.registration.RegistrationRepository
import org.signal.registration.RegistrationRoute
import org.signal.registration.screens.countrycode.Country
import org.signal.registration.screens.countrycode.CountryUtils
import org.signal.registration.screens.localbackuprestore.LocalBackupRestoreResult
import org.signal.registration.screens.shared.AccountIdFormat
import org.signal.registration.screens.util.navigateTo
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PhoneNumberEntryViewModel(
  val repository: RegistrationRepository,
  private val parentState: StateFlow<RegistrationFlowState>,
  private val parentEventEmitter: (RegistrationFlowEvent) -> Unit,
  private val clock: () -> Long = { System.currentTimeMillis() }
) : EventDrivenViewModel<PhoneNumberEntryScreenEvents>(TAG) {

  companion object {
    private val TAG = Log.tag(PhoneNumberEntryViewModel::class)
    private const val PUSH_CHALLENGE_TIMEOUT_MS = 5000L
  }

  private val phoneNumberUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()
  private var formatter: AsYouTypeFormatter = phoneNumberUtil.getAsYouTypeFormatter("US")

  private val _state = MutableStateFlow(
    PhoneNumberEntryState(
      isLinkAndSyncAvailable = repository.isLinkAndSyncAvailable,
      isPhoneNumberlessRegistrationAvailable = repository.isPhoneNumberlessRegistrationAvailable
    )
  )
  val state: StateFlow<PhoneNumberEntryState> = _state.asStateFlow()

  init {
    setDefaultCountry()

    _state
      .onEach { Log.d(TAG, "[State] $it") }
      .launchIn(viewModelScope)

    onEvent(PhoneNumberEntryScreenEvents.Initialize)

    parentState
      .onEach { onEvent(PhoneNumberEntryScreenEvents.ParentStateChanged(it)) }
      .launchIn(viewModelScope)
  }

  fun setDefaultCountry() {
    val regionCode = repository.getDefaultRegionCode()
    formatter = phoneNumberUtil.getAsYouTypeFormatter(regionCode)
    _state.update {
      it.copy(
        regionCode = regionCode,
        countryName = E164Util.getRegionDisplayName(regionCode).orElse(""),
        countryEmoji = CountryUtils.countryToEmoji(regionCode),
        countryCode = PhoneNumberUtil.getInstance().getCountryCodeForRegion(regionCode).toString()
      ).withNumberValidity()
    }
  }

  override suspend fun processEvent(event: PhoneNumberEntryScreenEvents) {
    applyEvent(_state.value, event, parentEventEmitter) {
      _state.value = it
    }
  }

  @VisibleForTesting
  suspend fun applyEvent(state: PhoneNumberEntryState, event: PhoneNumberEntryScreenEvents, parentEventEmitter: (RegistrationFlowEvent) -> Unit, stateEmitter: (PhoneNumberEntryState) -> Unit) {
    when (event) {
      is PhoneNumberEntryScreenEvents.Initialize -> {
        stateEmitter(applyInitialize(state))
      }
      is PhoneNumberEntryScreenEvents.ParentStateChanged -> {
        stateEmitter(applyParentState(state, event.parentState))
      }
      is PhoneNumberEntryScreenEvents.CountryCodeChanged -> {
        stateEmitter(applyCountryCodeChanged(state, event.value))
      }
      is PhoneNumberEntryScreenEvents.CountrySelected -> {
        stateEmitter(applyCountrySelected(state, event.countryCode, event.regionCode, event.countryName, event.countryEmoji))
      }
      is PhoneNumberEntryScreenEvents.FullPhoneNumberEntered -> {
        val populatedState = applyFullPhoneNumberEntered(state, event.e164)
        stateEmitter(populatedState.copy(dialogs = populatedState.dialogs.copy(confirmNumber = event.autoConfirm && populatedState.isNumberPossible)))
      }
      is PhoneNumberEntryScreenEvents.NationalNumberChanged -> {
        stateEmitter(applyPhoneNumberChanged(state, event.oldValue, event.newValue))
      }
      is PhoneNumberEntryScreenEvents.NextClicked -> {
        val accountId = state.enteredAccountId
        if (accountId != null) {
          parentEventEmitter.navigateTo(RegistrationRoute.SignalLoginCredentialEntry(accountId))
        } else {
          val normalized = state.withNormalizedNationalNumber()
          stateEmitter(normalized.copy(dialogs = normalized.dialogs.copy(confirmNumber = true)))
        }
      }
      is PhoneNumberEntryScreenEvents.PhoneNumberCancelled -> {
        stateEmitter(state.copy(dialogs = state.dialogs.copy(confirmNumber = false)))
      }
      is PhoneNumberEntryScreenEvents.PhoneNumberConfirmed -> {
        var localState = state.copy(showSpinner = true, dialogs = state.dialogs.copy(confirmNumber = false))
        stateEmitter(localState)
        localState = applyPhoneNumberSubmitted(localState, parentEventEmitter)
        stateEmitter(localState.copy(showSpinner = false))
      }
      is PhoneNumberEntryScreenEvents.CountryPicker -> {
        state.also {
          parentEventEmitter.navigateTo(
            RegistrationRoute.CountryCodePicker(
              Country(state.countryEmoji, state.countryName, state.countryCode.toIntOrNull() ?: 0, state.regionCode).takeIf { state.countryName.isNotEmpty() }
            )
          )
        }
      }
      is PhoneNumberEntryScreenEvents.LinkDevice -> {
        parentEventEmitter.navigateTo(RegistrationRoute.LinkAccount())
      }
      is PhoneNumberEntryScreenEvents.RegisterWithoutNumber -> {
        parentEventEmitter.navigateTo(RegistrationRoute.SignalLoginPayment)
      }
      is PhoneNumberEntryScreenEvents.CaptchaCompleted -> {
        stateEmitter(applyCaptchaCompleted(state, event.token, parentEventEmitter))
      }
      is PhoneNumberEntryScreenEvents.LocalBackupRestoreCompleted -> {
        when (event.result) {
          is LocalBackupRestoreResult.Success -> {
            var localState = state.copy(showSpinner = true)
            stateEmitter(localState)
            localState = applyLocalBackupRestoreCompleted(localState, event.result.aep, parentEventEmitter)
            stateEmitter(localState.copy(showSpinner = false))
          }
          is LocalBackupRestoreResult.DeferredToSms -> {
            Log.i(TAG, "[LocalRestore] Backup belongs to a different account. Verifying the number over SMS before restoring.")
            var localState = state.copy(showSpinner = true)
            stateEmitter(localState)
            localState = applySessionBasedRegistration(localState, localState.sessionE164 ?: "+${localState.countryCode}${localState.nationalNumber}", parentEventEmitter)
            stateEmitter(localState.copy(showSpinner = false))
          }
          is LocalBackupRestoreResult.Canceled -> {
            parentEventEmitter(RegistrationFlowEvent.PendingRestoreOptionSelected(null))
          }
        }
      }
      is PhoneNumberEntryScreenEvents.NetworkErrorDialogDismissed -> {
        stateEmitter(state.copy(dialogs = state.dialogs.copy(networkError = false)))
      }
      is PhoneNumberEntryScreenEvents.UnknownErrorDialogDismissed -> {
        stateEmitter(state.copy(dialogs = state.dialogs.copy(unknownError = false)))
      }
      is PhoneNumberEntryScreenEvents.RateLimitedDialogDismissed -> {
        stateEmitter(state.copy(dialogs = state.dialogs.copy(rateLimitedRetryAfter = null)))
      }
      is PhoneNumberEntryScreenEvents.UnableToSendSmsDialogDismissed -> {
        stateEmitter(state.copy(dialogs = state.dialogs.copy(unableToSendSms = false)))
      }
      is PhoneNumberEntryScreenEvents.CouldNotRequestCodeWithSelectedTransportDialogDismissed -> {
        stateEmitter(state.copy(dialogs = state.dialogs.copy(couldNotRequestCodeWithSelectedTransport = false)))
      }
      is PhoneNumberEntryScreenEvents.InvalidPhoneNumberDialogDismissed -> {
        stateEmitter(state.copy(dialogs = state.dialogs.copy(invalidPhoneNumber = false)))
      }
    }
  }

  private suspend fun applyInitialize(inputState: PhoneNumberEntryState): PhoneNumberEntryState {
    var state = inputState.copy(restoredSvrCredentials = repository.getRestoredSvrCredentials())

    parentState.value.preExistingRegistrationData?.e164?.let { preExistingE164 ->
      if (state.formattedNumber.isEmpty()) {
        state = applyFullPhoneNumberEntered(state, preExistingE164)
      }
    }

    return state.copy(initialized = true)
  }

  private fun applyParentState(state: PhoneNumberEntryState, parentState: RegistrationFlowState): PhoneNumberEntryState {
    return state.copy(
      sessionE164 = parentState.sessionE164,
      sessionMetadata = parentState.sessionMetadata,
      smsVerificationCodeRequest = parentState.lastSmsVerificationCodeRequest,
      preExistingRegistrationData = parentState.preExistingRegistrationData,
      restoredSvrCredentials = state.restoredSvrCredentials.takeUnless { parentState.doNotAttemptRecoveryPassword } ?: emptyList(),
      pendingRestoreOption = parentState.pendingRestoreOption
    )
  }

  private fun applyCountrySelected(state: PhoneNumberEntryState, countryCode: Int, regionCode: String, countryName: String, countryEmoji: String): PhoneNumberEntryState {
    val countryCodeStr = countryCode.toString()
    if (countryCodeStr == state.countryCode && regionCode == state.regionCode) return state

    formatter = phoneNumberUtil.getAsYouTypeFormatter(regionCode)
    val formattedNumber = formatNumber(state.nationalNumber)

    return state.copy(
      countryCode = countryCodeStr,
      regionCode = regionCode,
      countryName = countryName,
      countryEmoji = countryEmoji,
      formattedNumber = formattedNumber
    ).withNumberValidity()
  }

  @VisibleForTesting
  fun applyFullPhoneNumberEntered(state: PhoneNumberEntryState, e164: String): PhoneNumberEntryState {
    val numberState = state.withoutAccountId()
    return redistributeFullPhoneNumber(numberState, e164) ?: numberState
  }

  private fun applyCountryCodeChanged(state: PhoneNumberEntryState, countryCode: String): PhoneNumberEntryState {
    // Only allow digits, max 3 characters
    val sanitized = countryCode.filter { it.isDigit() }.take(3)
    if (sanitized == state.countryCode) return state

    // Try to determine region from country code
    val regionCode = phoneNumberUtil.getRegionCodeForCountryCode(sanitized.toIntOrNull() ?: 0) ?: state.regionCode

    // Reset formatter for new region and reformat the existing national number
    formatter = phoneNumberUtil.getAsYouTypeFormatter(regionCode)
    val formattedNumber = formatNumber(state.nationalNumber)

    return state.copy(
      countryName = E164Util.getRegionDisplayName(regionCode).orElse(""),
      countryEmoji = CountryUtils.countryToEmoji(regionCode).takeIf { regionCode != "ZZ" } ?: "",
      countryCode = sanitized,
      regionCode = regionCode,
      formattedNumber = formattedNumber
    ).withNumberValidity()
  }

  private fun applyPhoneNumberChanged(state: PhoneNumberEntryState, oldValue: String, newValue: String): PhoneNumberEntryState {
    if (state.isPhoneNumberlessRegistrationAvailable) {
      AccountIdFormat.asAccountIdOrNull(newValue)?.let { accountId ->
        return state.copy(
          accountId = accountId,
          accountIdError = AccountIdFormat.validate(accountId),
          nationalNumber = "",
          formattedNumber = accountId,
          isNumberPossible = false,
          isNumberInvalid = false
        )
      }
    }

    // Extract only digits from the input
    val digitsOnly = newValue.filter { it.isDigit() }
    if (state.enteredAccountId == null && digitsOnly == state.nationalNumber) return state

    val numberState = state.withoutAccountId()

    // Only attempt to split out a country code / trunk prefix on a bulk entry (paste or autofill)
    if (insertedCharCount(oldValue, newValue) > 1) {
      tellomiFullNumberInserted(numberState, oldValue, newValue)?.let { return it }
      if (newValue.trimStart().startsWith("+")) {
        redistributeFullPhoneNumber(numberState, "+$digitsOnly")?.let { return it }
      } else {
        reinterpretNationalNumber(numberState, digitsOnly)?.let { return it }
      }
    }

    val formattedNumber = formatNumber(digitsOnly)

    return numberState.copy(
      nationalNumber = digitsOnly,
      formattedNumber = formattedNumber
    ).withNumberValidity()
  }

  private suspend fun applyPhoneNumberSubmitted(
    inputState: PhoneNumberEntryState,
    parentEventEmitter: (RegistrationFlowEvent) -> Unit
  ): PhoneNumberEntryState {
    var state = inputState.withNormalizedNationalNumber()
    val e164 = "+${state.countryCode}${state.nationalNumber}"

    // If the user selected a restore option before entering their phone number, navigate to the restore flow
    if (state.pendingRestoreOption != null) {
      parentEventEmitter(RegistrationFlowEvent.E164Chosen(e164))

      Log.i(TAG, "Pending restore option: ${state.pendingRestoreOption}. Navigating to appropriate screen.")

      when (state.pendingRestoreOption) {
        PendingRestoreOption.LocalBackup -> parentEventEmitter.navigateTo(RegistrationRoute.LocalBackupRestore(isPreRegistration = true))
        PendingRestoreOption.RemoteBackup -> parentEventEmitter.navigateTo(RegistrationRoute.EnterAepForRemoteBackupPreRegistration(e164))
      }

      return state
    }

    // If we're re-registering for the same number we used to be registered for, we should try to skip right to registration
    if (state.preExistingRegistrationData?.e164 == e164) {
      val masterKey = state.preExistingRegistrationData.aep.deriveMasterKey()
      val recoveryPassword = masterKey.deriveRegistrationRecoveryPassword()
      val registrationLock = masterKey.deriveRegistrationLock().takeIf { state.preExistingRegistrationData.registrationLockEnabled }

      when (val registerResult = repository.registerAccountWithRecoveryPassword(e164, recoveryPassword, registrationLock, skipDeviceTransfer = true, state.preExistingRegistrationData)) {
        is RequestResult.Success -> {
          Log.i(TAG, "[Register] Successfully re-registered using RRP from pre-existing data.")
          val (response, keyMaterial, aci) = registerResult.result

          parentEventEmitter(RegistrationFlowEvent.Registered(aci, keyMaterial.accountEntropyPool, response.storageCapable, phoneNumberless = response.e164 == null))

          if (response.storageCapable) {
            parentEventEmitter.navigateTo(RegistrationRoute.PinEntryForSvrRestore)
          } else {
            parentEventEmitter.navigateTo(RegistrationRoute.PinCreate)
          }
          return state
        }
        is RequestResult.NonSuccess -> {
          when (val error = registerResult.error) {
            is RegisterAccountError.SessionNotFoundOrNotVerified -> {
              error("[Register] Got told that our session could not be found when registering with RRP. We should never get into this state.")
            }
            is RegisterAccountError.DeviceTransferPossible -> {
              error("[Register] Got told a device transfer is possible. We should never get into this state.")
            }
            is RegisterAccountError.RegistrationLock -> {
              Log.w(TAG, "[Register] Reglocked. This implies that the user still had reglock enabled despite the pre-existing data not thinking it was.")
              parentEventEmitter(RegistrationFlowEvent.E164Chosen(e164))
              parentEventEmitter.navigateTo(
                RegistrationRoute.PinEntryForRegistrationLock(
                  timeRemaining = error.data.timeRemaining,
                  svrCredentials = error.data.svr2Credentials
                )
              )
              return state
            }
            is RegisterAccountError.RateLimited -> {
              Log.w(TAG, "[Register] Rate limited (retryAfter: ${error.retryAfter}).")
              return state.copy(dialogs = state.dialogs.copy(rateLimitedRetryAfter = error.retryAfter))
            }
            is RegisterAccountError.InvalidRequest -> {
              Log.w(TAG, "[Register] Invalid request when registering account with RRP. Ditching pre-existing data and continuing with session creation. Message: ${error.message}")
              parentEventEmitter(RegistrationFlowEvent.RecoveryPasswordInvalid)
              state = state.copy(preExistingRegistrationData = null)
            }
            is RegisterAccountError.RegistrationRecoveryPasswordIncorrect -> {
              Log.w(TAG, "[Register] Registration recovery password incorrect. Ditching pre-existing data and continuing with session creation. Message: ${error.message}")
              parentEventEmitter(RegistrationFlowEvent.RecoveryPasswordInvalid)
              state = state.copy(preExistingRegistrationData = null)
            }
            is RegisterAccountError.InvalidReceiptCredentialPresentation,
            RegisterAccountError.TotpMissingOrIncorrect,
            RegisterAccountError.PostQuantumRatchetRequired -> {
              Log.w(TAG, "[Register] Unexpected registration error: $error")
              return state.copy(dialogs = state.dialogs.copy(unknownError = true))
            }
          }
        }
        is RequestResult.RetryableNetworkError -> {
          Log.w(TAG, "[Register] Network error.", registerResult.networkError)
          return state.copy(dialogs = state.dialogs.copy(networkError = true))
        }
        is RequestResult.ApplicationError -> {
          Log.w(TAG, "[Register] Unknown error when registering account.", registerResult.cause)
          return state.copy(dialogs = state.dialogs.copy(unknownError = true))
        }
      }
    }

    return applySessionBasedRegistration(state, e164, parentEventEmitter)
  }

  /**
   * Handles the result of a pre-registration V1 local backup restore (V2 backups register before restoring instead).
   * If the restored database contained an AEP, attempts RRP-based registration with it.
   * Falls back to SVR check and SMS verification if RRP fails or no AEP is available.
   */
  private suspend fun applyLocalBackupRestoreCompleted(
    inputState: PhoneNumberEntryState,
    aep: AccountEntropyPool?,
    parentEventEmitter: (RegistrationFlowEvent) -> Unit
  ): PhoneNumberEntryState {
    val e164 = inputState.sessionE164 ?: "+${inputState.countryCode}${inputState.nationalNumber}"
    val state = inputState.copy()

    if (aep == null) {
      Log.i(TAG, "[LocalRestore] No AEP available (V1 backup). Proceeding to session-based registration.")
      return applySessionBasedRegistration(state, e164, parentEventEmitter)
    }

    parentEventEmitter(RegistrationFlowEvent.UserSuppliedAepSubmitted(aep))

    Log.i(TAG, "[LocalRestore] Attempting registration with RRP derived from restored AEP.")

    return attemptRegistrationWithRestoredAep(state, e164, aep, provideRegistrationLock = false, parentEventEmitter)
  }

  private suspend fun attemptRegistrationWithRestoredAep(
    state: PhoneNumberEntryState,
    e164: String,
    aep: AccountEntropyPool,
    provideRegistrationLock: Boolean,
    parentEventEmitter: (RegistrationFlowEvent) -> Unit
  ): PhoneNumberEntryState {
    val masterKey = aep.deriveMasterKey()
    val recoveryPassword = masterKey.deriveRegistrationRecoveryPassword()
    val registrationLock = masterKey.deriveRegistrationLock().takeIf { provideRegistrationLock }

    return when (val result = repository.registerAccountWithRecoveryPassword(e164, recoveryPassword, registrationLock, existingAccountEntropyPool = aep)) {
      is RequestResult.Success -> {
        Log.i(TAG, "[LocalRestore] Successfully registered using RRP from restored AEP.")
        val (response, keyMaterial, aci) = result.result

        parentEventEmitter(RegistrationFlowEvent.Registered(aci, keyMaterial.accountEntropyPool, response.storageCapable, phoneNumberless = response.e164 == null))

        if (response.storageCapable) {
          parentEventEmitter.navigateTo(RegistrationRoute.PinEntryForSvrRestore)
        } else {
          parentEventEmitter.navigateTo(RegistrationRoute.PinCreate)
        }
        state
      }
      is RequestResult.NonSuccess -> {
        when (val error = result.error) {
          is RegisterAccountError.RegistrationRecoveryPasswordIncorrect -> {
            Log.w(TAG, "[LocalRestore] RRP incorrect. Falling back to session-based registration.")
            parentEventEmitter(RegistrationFlowEvent.RecoveryPasswordInvalid)
            applySessionBasedRegistration(state, e164, parentEventEmitter)
          }
          is RegisterAccountError.InvalidRequest -> {
            Log.w(TAG, "[LocalRestore] Invalid request. Falling back to session-based registration. Message: ${error.message}")
            parentEventEmitter(RegistrationFlowEvent.RecoveryPasswordInvalid)
            applySessionBasedRegistration(state, e164, parentEventEmitter)
          }
          is RegisterAccountError.RegistrationLock -> {
            if (provideRegistrationLock) {
              Log.w(TAG, "[LocalRestore] Still registration locked after providing the reglock token derived from the AEP. Falling back to PIN entry.")
              parentEventEmitter.navigateTo(
                RegistrationRoute.PinEntryForRegistrationLock(
                  timeRemaining = error.data.timeRemaining,
                  svrCredentials = error.data.svr2Credentials
                )
              )
              state
            } else {
              Log.w(TAG, "[LocalRestore] Registration locked. Retrying with the reglock token derived from the AEP.")
              attemptRegistrationWithRestoredAep(state, e164, aep, provideRegistrationLock = true, parentEventEmitter)
            }
          }
          is RegisterAccountError.RateLimited -> {
            Log.w(TAG, "[LocalRestore] Rate limited (retryAfter: ${error.retryAfter}).")
            state.copy(dialogs = state.dialogs.copy(rateLimitedRetryAfter = error.retryAfter))
          }
          is RegisterAccountError.SessionNotFoundOrNotVerified -> {
            Log.w(TAG, "[LocalRestore] Session not found. Falling back to session-based registration.")
            applySessionBasedRegistration(state, e164, parentEventEmitter)
          }
          is RegisterAccountError.DeviceTransferPossible -> {
            Log.w(TAG, "[LocalRestore] Device transfer possible. Falling back to session-based registration.")
            applySessionBasedRegistration(state, e164, parentEventEmitter)
          }
          is RegisterAccountError.InvalidReceiptCredentialPresentation,
          RegisterAccountError.TotpMissingOrIncorrect,
          RegisterAccountError.PostQuantumRatchetRequired -> {
            Log.w(TAG, "[LocalRestore] Unexpected registration error: $error")
            state.copy(dialogs = state.dialogs.copy(unknownError = true))
          }
        }
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[LocalRestore] Network error.", result.networkError)
        state.copy(dialogs = state.dialogs.copy(networkError = true))
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[LocalRestore] Application error.", result.cause)
        state.copy(dialogs = state.dialogs.copy(unknownError = true))
      }
    }
  }

  /**
   * Checks SVR credentials, then creates a session and requests an SMS verification code.
   * This is the shared fallback path used by both phone number submission and local backup restore completion.
   */
  private suspend fun applySessionBasedRegistration(
    inputState: PhoneNumberEntryState,
    e164: String,
    parentEventEmitter: (RegistrationFlowEvent) -> Unit
  ): PhoneNumberEntryState {
    var state = inputState.copy()

    // Detect if we have valid SVR credentials for the current number. If so, we can go right to the PIN entry screen.
    // If they successfully restore the master key at that screen, we can use that to build the RRP and register without SMS.
    //
    // Tellomi（#999）：没有 SVR enclave 时这条路一步都走不通——它的终点是拿用户的 PIN 去 enclave 里
    // 换 master key。而服务端的 `POST v2/svr/auth/check` **只查凭证本身、不查有没有 enclave**，
    // 会回 200（iOS 上实测过，#964 就是被这个 200 骗去要 PIN 的），所以不能靠它自己失败。
    // 直接不进这条路，落到下面的会话验证码流程。
    if (state.restoredSvrCredentials.isNotEmpty() && repository.svrEnclaveAvailable) {
      when (val result = repository.checkSvrCredentials(e164, state.restoredSvrCredentials)) {
        is RequestResult.Success -> {
          Log.i(TAG, "[CheckSVRCredentials] Successfully validated credentials for $e164.")
          val credential = result.result.validCredential
          if (credential != null) {
            parentEventEmitter(RegistrationFlowEvent.E164Chosen(e164))
            parentEventEmitter.navigateTo(RegistrationRoute.PinEntryForSmsBypass(credential))
            return state
          }
        }
        is RequestResult.RetryableNetworkError -> {
          Log.w(TAG, "[CheckSVRCredentials] Network error. Ignoring error and continuing without RRP.", result.networkError)
        }
        is RequestResult.ApplicationError -> {
          Log.w(TAG, "[CheckSVRCredentials] Application error. Ignoring error and continuing without RRP.", result.cause)
        }
        is RequestResult.NonSuccess -> {
          when (val error = result.error) {
            is CheckSvrCredentialsError.InvalidRequest -> {
              Log.w(TAG, "[CheckSVRCredentials] Invalid request. Ignoring error and continuing without RRP. Message: ${error.message}")
            }

            CheckSvrCredentialsError.Unauthorized -> {
              Log.w(TAG, "[CheckSVRCredentials] Unauthorized. Ignoring error and continuing without RRP.")
            }
          }
        }
      }
    }

    // Detect if someone backed into this screen and entered a different number
    if (state.sessionE164 != null && state.sessionE164 != e164) {
      state = state.copy(sessionMetadata = null)
    }

    // If we recently requested an SMS for this same number, the server would just reject another request. Skip it and go straight to code entry.
    val nextSmsRequestAllowed = state.smsVerificationCodeRequest
    if (state.sessionMetadata != null && state.sessionE164 == e164 && nextSmsRequestAllowed?.e164 == e164 && clock() < nextSmsRequestAllowed.nextAllowedRequestTime) {
      Log.i(TAG, "[RequestVerificationCode] An SMS was already requested for this number and another isn't allowed for ${(nextSmsRequestAllowed.nextAllowedRequestTime - clock()).milliseconds}. Skipping the request and going straight to code entry.")
      parentEventEmitter(RegistrationFlowEvent.E164Chosen(e164))
      parentEventEmitter.navigateTo(RegistrationRoute.VerificationCodeEntry)
      return state
    }

    var sessionMetadata: SessionMetadata = state.sessionMetadata ?: when (val response = this@PhoneNumberEntryViewModel.repository.createSession(e164)) {
      is RequestResult.Success<SessionMetadata> -> {
        response.result
      }
      is RequestResult.NonSuccess<CreateSessionError> -> {
        return when (val error = response.error) {
          is CreateSessionError.InvalidRequest -> {
            Log.w(TAG, "[CreateSession] Invalid request when creating session, likely an invalid phone number. Message: ${error.message}")
            state.copy(dialogs = state.dialogs.copy(invalidPhoneNumber = true))
          }
          is CreateSessionError.RateLimited -> {
            Log.w(TAG, "[CreateSession] Rate limited (retryAfter: ${error.retryAfter}).")
            state.copy(dialogs = state.dialogs.copy(rateLimitedRetryAfter = error.retryAfter))
          }
        }
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[CreateSession] Network error.", response.networkError)
        return state.copy(dialogs = state.dialogs.copy(networkError = true))
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "Unknown error when creating session.", response.cause)
        return state.copy(dialogs = state.dialogs.copy(unknownError = true))
      }
    }

    state = state.copy(sessionMetadata = sessionMetadata)

    if (sessionMetadata.requestedInformation.contains("pushChallenge")) {
      Log.d(TAG, "Push challenge requested, waiting for token...")
      val pushChallengeToken = withTimeoutOrNull(PUSH_CHALLENGE_TIMEOUT_MS) {
        repository.awaitPushChallengeToken()
      }

      if (pushChallengeToken != null) {
        Log.d(TAG, "Received push challenge token, submitting...")
        val updateResult = repository.submitPushChallengeToken(sessionMetadata.id, pushChallengeToken)
        sessionMetadata = when (updateResult) {
          is RequestResult.Success -> {
            Log.d(TAG, "[SubmitPushChallengeToken] Successfully submitted push challenge token.")
            updateResult.result
          }
          is RequestResult.NonSuccess -> {
            if (updateResult.error is UpdateSessionError.SessionNotFound) {
              Log.w(TAG, "[SubmitPushChallengeToken] Session not found when submitting push challenge token.")
              parentEventEmitter(RegistrationFlowEvent.ResetState)
              return state
            }
            Log.w(TAG, "[SubmitPushChallengeToken] Failed to submit push challenge token: ${updateResult.error}")
            sessionMetadata
          }
          is RequestResult.RetryableNetworkError -> {
            Log.w(TAG, "[SubmitPushChallengeToken] Network error submitting push challenge token", updateResult.networkError)
            sessionMetadata
          }
          is RequestResult.ApplicationError -> {
            Log.w(TAG, "[SubmitPushChallengeToken] Application error submitting push challenge token", updateResult.cause)
            sessionMetadata
          }
        }
        state = state.copy(sessionMetadata = sessionMetadata)
      } else {
        Log.d(TAG, "[SubmitPushChallengeToken] Push challenge token not received within timeout")
      }
    }

    if (sessionMetadata.requestedInformation.contains("captcha")) {
      parentEventEmitter(RegistrationFlowEvent.SessionUpdated(sessionMetadata))
      parentEventEmitter(RegistrationFlowEvent.E164Chosen(e164))
      parentEventEmitter.navigateTo(RegistrationRoute.Captcha(sessionMetadata))
      return state
    }

    if (!sessionMetadata.allowedToRequestCode && sessionMetadata.requestedInformation.isEmpty()) {
      Log.w(TAG, "Not allowed to request code and no challenges requested. Unable to send SMS.")
      return state.copy(dialogs = state.dialogs.copy(unableToSendSms = true))
    }

    val verificationCodeResponse = this@PhoneNumberEntryViewModel.repository.requestVerificationCode(
      sessionMetadata.id,
      smsAutoRetrieveCodeSupported = repository.registerSmsListener(),
      transport = VerificationCodeTransport.SMS
    )

    sessionMetadata = when (verificationCodeResponse) {
      is RequestResult.Success<SessionMetadata> -> {
        Log.d(TAG, "[RequestVerificationCode] Successfully requested verification code.")
        parentEventEmitter(RegistrationFlowEvent.VerificationCodeRequested.from(e164, VerificationCodeTransport.SMS, verificationCodeResponse.result, clock()))
        verificationCodeResponse.result
      }
      is RequestResult.NonSuccess<RequestVerificationCodeError> -> {
        return when (val error = verificationCodeResponse.error) {
          is RequestVerificationCodeError.InvalidRequest -> {
            Log.w(TAG, "[RequestVerificationCode] Invalid request when requesting verification code. Message: ${error.message}")
            state.copy(dialogs = state.dialogs.copy(unknownError = true))
          }
          is RequestVerificationCodeError.RateLimited -> {
            Log.w(TAG, "[RequestVerificationCode] Rate limited (retryAfter: ${error.retryAfter}). Navigating to code entry so the user can see how long they have to wait.")
            navigateToCodeEntryAfterRateLimit(e164, error, parentEventEmitter)
            state.copy(sessionMetadata = error.session)
          }
          is RequestVerificationCodeError.CouldNotFulfillWithRequestedTransport -> {
            Log.w(TAG, "[RequestVerificationCode] Could not fulfill with requested transport.")
            state.copy(dialogs = state.dialogs.copy(couldNotRequestCodeWithSelectedTransport = true))
          }
          is RequestVerificationCodeError.InvalidSessionId -> {
            Log.w(TAG, "[RequestVerificationCode] Invalid session ID when requesting verification code.")
            parentEventEmitter(RegistrationFlowEvent.ResetState)
            state
          }
          is RequestVerificationCodeError.MissingRequestInformationOrAlreadyVerified -> {
            Log.w(TAG, "[RequestVerificationCode] Missing request information or already verified.")
            state.copy(dialogs = state.dialogs.copy(unableToSendSms = true))
          }
          is RequestVerificationCodeError.SessionNotFound -> {
            Log.w(TAG, "[RequestVerificationCode] Session not found when requesting verification code.")
            parentEventEmitter(RegistrationFlowEvent.ResetState)
            state
          }
          is RequestVerificationCodeError.ThirdPartyServiceError -> {
            Log.w(TAG, "[RequestVerificationCode] Third party service error.")
            state.copy(dialogs = state.dialogs.copy(unableToSendSms = true))
          }
        }
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[RequestVerificationCode] Network error.", verificationCodeResponse.networkError)
        return state.copy(dialogs = state.dialogs.copy(networkError = true))
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[RequestVerificationCode] Unknown error when creating session.", verificationCodeResponse.cause)
        return state.copy(dialogs = state.dialogs.copy(unknownError = true))
      }
    }

    state = state.copy(sessionMetadata = sessionMetadata)

    if (sessionMetadata.requestedInformation.contains("captcha")) {
      parentEventEmitter.navigateTo(RegistrationRoute.Captcha(sessionMetadata))
      return state
    }

    parentEventEmitter(RegistrationFlowEvent.SessionUpdated(sessionMetadata))
    parentEventEmitter(RegistrationFlowEvent.E164Chosen(e164))
    parentEventEmitter.navigateTo(RegistrationRoute.VerificationCodeEntry)
    return state
  }

  private suspend fun applyCaptchaCompleted(inputState: PhoneNumberEntryState, token: String, parentEventEmitter: (RegistrationFlowEvent) -> Unit): PhoneNumberEntryState {
    val e164 = "+${inputState.countryCode}${inputState.nationalNumber}"
    var state = inputState.copy()
    var sessionMetadata = state.sessionMetadata ?: return state.copy(dialogs = state.dialogs.copy(unknownError = true))

    val updateResult = this@PhoneNumberEntryViewModel.repository.submitCaptchaToken(sessionMetadata.id, token)

    sessionMetadata = when (updateResult) {
      is RequestResult.Success -> updateResult.result
      is RequestResult.NonSuccess -> {
        return when (val error = updateResult.error) {
          is UpdateSessionError.InvalidRequest -> {
            state.copy(dialogs = state.dialogs.copy(unknownError = true))
          }
          is UpdateSessionError.RejectedUpdate -> {
            state.copy(dialogs = state.dialogs.copy(unknownError = true))
          }
          is UpdateSessionError.SessionNotFound -> {
            Log.w(TAG, "[SubmitCaptcha] Session not found when submitting captcha token.")
            parentEventEmitter(RegistrationFlowEvent.ResetState)
            state
          }
          is UpdateSessionError.RateLimited -> {
            state.copy(dialogs = state.dialogs.copy(rateLimitedRetryAfter = error.retryAfter))
          }
        }
      }
      is RequestResult.RetryableNetworkError -> {
        return state.copy(dialogs = state.dialogs.copy(networkError = true))
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "Unknown error when submitting captcha.", updateResult.cause)
        return state.copy(dialogs = state.dialogs.copy(unknownError = true))
      }
    }

    state = state.copy(sessionMetadata = sessionMetadata)

    if (sessionMetadata.requestedInformation.contains("captcha")) {
      parentEventEmitter.navigateTo(RegistrationRoute.Captcha(sessionMetadata))
      return state
    }

    if (!sessionMetadata.allowedToRequestCode && sessionMetadata.requestedInformation.isEmpty()) {
      Log.w(TAG, "Not allowed to request code and no challenges requested after captcha. Unable to send SMS.")
      return state.copy(dialogs = state.dialogs.copy(unableToSendSms = true))
    }

    val verificationCodeResponse = this@PhoneNumberEntryViewModel.repository.requestVerificationCode(
      sessionId = sessionMetadata.id,
      smsAutoRetrieveCodeSupported = repository.registerSmsListener(),
      transport = VerificationCodeTransport.SMS
    )

    sessionMetadata = when (verificationCodeResponse) {
      is RequestResult.Success -> {
        parentEventEmitter(RegistrationFlowEvent.VerificationCodeRequested.from(e164, VerificationCodeTransport.SMS, verificationCodeResponse.result, clock()))
        verificationCodeResponse.result
      }
      is RequestResult.NonSuccess -> {
        return when (val error = verificationCodeResponse.error) {
          is RequestVerificationCodeError.InvalidRequest -> {
            state.copy(dialogs = state.dialogs.copy(unknownError = true))
          }
          is RequestVerificationCodeError.RateLimited -> {
            Log.w(TAG, "[RequestVerificationCode] Rate limited after captcha (retryAfter: ${error.retryAfter}). Navigating to code entry so the user can see how long they have to wait.")
            navigateToCodeEntryAfterRateLimit(e164, error, parentEventEmitter)
            state.copy(sessionMetadata = error.session)
          }
          is RequestVerificationCodeError.CouldNotFulfillWithRequestedTransport -> {
            state.copy(dialogs = state.dialogs.copy(couldNotRequestCodeWithSelectedTransport = true))
          }
          is RequestVerificationCodeError.InvalidSessionId -> {
            parentEventEmitter(RegistrationFlowEvent.ResetState)
            state
          }
          is RequestVerificationCodeError.MissingRequestInformationOrAlreadyVerified -> {
            Log.w(TAG, "When requesting verification code after captcha, missing request information or already verified.")
            state.copy(dialogs = state.dialogs.copy(unableToSendSms = true))
          }
          is RequestVerificationCodeError.SessionNotFound -> {
            parentEventEmitter(RegistrationFlowEvent.ResetState)
            state
          }
          is RequestVerificationCodeError.ThirdPartyServiceError -> {
            state.copy(dialogs = state.dialogs.copy(unableToSendSms = true))
          }
        }
      }
      is RequestResult.RetryableNetworkError -> {
        return state.copy(dialogs = state.dialogs.copy(networkError = true))
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "Unknown error when requesting verification code.", verificationCodeResponse.cause)
        return state.copy(dialogs = state.dialogs.copy(unknownError = true))
      }
    }

    parentEventEmitter(RegistrationFlowEvent.SessionUpdated(sessionMetadata))
    parentEventEmitter(RegistrationFlowEvent.E164Chosen(e164))
    parentEventEmitter.navigateTo(RegistrationRoute.VerificationCodeEntry)
    return state
  }

  /**
   * The server refused to send a code yet, but it told us how long we have to wait. Rather than show a dead-end rate
   * limit error, record the wait as the SMS request window and land on the code entry screen, where the resend
   * countdown communicates the wait and any previously-sent code can still be entered.
   */
  private fun navigateToCodeEntryAfterRateLimit(
    e164: String,
    error: RequestVerificationCodeError.RateLimited,
    parentEventEmitter: (RegistrationFlowEvent) -> Unit
  ) {
    parentEventEmitter(
      RegistrationFlowEvent.VerificationCodeRequested(
        e164 = e164,
        nextSmsAllowedTimestamp = clock() + error.retryAfter.inWholeMilliseconds,
        nextCallAllowedTimestamp = error.session.nextCall?.let { clock() + it.seconds.inWholeMilliseconds }
      )
    )
    parentEventEmitter(RegistrationFlowEvent.SessionUpdated(error.session))
    parentEventEmitter(RegistrationFlowEvent.E164Chosen(e164))
    parentEventEmitter.navigateTo(RegistrationRoute.VerificationCodeEntry)
  }

  private fun formatNumber(nationalNumber: String): String {
    formatter.clear()
    var result = ""
    for (digit in nationalNumber) {
      result = formatter.inputDigit(digit)
    }
    return result
  }

  /**
   * Attempts to interpret [e164] as a complete phone number and split it into the country code and national number
   * fields. Returns null if it can't be parsed into a usable number, leaving it to the caller to decide on a fallback.
   */
  private fun redistributeFullPhoneNumber(state: PhoneNumberEntryState, e164: String): PhoneNumberEntryState? {
    val parsedNumber = try {
      phoneNumberUtil.parse(e164, null)
    } catch (e: NumberParseException) {
      Log.w(TAG, "Failed to parse E164 used to populate phone number.", e)
      return null
    }

    if (parsedNumber.nationalNumber == 0L) {
      return null
    }

    val countryCode = parsedNumber.countryCode
    val nationalNumber = parsedNumber.nationalNumber.toString()
    val regionCode = phoneNumberUtil.getRegionCodeForNumber(parsedNumber) ?: phoneNumberUtil.getRegionCodeForCountryCode(countryCode)

    formatter = phoneNumberUtil.getAsYouTypeFormatter(regionCode)
    val formattedNumber = formatNumber(nationalNumber)

    return state.copy(
      countryCode = countryCode.toString(),
      regionCode = regionCode,
      countryName = E164Util.getRegionDisplayName(regionCode).orElse(""),
      countryEmoji = CountryUtils.countryToEmoji(regionCode).takeIf { regionCode != "ZZ" } ?: "",
      nationalNumber = nationalNumber,
      formattedNumber = formattedNumber
    ).withNumberValidity()
  }

  /**
   * Handles a national number that was pasted with a country code or a redundant trunk prefix still attached (e.g.
   * "16105550103" or "02079460958"), splitting the country code into its own field and normalizing the national
   * number. Returns null if [digitsOnly] should be treated as a plain national number.
   *
   * We do all of this because the system autofill likes put the entire phone number in the national number field,
   * generating an invalid number by default.
   */
  private fun reinterpretNationalNumber(state: PhoneNumberEntryState, digitsOnly: String): PhoneNumberEntryState? {
    val countryCode = state.countryCode
    if (countryCode.isEmpty() || digitsOnly.isEmpty()) return null

    val withCountryCode = try {
      phoneNumberUtil.parse("+$countryCode$digitsOnly", null)
    } catch (_: NumberParseException) {
      return null
    }

    return when (phoneNumberUtil.isPossibleNumberWithReason(withCountryCode)) {
      PhoneNumberUtil.ValidationResult.TOO_LONG -> {
        if (isPossibleFullNumber(digitsOnly)) redistributeFullPhoneNumber(state, "+$digitsOnly") else null
      }
      PhoneNumberUtil.ValidationResult.IS_POSSIBLE -> {
        if (withCountryCode.nationalNumber.toString() != digitsOnly) redistributeFullPhoneNumber(state, "+$countryCode$digitsOnly") else null
      }
      else -> null
    }
  }

  /** True if [digits] parses into a plausible international number (i.e. it appears to already contain a country code). */
  private fun isPossibleFullNumber(digits: String): Boolean {
    return try {
      val number = phoneNumberUtil.parse("+$digits", null)
      number.nationalNumber != 0L && phoneNumberUtil.isPossibleNumber(number)
    } catch (_: NumberParseException) {
      false
    }
  }

  /**
   * Tellomi（tellomi/tellomi#1213）：一次插进来的那一段本身就是完整号码时，整框换成它，不和框里已有的数字拼接；
   * 「0086…」按「+86…」处理。认三种写法：「+…」「00…」，以及框里已有数字时、以当前区号开头的一串（空框的这种上游已经会拆）。
   * 去掉前缀后还得是有效号码才算，免得把「0013 8000」这种本地号码片段读成 +1 38000。
   * 第三种写法另外要求去掉区号后的位数等于当前地区示例号码的有效位数：号码长度不固定的地区（DE、AT、FI 等），
   * 以区号数字开头的本地号码去掉「区号」后常常也是有效号码，只看有效会把它静默改成另一个号码。
   *
   * 插入段是按新旧两串的公共前后缀推断的，全选再粘时可能被截短（新旧号码尾部都是「000」）；
   * 所以插入段不算时再看整框：整框以 00 开头、去掉 00 是有效号码，也按「+」处理（上游对整框的「+」有同样的判断）。
   * 都不算就返回 null，交回上游的处理。iOS 的 RegistrationPhoneNumberInputView.tellomiFullPhoneNumber 是同一套规则。
   */
  private fun tellomiFullNumberInserted(state: PhoneNumberEntryState, oldValue: String, newValue: String): PhoneNumberEntryState? {
    val inserted = insertedText(oldValue, newValue).tellomiHalfWidth().filter { it.isDigit() || it == '+' }
    val digits = inserted.filter { it.isDigit() }
    val international = when {
      inserted.startsWith("+") -> digits
      inserted.startsWith("00") -> digits.drop(2)
      oldValue.any { it.isDigit() } && inserted == digits && isCallingCodeAndFullNationalNumber(state, digits) -> digits
      else -> null
    }
    if (international != null && isValidFullNumber(international)) {
      return redistributeFullPhoneNumber(state, "+$international")
    }

    val field = newValue.tellomiHalfWidth().filter { it.isDigit() || it == '+' }
    val fieldInternational = field.filter { it.isDigit() }.drop(2)
    return if (field.startsWith("00") && isValidFullNumber(fieldInternational)) redistributeFullPhoneNumber(state, "+$fieldInternational") else null
  }

  /**
   * Tellomi（taishi 审查 b20 不阻塞 1，两端对齐）：全角「＋」换成「+」，全角数字换成 ASCII 数字，别的原样留着。
   * isDigit() 虽然认全角数字，但「00」开头、以区号开头这两条是按 ASCII 比的，全角「＋」更是直接被丢掉。
   * iOS 的 RegistrationPhoneNumberInputView.tellomiHalfWidth 是同一条。
   */
  private fun String.tellomiHalfWidth(): String {
    return map { c ->
      when (c) {
        '\uFF0B' -> '+'
        in '\uFF10'..'\uFF19' -> '0' + (c - '\uFF10')
        else -> c
      }
    }.joinToString("")
  }

  /** [digits] 以当前区号开头，而且去掉区号后的位数等于当前地区示例号码的有效位数（见 [exampleNationalSignificantNumberLength]）。 */
  private fun isCallingCodeAndFullNationalNumber(state: PhoneNumberEntryState, digits: String): Boolean {
    val countryCode = state.countryCode
    if (countryCode.isEmpty() || !digits.startsWith(countryCode)) return false
    return digits.length - countryCode.length == exampleNationalSignificantNumberLength(state.regionCode)
  }

  /**
   * 示例号码的有效位数（不含长途前缀）：先取手机号的示例，没有再取「固话或手机」，和 iOS 上游 exampleNationalNumber 的取法相同。
   * 用有效位数而不是本国格式的位数：台湾本国格式带长途前缀 0（0912 345 678，10 位），有效位数是 9。
   */
  private fun exampleNationalSignificantNumberLength(regionCode: String): Int? {
    val example = phoneNumberUtil.getExampleNumberForType(regionCode, PhoneNumberUtil.PhoneNumberType.MOBILE)
      ?: phoneNumberUtil.getExampleNumberForType(regionCode, PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE)
      ?: return null
    return phoneNumberUtil.getNationalSignificantNumber(example).length
  }

  private fun isValidFullNumber(digits: String): Boolean {
    return try {
      phoneNumberUtil.isValidNumber(phoneNumberUtil.parse("+$digits", null))
    } catch (_: NumberParseException) {
      false
    }
  }

  /** The text that replaced the changed middle part of [old] to make [new]; see [insertedCharCount]. */
  private fun insertedText(old: String, new: String): String {
    val max = minOf(old.length, new.length)

    var prefix = 0
    while (prefix < max && old[prefix] == new[prefix]) {
      prefix++
    }

    var suffix = 0
    while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) {
      suffix++
    }

    return new.substring(prefix, (new.length - suffix).coerceAtLeast(prefix))
  }

  private fun insertedCharCount(old: String, new: String): Int {
    val max = minOf(old.length, new.length)

    var prefix = 0
    while (prefix < max && old[prefix] == new[prefix]) {
      prefix++
    }

    var suffix = 0
    while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) {
      suffix++
    }

    return (new.length - prefix - suffix).coerceAtLeast(0)
  }

  /**
   * Strips a redundant national trunk prefix from the national number (e.g. the leading 0 a Dutch user habitually
   * types in "0612345678"), which would otherwise produce a wrong E164 when naively concatenated with the country
   * code (+310612345678 instead of +31612345678). Leading zeros that are a significant part of the number (e.g.
   * Italian landlines) are preserved.
   */
  private fun PhoneNumberEntryState.withNormalizedNationalNumber(): PhoneNumberEntryState {
    if (countryCode.isEmpty() || nationalNumber.isEmpty()) {
      return this
    }

    val parsedNumber = try {
      phoneNumberUtil.parse("+$countryCode$nationalNumber", null)
    } catch (_: NumberParseException) {
      return this
    }

    val significantNumber = phoneNumberUtil.getNationalSignificantNumber(parsedNumber)
    if (significantNumber == nationalNumber || parsedNumber.countryCode.toString() != countryCode) {
      return this
    }

    return copy(
      nationalNumber = significantNumber,
      formattedNumber = formatNumber(significantNumber)
    ).withNumberValidity()
  }

  private fun PhoneNumberEntryState.withoutAccountId(): PhoneNumberEntryState {
    return copy(accountId = null, accountIdError = null)
  }

  /**
   * Recomputes [PhoneNumberEntryState.isNumberPossible] and [PhoneNumberEntryState.isNumberInvalid] from the current
   * country code and national number. Should be applied to any state that changes either of those fields.
   */
  private fun PhoneNumberEntryState.withNumberValidity(): PhoneNumberEntryState {
    if (countryCode.isEmpty() || nationalNumber.isEmpty()) {
      return copy(isNumberPossible = false, isNumberInvalid = false)
    }

    val parsedNumber = try {
      phoneNumberUtil.parse("+$countryCode$nationalNumber", null)
    } catch (_: NumberParseException) {
      return copy(isNumberPossible = false, isNumberInvalid = false)
    }

    val isNumberInvalid = when (phoneNumberUtil.isPossibleNumberWithReason(parsedNumber)) {
      PhoneNumberUtil.ValidationResult.TOO_LONG,
      PhoneNumberUtil.ValidationResult.INVALID_LENGTH,
      PhoneNumberUtil.ValidationResult.INVALID_COUNTRY_CODE -> true
      else -> false
    }
    val isNumberPossible = phoneNumberUtil.isPossibleNumber(parsedNumber)

    return if (this.isNumberInvalid != isNumberInvalid || this.isNumberPossible != isNumberPossible) {
      copy(isNumberPossible = isNumberPossible, isNumberInvalid = isNumberInvalid)
    } else {
      this
    }
  }

  class Factory(
    val repository: RegistrationRepository,
    val parentState: StateFlow<RegistrationFlowState>,
    val parentEventEmitter: (RegistrationFlowEvent) -> Unit
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return PhoneNumberEntryViewModel(repository, parentState, parentEventEmitter) as T
    }
  }
}
