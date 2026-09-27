package org.thoughtcrime.securesms.keyvalue

import androidx.annotation.CheckResult
import androidx.annotation.VisibleForTesting
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.model.databaseprotos.LocalRegistrationMetadata
import org.thoughtcrime.securesms.database.model.databaseprotos.RestoreDecisionState
import org.thoughtcrime.securesms.dependencies.AppDependencies

class RegistrationValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    private val TAG = Log.tag(RegistrationValues::class)

    private const val REGISTRATION_COMPLETE = "registration.complete"
    private const val PIN_REQUIRED = "registration.pin_required"
    private const val HAS_UPLOADED_PROFILE = "registration.has_uploaded_profile"
    private const val HAS_DOWNLOADED_PROFILE = "registration.has_downloaded_profile"
    private const val SESSION_E164 = "registration.session_e164"
    private const val SESSION_ID = "registration.session_id"
    private const val LOCAL_REGISTRATION_DATA = "registration.local_registration_data"
    private const val RESTORE_METHOD_TOKEN = "registration.restore_method_token"
    private const val RESTORE_BACKUP_MEDIA_SIZE = "registration.restore_backup_media_size"
    private const val IS_OTHER_DEVICE_ANDROID = "registration.is_other_device_android"
    private const val RESTORING_ON_NEW_DEVICE = "registration.restoring_on_new_device"
    private const val IN_PROGRESS_DATA_BLOB_URI = "registration.in_progress_data_blob_uri"
    private const val TELLOMI_IS_REREGISTRATION = "registration.tellomi.is_reregistration"

    @VisibleForTesting
    const val RESTORE_DECISION_STATE = "registration.restore_decision_state.2"
  }

  @Synchronized
  public override fun onFirstEverAppLaunch() {
    store
      .beginWrite()
      .putBoolean(HAS_UPLOADED_PROFILE, false)
      .putBoolean(HAS_DOWNLOADED_PROFILE, false)
      .putBoolean(REGISTRATION_COMPLETE, false)
      .putBoolean(PIN_REQUIRED, true)
      .putBlob(RESTORE_DECISION_STATE, RestoreDecisionState.Start.encode())
      .commit()
  }

  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  @Synchronized
  fun clearRegistrationComplete() {
    store
      .beginWrite()
      .putBoolean(HAS_UPLOADED_PROFILE, false)
      .putBoolean(REGISTRATION_COMPLETE, false)
      .putBoolean(PIN_REQUIRED, true)
      .putBoolean(TELLOMI_IS_REREGISTRATION, false)
      .commit()
  }

  @Synchronized
  fun markRegistrationComplete() {
    store
      .beginWrite()
      .putBoolean(REGISTRATION_COMPLETE, true)
      .putBoolean(TELLOMI_IS_REREGISTRATION, false)
      .commit()
  }

  /**
   * Tellomi（tellomi/tellomi#1266）：这次注册是不是**重新注册**（注册回包的 `reregistration`，服务端看的是这个号码之前有没有账号）。
   * 为真时注册资料页不显示用户名框：旧用户名在服务端是本账号的待认领保留，本机又不知道它，在注册那一刻主动请用户填，
   * 冷却外一填就等于换名、丢了原名，冷却内又会被 429 误导。交给设置页。
   *
   * 进行中的注册数据在注册完成时整个删掉，`needsUsernameRestore` 又会被第一次完成注册就排上的 `ReclaimUsernameAndLinkJob` 清掉，
   * 都靠不住，所以另存一个：`applyAccountData` 写，标完成（或重新开始注册）时清掉。
   */
  @get:Synchronized
  @set:Synchronized
  var isTellomiReRegistration: Boolean by booleanValue(TELLOMI_IS_REREGISTRATION, false)

  @CheckResult
  @Synchronized
  fun pinWasRequiredAtRegistration(): Boolean {
    return store.getBoolean(PIN_REQUIRED, false)
  }

  @get:Synchronized
  @get:CheckResult
  val isRegistrationComplete: Boolean by booleanValue(REGISTRATION_COMPLETE, true)

  var localRegistrationMetadata: LocalRegistrationMetadata? by protoValue(LOCAL_REGISTRATION_DATA, LocalRegistrationMetadata.ADAPTER)

  @get:JvmName("hasUploadedProfile")
  var hasUploadedProfile: Boolean by booleanValue(HAS_UPLOADED_PROFILE, true)

  @get:JvmName("hasDownloadedProfile")
  var hasDownloadedProfile: Boolean by booleanValue(HAS_DOWNLOADED_PROFILE, true)

  var sessionId: String? by stringValue(SESSION_ID, null)
  var sessionE164: String? by stringValue(SESSION_E164, null)

  /** URI of the encrypted [org.signal.core.util.contentproviders.BlobProvider] blob holding the in-progress registration data, if any. */
  var inProgressRegistrationDataBlobUri: String? by stringValue(IN_PROGRESS_DATA_BLOB_URI, null)

  var isOtherDeviceAndroid: Boolean by booleanValue(IS_OTHER_DEVICE_ANDROID, false)
  var restoreMethodToken: String? by stringValue(RESTORE_METHOD_TOKEN, null)
  var restoreBackupMediaSize: Long by longValue(RESTORE_BACKUP_MEDIA_SIZE, 0L)

  @get:JvmName("isRestoringOnNewDevice")
  var restoringOnNewDevice: Boolean by booleanValue(RESTORING_ON_NEW_DEVICE, false)

  var restoreDecisionState: RestoreDecisionState
    get() = store.getBlob(RESTORE_DECISION_STATE, null)?.let { RestoreDecisionState.ADAPTER.decode(it) } ?: RestoreDecisionState.Skipped
    set(newValue) {
      if (isRegistrationComplete || restoreDecisionState.isTerminal) {
        Log.w(TAG, "Cannot change initial restore decision state. complete: $isRegistrationComplete terminal: ${restoreDecisionState.isTerminal}")
      } else {
        Log.v(TAG, "Restore decision set: $newValue", Throwable())
        store.beginWrite()
          .putBlob(RESTORE_DECISION_STATE, newValue.encode())
          .apply()

        if (newValue.isTerminal) {
          AppDependencies.incomingMessageObserver.notifyRestoreDecisionMade()
        }
      }
    }
}
