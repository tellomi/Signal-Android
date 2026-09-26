package org.thoughtcrime.securesms.profiles.manage

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import org.signal.core.util.Result
import org.signal.core.util.TellomiUsernames
import org.signal.core.util.UsernameUtil.InvalidReason
import org.signal.core.util.UsernameUtil.checkDiscriminator
import org.signal.core.util.UsernameUtil.checkNickname
import org.signal.core.util.isNotNullOrBlank
import org.signal.core.util.logging.Log
import org.signal.libsignal.usernames.Username
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository.ReserveFailure
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository.UsernameDeleteResult
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository.UsernameSetResult
import org.thoughtcrime.securesms.util.NetworkUtil
import org.thoughtcrime.securesms.util.rx.RxStore
import org.whispersystems.signalservice.api.util.Usernames
import java.util.concurrent.TimeUnit

/**
 * Manages the state around username updates.
 *
 *
 * A note on naming conventions:
 * Usernames are made up of two discrete components, a nickname and a discriminator. They are formatted thusly:
 *
 * nickname.discriminator
 */
internal class UsernameEditViewModel private constructor(private val mode: UsernameEditMode) : ViewModel() {
  private val events: PublishSubject<Event> = PublishSubject.create()
  private val disposables: CompositeDisposable = CompositeDisposable()

  private val uiState: RxStore<State> = RxStore(
    defaultValue = State(
      buttonState = ButtonState.SUBMIT_DISABLED,
      usernameStatus = UsernameStatus.NONE,
      usernameState = SignalStore.account.username?.let { UsernameState.Set(Username(it)) } ?: UsernameState.NoUsername
    ),
    scheduler = Schedulers.computation()
  )

  private val stateMachineStore = RxStore<UsernameEditStateMachine.State>(
    defaultValue = UsernameEditStateMachine.NoUserEntry(
      nickname = SignalStore.account.username?.split(Usernames.DELIMITER)?.first() ?: "",
      discriminator = SignalStore.account.username?.split(Usernames.DELIMITER)?.last() ?: "",
      stateModifier = UsernameEditStateMachine.StateModifier.SYSTEM
    ),
    scheduler = Schedulers.computation()
  )

  val usernameInputState: Flowable<UsernameEditStateMachine.State> = stateMachineStore.stateFlowable.observeOn(AndroidSchedulers.mainThread())

  init {
    disposables += stateMachineStore
      .stateFlowable
      .filter { it.stateModifier == UsernameEditStateMachine.StateModifier.USER }
      .debounce(NICKNAME_PUBLISHER_DEBOUNCE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      .subscribeBy(onNext = this::onUsernameStateUpdateDebounced)

    if (mode == UsernameEditMode.RECOVERY) {
      onNicknameUpdated(SignalStore.account.username?.split(Usernames.DELIMITER)?.first() ?: "")
      onDiscriminatorUpdated(SignalStore.account.username?.split(Usernames.DELIMITER)?.last() ?: "")
    }
  }

  override fun onCleared() {
    super.onCleared()
    disposables.clear()
    uiState.dispose()
  }

  fun onNicknameUpdated(nickname: String) {
    uiState.update { state: State ->
      if (nickname.isBlank() && SignalStore.account.username != null) {
        return@update State(
          buttonState = ButtonState.DELETE,
          usernameStatus = UsernameStatus.NONE,
          usernameState = UsernameState.NoUsername
        )
      }

      State(
        buttonState = ButtonState.SUBMIT_DISABLED,
        usernameStatus = UsernameStatus.NONE,
        usernameState = state.usernameState
      )
    }

    stateMachineStore.update {
      it.onUserChangedNickname(nickname)
    }
  }

  fun onDiscriminatorUpdated(discriminator: String) {
    uiState.update { state: State ->
      if (discriminator.isBlank() && SignalStore.account.username != null) {
        return@update State(
          buttonState = ButtonState.DELETE,
          usernameStatus = UsernameStatus.NONE,
          usernameState = UsernameState.NoUsername
        )
      }

      State(
        buttonState = ButtonState.SUBMIT_DISABLED,
        usernameStatus = UsernameStatus.NONE,
        usernameState = state.usernameState
      )
    }

    stateMachineStore.update {
      it.onUserChangedDiscriminator(discriminator)
    }
  }

  fun onUsernameSkipped() {
    SignalStore.uiHints.markHasSetOrSkippedUsernameCreation()
    events.onNext(Event.SKIPPED)
  }

  fun isSameUsernameRecovery(): Boolean {
    val usernameState = uiState.state.usernameState
    return mode == UsernameEditMode.RECOVERY &&
      usernameState is UsernameState.Reserved &&
      usernameState.requireUsername().username.lowercase() == SignalStore.account.username?.lowercase()
  }

  /**
   * @param userConfirmedResetOk True if the user is submitting this after confirming that they're ok with resetting their username via [Event.NEEDS_CONFIRM_RESET].
   */
  fun onUsernameSubmitted(userConfirmedResetOk: Boolean) {
    if (!NetworkUtil.isConnected(AppDependencies.application)) {
      events.onNext(Event.NETWORK_FAILURE)
      return
    }

    val editState = stateMachineStore.state
    val usernameState = uiState.state.usernameState
    val isCaseChange = isCaseChange(editState)

    if (!userConfirmedResetOk) {
      // Tellomi（ADR-0066 §6.2）：保留期内「删了再设」服务端也当改名，和换名一样先提醒（与 Desktop getUsernameSaveConfirmation 同一判法）
      when (saveConfirmation(isCaseChange, SignalStore.account.username, SignalStore.account.tellomiUsernameDeletedAt, System.currentTimeMillis())) {
        SaveConfirmation.CHANGE -> {
          events.onNext(Event.NEEDS_CONFIRM_RESET)
          return
        }
        SaveConfirmation.SET_AFTER_DELETE -> {
          events.onNext(Event.NEEDS_CONFIRM_SET_AFTER_DELETE)
          return
        }
        SaveConfirmation.NONE -> Unit
      }
    }

    if (usernameState !is UsernameState.Reserved && usernameState !is UsernameState.CaseChange) {
      Log.w(TAG, "Username was submitted, current state is invalid! State: ${usernameState.javaClass.simpleName}")
      uiState.update { it.copy(buttonState = ButtonState.SUBMIT_DISABLED, usernameStatus = UsernameStatus.NONE) }
      return
    }

    if (usernameState.requireUsername().username == SignalStore.account.username && mode != UsernameEditMode.RECOVERY) {
      Log.d(TAG, "Username was submitted, but was identical to the current username. Ignoring.")
      uiState.update { it.copy(buttonState = ButtonState.SUBMIT_DISABLED, usernameStatus = UsernameStatus.NONE) }
      return
    }

    val invalidReason: InvalidReason? = checkNicknameForEdit(usernameState.getNickname(), SignalStore.account.username, mode == UsernameEditMode.RECOVERY)
    if (invalidReason != null) {
      Log.w(TAG, "Username was submitted, but did not pass validity checks. Reason: $invalidReason")
      uiState.update { it.copy(buttonState = ButtonState.SUBMIT_DISABLED, usernameStatus = mapNicknameError(invalidReason)) }
      return
    }

    uiState.update { it.copy(buttonState = ButtonState.SUBMIT_LOADING, usernameStatus = UsernameStatus.NONE) }

    val usernameConfirmOperation: Single<UsernameSetResult> = if (isCaseChange) {
      UsernameRepository.updateUsernameDisplayForCurrentLink(usernameState.requireUsername())
    } else {
      val reservation = usernameState as UsernameState.Reserved
      UsernameRepository.confirmUsernameAndCreateNewLink(reservation.requireUsername())
    }

    disposables += usernameConfirmOperation.subscribe { result: UsernameSetResult ->
      val nickname = usernameState.getNickname()

      when (result) {
        UsernameSetResult.SUCCESS -> {
          SignalStore.uiHints.markHasSetOrSkippedUsernameCreation()
          uiState.update { State(ButtonState.SUBMIT_DISABLED, UsernameStatus.NONE, it.usernameState) }
          events.onNext(Event.SUBMIT_SUCCESS)
        }

        UsernameSetResult.USERNAME_INVALID -> {
          uiState.update { State(ButtonState.SUBMIT_DISABLED, UsernameStatus.INVALID_GENERIC, it.usernameState) }
          events.onNext(Event.SUBMIT_FAIL_INVALID)
          nickname?.let { onNicknameUpdated(it) }
        }

        UsernameSetResult.CANDIDATE_GENERATION_ERROR, UsernameSetResult.USERNAME_UNAVAILABLE -> {
          uiState.update { State(ButtonState.SUBMIT_DISABLED, UsernameStatus.TAKEN, it.usernameState) }
          events.onNext(Event.SUBMIT_FAIL_TAKEN)
          nickname?.let { onNicknameUpdated(it) }
        }

        UsernameSetResult.NETWORK_ERROR -> {
          uiState.update { State(ButtonState.SUBMIT, UsernameStatus.NONE, it.usernameState) }
          events.onNext(Event.NETWORK_FAILURE)
        }

        // Tellomi（#1106 第四刀）：CHANGE_COOLDOWN 只会出现在 reserve；confirm 回 429 照上游当限流
        UsernameSetResult.RATE_LIMIT_ERROR, UsernameSetResult.CHANGE_COOLDOWN -> {
          uiState.update { State(ButtonState.SUBMIT, UsernameStatus.NONE, it.usernameState) }
          events.onNext(Event.RATE_LIMIT_EXCEEDED)
        }
      }
    }
  }

  fun onUsernameDeleted() {
    uiState.update { state: State -> State(ButtonState.DELETE_LOADING, UsernameStatus.NONE, state.usernameState) }

    disposables += UsernameRepository.deleteUsernameAndLink().subscribe { result: UsernameDeleteResult ->
      when (result) {
        UsernameDeleteResult.SUCCESS -> {
          uiState.update { state: State -> State(ButtonState.DELETE_DISABLED, UsernameStatus.NONE, state.usernameState) }
          events.onNext(Event.DELETE_SUCCESS)
        }

        UsernameDeleteResult.NETWORK_ERROR -> {
          uiState.update { state: State -> State(ButtonState.DELETE, UsernameStatus.NONE, state.usernameState) }
          events.onNext(Event.NETWORK_FAILURE)
        }
      }
    }
  }

  fun getUiState(): Flowable<State> {
    return uiState.stateFlowable.observeOn(AndroidSchedulers.mainThread())
  }

  fun getEvents(): Observable<Event> {
    return events.observeOn(AndroidSchedulers.mainThread())
  }

  private fun isCaseChange(state: UsernameEditStateMachine.State): Boolean {
    if (mode == UsernameEditMode.RECOVERY) {
      return false
    }

    if (state is UsernameEditStateMachine.UserEnteredDiscriminator || state is UsernameEditStateMachine.UserEnteredNicknameAndDiscriminator) {
      return false
    }

    val newLower = state.nickname.lowercase()
    val oldLower = SignalStore.account.username?.split(Usernames.DELIMITER)?.firstOrNull()?.lowercase()
    // Tellomi（tellomi/tellomi#1106，ADR-0066）：「只改大小写 → 沿用原判别位」这条上游捷径，只在原判别位就是 01 时走。
    // 带 `.37` 这类旧后缀的账号输入同一个 nickname，要走正常 reserve 拿 `nickname.01`——否则老数据永远改不成不带数字的。
    val oldDiscriminator = SignalStore.account.username?.split(Usernames.DELIMITER)?.lastOrNull()

    return newLower == oldLower && oldDiscriminator == TellomiUsernames.FIXED_DISCRIMINATOR
  }

  /** Triggered when the debounced nickname event stream fires. */
  private fun onUsernameStateUpdateDebounced(state: UsernameEditStateMachine.State) {
    val nickname = state.nickname
    if (nickname.isBlank()) {
      return
    }

    if (state is UsernameEditStateMachine.NoUserEntry || state.stateModifier == UsernameEditStateMachine.StateModifier.SYSTEM) {
      return
    }

    val invalidReason: InvalidReason? = checkNicknameForEdit(nickname, SignalStore.account.username, mode == UsernameEditMode.RECOVERY)
    if (invalidReason != null) {
      uiState.update { uiState ->
        uiState.copy(
          buttonState = ButtonState.SUBMIT_DISABLED,
          usernameStatus = mapNicknameError(invalidReason)
        )
      }
      return
    }

    if (isCaseChange(state)) {
      val discriminator = SignalStore.account.username?.split(Usernames.DELIMITER)?.lastOrNull() ?: error("Unexpected case change, no discriminator!")
      uiState.update {
        State(
          buttonState = ButtonState.SUBMIT,
          usernameStatus = UsernameStatus.NONE,
          usernameState = UsernameState.CaseChange(Username("${state.nickname}${Usernames.DELIMITER}$discriminator"))
        )
      }

      stateMachineStore.update { s -> s.onSystemChangedDiscriminator(discriminator) }
      return
    }

    val isDiscriminatorSetByUser = keepsEnteredDiscriminator(
      discriminatorEnteredByUser = state is UsernameEditStateMachine.UserEnteredDiscriminator || state is UsernameEditStateMachine.UserEnteredNicknameAndDiscriminator,
      mode = mode,
      nickname = nickname,
      currentUsername = SignalStore.account.username
    )
    val discriminator = if (isDiscriminatorSetByUser) {
      state.discriminator
    } else {
      null
    }

    val discriminatorInvalidReason = checkDiscriminator(discriminator)
    if (isDiscriminatorSetByUser && discriminatorInvalidReason != null) {
      uiState.update { uiState ->
        uiState.copy(
          buttonState = ButtonState.SUBMIT_DISABLED,
          usernameStatus = mapDiscriminatorError(discriminatorInvalidReason)
        )
      }
      return
    }

    uiState.update { State(ButtonState.SUBMIT_DISABLED, UsernameStatus.NONE, UsernameState.Loading) }

    disposables += UsernameRepository.reserveUsername(nickname, discriminator).subscribe { result: Result<UsernameState.Reserved, ReserveFailure> ->
      result.either(
        onSuccess = { reserved: UsernameState.Reserved ->
          uiState.update { State(ButtonState.SUBMIT, UsernameStatus.NONE, reserved) }

          val d = reserved.getDiscriminator()
          if (!isDiscriminatorSetByUser && d != null) {
            stateMachineStore.update { s -> s.onSystemChangedDiscriminator(d) }
          }
        },
        onFailure = { failure: ReserveFailure ->
          when (failure.result) {
            UsernameSetResult.SUCCESS -> {
              throw AssertionError()
            }

            UsernameSetResult.USERNAME_INVALID -> {
              uiState.update { State(ButtonState.SUBMIT_DISABLED, UsernameStatus.INVALID_GENERIC, UsernameState.NoUsername) }
            }

            UsernameSetResult.USERNAME_UNAVAILABLE -> {
              val status = if (isDiscriminatorSetByUser) {
                UsernameStatus.DISCRIMINATOR_NOT_AVAILABLE
              } else {
                UsernameStatus.TAKEN
              }

              // Tellomi（tellomi/tellomi#1106）：用户没填判别位时 discriminator 是 null，上游这里会拼出 `kaixin.null`，
              // libsignal 的 Username(…) 当场抛 BaseUsernameException（Rx 回调里没接 → 闪退）。上游靠随机候选几乎走不到这里；
              // 判别位固定 01 之后「被占 / 命中保留词」都是 409，这里就成了常见路径。
              val attempted = discriminator ?: TellomiUsernames.FIXED_DISCRIMINATOR
              uiState.update {
                State(
                  ButtonState.SUBMIT_DISABLED,
                  status,
                  usernameState = UsernameState.CaseChange(Username("${state.nickname}${Usernames.DELIMITER}$attempted"))
                )
              }
            }

            UsernameSetResult.NETWORK_ERROR -> {
              uiState.update { State(ButtonState.SUBMIT, UsernameStatus.NONE, UsernameState.NoUsername) }
              events.onNext(Event.NETWORK_FAILURE)
            }

            UsernameSetResult.RATE_LIMIT_ERROR -> {
              uiState.update { State(ButtonState.SUBMIT, UsernameStatus.NONE, UsernameState.NoUsername) }
              events.onNext(Event.RATE_LIMIT_EXCEEDED)
            }

            // Tellomi（tellomi/tellomi#1106 第四刀，ADR-0066 §6.2）：冷却期内要别的名字——输入框下直接说「N 天后可以再改」，
            // 不再是泛泛的「尝试次数过多」（与 Desktop#2 同一句）
            UsernameSetResult.CHANGE_COOLDOWN -> {
              uiState.update {
                State(ButtonState.SUBMIT_DISABLED, UsernameStatus.CHANGE_COOLDOWN, UsernameState.NoUsername, renameCooldownDaysLeft = failure.renameCooldownDaysLeft)
              }
            }

            UsernameSetResult.CANDIDATE_GENERATION_ERROR -> {
              // TODO -- Retry
              uiState.update { State(ButtonState.SUBMIT_DISABLED, UsernameStatus.TAKEN, UsernameState.NoUsername) }
            }
          }
        }
      )
    }
  }

  data class State(
    @JvmField val buttonState: ButtonState,
    @JvmField val usernameStatus: UsernameStatus,
    @JvmField val usernameState: UsernameState,
    /** Tellomi（#1106 第四刀）：[UsernameStatus.CHANGE_COOLDOWN] 时还剩几天。 */
    @JvmField val renameCooldownDaysLeft: Int = 0
  )

  enum class UsernameStatus {
    NONE,
    TAKEN,
    TOO_SHORT,
    TOO_LONG,
    CANNOT_START_WITH_NUMBER,

    /** Tellomi（ADR-0066）：`_` 开头——Tellomi 的规则是字母开头。 */
    CANNOT_START_WITH_UNDERSCORE,
    INVALID_CHARACTERS,
    INVALID_GENERIC,
    DISCRIMINATOR_NOT_AVAILABLE,
    DISCRIMINATOR_TOO_SHORT,
    DISCRIMINATOR_TOO_LONG,
    DISCRIMINATOR_HAS_INVALID_CHARACTERS,
    DISCRIMINATOR_CANNOT_BE_00,
    DISCRIMINATOR_CANNOT_START_WITH_0,

    /** Tellomi（tellomi/tellomi#1106 第四刀）：30 天改名冷却期内，天数见 [State.renameCooldownDaysLeft]。 */
    CHANGE_COOLDOWN
  }

  enum class ButtonState {
    SUBMIT,
    SUBMIT_DISABLED,
    SUBMIT_LOADING,
    DELETE,
    DELETE_LOADING,
    DELETE_DISABLED
  }

  enum class Event {
    NETWORK_FAILURE,
    SUBMIT_SUCCESS,
    DELETE_SUCCESS,
    SUBMIT_FAIL_INVALID,
    SUBMIT_FAIL_TAKEN,
    SKIPPED,
    NEEDS_CONFIRM_RESET,

    /** Tellomi（ADR-0066 §6.2）：现在没有用户名，但保留期内删过一个；再设也算改名，先确认。 */
    NEEDS_CONFIRM_SET_AFTER_DELETE,
    RATE_LIMIT_EXCEEDED
  }

  /** Tellomi（ADR-0066 §6.2）：保存前弹哪种确认框。 */
  enum class SaveConfirmation {
    NONE,
    CHANGE,
    SET_AFTER_DELETE
  }

  class Factory(private val mode: UsernameEditMode) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(UsernameEditViewModel(mode))!!
    }
  }

  companion object {
    private val TAG = Log.tag(UsernameEditViewModel::class.java)

    private const val NICKNAME_PUBLISHER_DEBOUNCE_TIMEOUT_MILLIS: Long = 500

    /**
     * Tellomi（ADR-0066 §6.2）：保存前弹哪种确认框。只改大小写 → 不弹；已有用户名 → 换名提醒（上游原有，文案带冷却天数）；
     * 没有用户名、但保留期内删过一个 → 「删了再设也算改名」；第一次设 → 不弹。与 Desktop `getUsernameSaveConfirmation` 同一判法。
     */
    @VisibleForTesting
    @JvmStatic
    fun saveConfirmation(isCaseChange: Boolean, currentUsername: String?, deletedAtMillis: Long, nowMillis: Long): SaveConfirmation {
      return when {
        isCaseChange -> SaveConfirmation.NONE
        currentUsername.isNotNullOrBlank() -> SaveConfirmation.CHANGE
        TellomiUsernames.isWithinUsernameHold(deletedAtMillis, nowMillis) -> SaveConfirmation.SET_AFTER_DELETE
        else -> SaveConfirmation.NONE
      }
    }

    /**
     * Tellomi（tellomi/tellomi#1106 第二刀 b，ADR-0066 §六「首次设置 / 修改 / 重新认领 / 修复都只产 `.01`」）：
     * 编辑页没有数字栏（第二刀 a），「用户填的判别位」只可能来自修复模式 `init` 把旧用户名灌进状态机的那一次。
     * - 原样重新认领旧名（昵称只差大小写，hash 相同）：照旧用旧判别位，不算造新候选；
     * - 在修复页**改了昵称**：就是造新候选，不能沿用旧判别位（上游会去占 `新昵称.37`）——返回 false，
     *   走 `reserveUsername(nickname, null)` 拿 `.01`。
     */
    @VisibleForTesting
    @JvmStatic
    fun keepsEnteredDiscriminator(discriminatorEnteredByUser: Boolean, mode: UsernameEditMode, nickname: String, currentUsername: String?): Boolean {
      if (!discriminatorEnteredByUser) {
        return false
      }
      if (mode != UsernameEditMode.RECOVERY) {
        return true
      }
      val originalNickname = currentUsername?.split(Usernames.DELIMITER)?.firstOrNull() ?: return false
      return nickname.lowercase() == originalNickname.lowercase()
    }

    private fun mapNicknameError(invalidReason: InvalidReason): UsernameStatus {
      return when (invalidReason) {
        InvalidReason.TOO_SHORT -> UsernameStatus.TOO_SHORT
        InvalidReason.TOO_LONG -> UsernameStatus.TOO_LONG
        InvalidReason.STARTS_WITH_NUMBER -> UsernameStatus.CANNOT_START_WITH_NUMBER
        InvalidReason.STARTS_WITH_UNDERSCORE -> UsernameStatus.CANNOT_START_WITH_UNDERSCORE
        InvalidReason.INVALID_CHARACTERS -> UsernameStatus.INVALID_CHARACTERS
        InvalidReason.INVALID_NUMBER,
        InvalidReason.INVALID_NUMBER_00,
        InvalidReason.INVALID_NUMBER_PREFIX_0 -> error("Unexpected reason $invalidReason")
      }
    }

    private fun mapDiscriminatorError(invalidReason: InvalidReason): UsernameStatus {
      return when (invalidReason) {
        InvalidReason.TOO_SHORT -> UsernameStatus.DISCRIMINATOR_TOO_SHORT
        InvalidReason.TOO_LONG -> UsernameStatus.DISCRIMINATOR_TOO_LONG
        InvalidReason.INVALID_CHARACTERS -> UsernameStatus.DISCRIMINATOR_HAS_INVALID_CHARACTERS
        InvalidReason.INVALID_NUMBER_00 -> UsernameStatus.DISCRIMINATOR_CANNOT_BE_00
        InvalidReason.INVALID_NUMBER_PREFIX_0 -> UsernameStatus.DISCRIMINATOR_CANNOT_START_WITH_0
        else -> UsernameStatus.INVALID_GENERIC
      }
    }

    /**
     * Tellomi（ADR-0066 §六；taishi 审 a3 与 Signal-Desktop#4 第三版，2026-09-24）：编辑页只对「新起的名字」收紧
     * 「字母开头」和「最长 20」（#1181）。只有与本机记录的原名 hash 相同、仍是用户已有的那个用户名时，才放过
     * [InvalidReason.STARTS_WITH_UNDERSCORE] 与 [InvalidReason.TOO_LONG]：
     * - 当前判别位是 `.01`、昵称只差大小写：走 [UsernameState.CaseChange]，不预约、不开始冷却；
     * - 修复模式原样认领当前的名字：昵称只差大小写，沿用旧判别位（`_kaixin.57` 认领回 `_kaixin.57`）。
     * 旧后缀迁到 `.01`（非修复模式下 `_kaixin.57` → `_kaixin.01`）换了 hash、服务端也按改名开始冷却，是一个新用户名，
     * 照 ADR-0066 §六「老数据：设置页照普通改名流程」按新名字的规则拦（与 Desktop#4 第三版同一条）。其余原因照旧拦。
     * 提交时（`onUsernameSubmitted`）与输入停顿时两处都用它。
     */
    @androidx.annotation.VisibleForTesting
    @JvmStatic
    fun checkNicknameForEdit(nickname: String?, currentUsername: String?, isRecovery: Boolean): InvalidReason? {
      val reason = checkNickname(nickname) ?: return null
      val currentParts = currentUsername?.split(Usernames.DELIMITER)
      val currentNickname = currentParts?.firstOrNull()
      val sameNickname = nickname != null && currentNickname != null && nickname.equals(currentNickname, ignoreCase = true)
      val keepsCurrentUsername = sameNickname && (isRecovery || currentParts?.getOrNull(1) == TellomiUsernames.FIXED_DISCRIMINATOR)
      return if (keepsCurrentUsername && (reason == InvalidReason.STARTS_WITH_UNDERSCORE || reason == InvalidReason.TOO_LONG)) null else reason
    }
  }
}
