/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import org.signal.core.util.logging.Log

class ApkUpdateValues(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private val TAG = Log.tag(ApkUpdateValues::class.java)

    private const val DOWNLOAD_ID = "apk_update.download_id"
    private const val DIGEST = "apk_update.digest"
    private const val AUTO_UPDATE = "apk_update.auto_update"
    private const val LAST_SUCCESSFUL_CHECK = "apk_update.last_successful_check"
    private const val LAST_APK_UPLOAD_TIME = "apk_update.last_apk_upload_time"
    private const val PENDING_APK_UPLOAD_TIME = "apk_update.pending_apk_upload_time"
    private const val AVAILABLE_UPDATE_VERSION_NAME = "apk_update.available_update_version_name"
  }

  public override fun onFirstEverAppLaunch() = Unit
  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  val downloadId: Long by longValue(DOWNLOAD_ID, -2)
  val digest: ByteArray? get() = store.getBlob(DIGEST, null)
  var autoUpdate: Boolean by booleanValue(AUTO_UPDATE, true)
  var lastSuccessfulCheck: Long by longValue(LAST_SUCCESSFUL_CHECK, 0)

  /** The upload of the last APK we installed */
  var lastApkUploadTime: Long
    get() = getLong(LAST_APK_UPLOAD_TIME, 0)
    set(value) {
      Log.d(TAG, "Setting lastApkUploadTime to $value")
      store.beginWrite().putLong(LAST_APK_UPLOAD_TIME, value).commit()
    }

  /** The upload time of the APK we're trying to install */
  val pendingApkUploadTime: Long by longValue(PENDING_APK_UPLOAD_TIME, 0)

  /**
   * Tellomi（tellomi/tellomi#1138）：最近一次清单检查看到的、比当前安装更新的版本号（清单的 versionName）；
   * 清单里的版本不比当前新时为 null。只有官网版（MANAGES_APP_UPDATES）会写。「关于」页的「有新版本」读这里。
   */
  var availableUpdateVersionName: String? by stringValue(AVAILABLE_UPDATE_VERSION_NAME, null as String?)

  fun setDownloadAttributes(id: Long, digest: ByteArray?, apkUploadTime: Long) {
    Log.d(TAG, "Saving download attributes. id: $id, apkUploadTime: $apkUploadTime")

    store
      .beginWrite()
      .putLong(DOWNLOAD_ID, id)
      .putBlob(DIGEST, digest)
      .putLong(PENDING_APK_UPLOAD_TIME, apkUploadTime)
      .commit()
  }

  fun clearDownloadAttributes() {
    Log.d(TAG, "Clearing download attributes.")

    store
      .beginWrite()
      .putLong(DOWNLOAD_ID, -1)
      .putBlob(DIGEST, null)
      .putLong(PENDING_APK_UPLOAD_TIME, 0)
      .commit()
  }
}
