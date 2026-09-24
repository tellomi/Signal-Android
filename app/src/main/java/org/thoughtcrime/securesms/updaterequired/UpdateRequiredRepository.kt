/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import org.signal.core.util.Util
import org.signal.core.util.getDownloadManager
import org.signal.core.util.logging.Log
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.apkupdate.ApkUpdateInstaller
import org.thoughtcrime.securesms.apkupdate.ApkUpdateNotifications
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.JobTracker
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobs.ApkUpdateJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import kotlin.time.Duration.Companion.seconds

/**
 * Tellomi（tellomi/tellomi#1138）：「必须更新」的判定入口。
 *
 * 服务端对本版本回 499（[org.thoughtcrime.securesms.net.RemoteDeprecationDetectorInterceptor]、
 * [org.thoughtcrime.securesms.net.SignalWebSocketHealthMonitor]）或构建过期
 * （[org.thoughtcrime.securesms.jobs.BuildExpirationConfirmationJob]）都会把 `misc.isClientDeprecated` 置真；
 * 装上新版本时 [org.thoughtcrime.securesms.migrations.ApplicationMigrations] 会清掉它。
 */
object UpdateRequired {

  @JvmStatic
  fun isRequired(): Boolean = SignalStore.misc.isClientDeprecated

  /**
   * 置真之后 [Util.getTimeUntilBuildExpiry] 恒为 0，分不出原因，所以这里直接比构建时间：
   * 过了构建有效期 = 版本过旧；否则是服务端拒绝（499 或远程配置宣布到期）。
   */
  fun reason(
    serverTime: Long = SignalStore.misc.estimatedServerTime,
    buildTimestamp: Long = BuildConfig.BUILD_TIMESTAMP
  ): UpdateRequiredState.Reason {
    return if (serverTime - buildTimestamp >= Util.getBuildLifespan()) {
      UpdateRequiredState.Reason.BUILD_EXPIRED
    } else {
      UpdateRequiredState.Reason.SERVER_REJECTED
    }
  }
}

/** DownloadManager 里一条下载的快照。 */
data class UpdateDownloadSnapshot(
  val downloadId: Long,
  val status: Status,
  val bytesSoFar: Long,
  /** 服务端没给长度时为 -1。 */
  val totalBytes: Long,
  /** 这条下载是否允许用流量（阻断页发起的是 true，上游后台检查排的是 false）。 */
  val allowsMetered: Boolean
) {
  enum class Status { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED }
}

/** 阻断页用到的系统能力，抽成接口方便单测。 */
interface UpdateRequiredRepository {
  val managesAppUpdates: Boolean

  fun reason(): UpdateRequiredState.Reason

  /** Android 8 起安装 APK 要「安装未知应用」这项特殊权限。 */
  fun canRequestPackageInstalls(): Boolean

  fun isOnline(): Boolean

  /** 同步跑一次必须档的清单检查（允许用流量下载）。检查失败或超时返回 false。 */
  @WorkerThread
  fun runRequiredUpdateCheck(): Boolean

  /** 最近一次清单检查看到的、比当前安装更新的版本号；没有则为 null。 */
  fun availableVersionName(): String?

  @WorkerThread
  fun currentDownload(): UpdateDownloadSnapshot?

  @WorkerThread
  fun install(downloadId: Long)
}

class DefaultUpdateRequiredRepository(private val context: Context) : UpdateRequiredRepository {

  companion object {
    private val TAG = Log.tag(DefaultUpdateRequiredRepository::class)

    /** 断网时清单检查会一直等网络；等这么久还没结果就让用户看到「重试」。 */
    private val CHECK_TIMEOUT = 30.seconds
  }

  override val managesAppUpdates: Boolean = BuildConfig.MANAGES_APP_UPDATES

  override fun reason(): UpdateRequiredState.Reason = UpdateRequired.reason()

  override fun canRequestPackageInstalls(): Boolean {
    return Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
  }

  override fun isOnline(): Boolean = NetworkConstraint.isMet(context)

  override fun runRequiredUpdateCheck(): Boolean {
    val result = AppDependencies.jobManager.runSynchronously(ApkUpdateJob(allowMeteredNetwork = true), CHECK_TIMEOUT.inWholeMilliseconds)
    Log.i(TAG, "Required update check finished: ${result.orElse(null)}")
    return result.orElse(null) == JobTracker.JobState.SUCCESS
  }

  override fun availableVersionName(): String? = SignalStore.apkUpdate.availableUpdateVersionName

  override fun currentDownload(): UpdateDownloadSnapshot? {
    val downloadId = SignalStore.apkUpdate.downloadId
    if (downloadId < 0) {
      return null
    }

    val allowsMetered = SignalStore.apkUpdate.downloadAllowsMetered

    // 部分 ROM 可以停用「下载管理器」，这时 query 会抛异常；阻断页不能因此一打开就崩，按下载失败处理
    // （给「重试」和「去官网下载」，taishi 审查 b14 不阻塞 7）。
    return runCatching {
      context.getDownloadManager().query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
        if (!cursor.moveToFirst()) {
          return@runCatching null
        }

        val status = when (cursor.requireInt(DownloadManager.COLUMN_STATUS)) {
          DownloadManager.STATUS_PENDING -> UpdateDownloadSnapshot.Status.PENDING
          DownloadManager.STATUS_RUNNING -> UpdateDownloadSnapshot.Status.RUNNING
          DownloadManager.STATUS_PAUSED -> UpdateDownloadSnapshot.Status.PAUSED
          DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadSnapshot.Status.SUCCESSFUL
          else -> UpdateDownloadSnapshot.Status.FAILED
        }

        UpdateDownloadSnapshot(
          downloadId = downloadId,
          status = status,
          bytesSoFar = cursor.requireLong(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
          totalBytes = cursor.requireLong(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
          allowsMetered = allowsMetered
        )
      }
    }.getOrElse { e ->
      Log.w(TAG, "Couldn't query the download manager.", e)
      UpdateDownloadSnapshot(downloadId, UpdateDownloadSnapshot.Status.FAILED, bytesSoFar = 0, totalBytes = -1, allowsMetered = allowsMetered)
    }
  }

  override fun install(downloadId: Long) {
    // 下载完成时后台已经发过一条「点此安装」通知；这里由用户在阻断页上直接装，不再需要它。
    ApkUpdateNotifications.dismissInstallPrompt(context)
    ApkUpdateInstaller.installOrPromptForInstall(context, downloadId, userInitiated = true)
  }
}
