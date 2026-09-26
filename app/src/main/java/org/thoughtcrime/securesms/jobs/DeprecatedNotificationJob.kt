/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import org.signal.core.util.ServiceUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.megaphone.ClientDeprecatedActivity
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.NotificationIds
import kotlin.time.Duration.Companion.days

/**
 * Notifies users that their build expired and redirects to the download page on click.
 */
class DeprecatedNotificationJob private constructor(parameters: Parameters) : Job(parameters) {
  companion object {
    const val KEY: String = "DeprecatedNotificationJob"
    private val TAG = Log.tag(DeprecatedNotificationJob::class.java)

    @JvmStatic
    fun enqueue() {
      AppDependencies.jobManager.add(DeprecatedNotificationJob())
    }

    /**
     * Tellomi（tellomi/tellomi#1138）：拆出来是为了用例能直接检查通知的文字和点按去向，不用搭起 NotificationChannels。
     */
    @VisibleForTesting
    fun buildNotification(context: Context, channelId: String): Notification {
      val intent: Intent

      if (BuildConfig.MANAGES_APP_UPDATES) {
        Log.d(TAG, "Showing deprecated notification for website APK")
        // Tellomi（taishi 审查 b14 包 8 不阻塞 2）：打开 App 内的更新页（阻断页 / 更新页本身会在清单检查失败时给「去官网下载」），
        // 和只读横幅一致，不直接去浏览器。
        intent = Intent(context, ClientDeprecatedActivity::class.java)
      } else {
        Log.d(TAG, "Showing deprecated notification for PlayStore")
        val packageName = context.packageName
        intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
      }

      val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
      return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.DeprecatedNotificationJob_update_signal))
        // Tellomi（#1143 需求 3.4，taishi 审查 b14 包 8 要改 1 的同类）：服务端要求更新（499）时版本并没有过期，
        // 和只读横幅、输入框一样说「需要更新」，不说上游的「已过期」。
        .setContentText(context.getString(R.string.TellomiUpdateRequired__notification_text))
        .setContentIntent(pendingIntent)
        .build()
    }
  }

  private constructor() : this(
    Parameters.Builder()
      .setQueue("DeprecatedNotificationJob")
      .setLifespan(7.days.inWholeMilliseconds)
      .setMaxAttempts(Parameters.UNLIMITED)
      .build()
  )

  override fun serialize(): ByteArray? = null

  override fun getFactoryKey(): String = KEY

  override fun run(): Result {
    if (NotificationChannels.getInstance().areNotificationsEnabled()) {
      ServiceUtil.getNotificationManager(context).notify(NotificationIds.APK_UPDATE_PROMPT_INSTALL, buildNotification(context, NotificationChannels.getInstance().APP_ALERTS))
    }

    return Result.success()
  }

  override fun onFailure() = Unit

  class Factory : Job.Factory<DeprecatedNotificationJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): DeprecatedNotificationJob {
      return DeprecatedNotificationJob(parameters)
    }
  }
}
