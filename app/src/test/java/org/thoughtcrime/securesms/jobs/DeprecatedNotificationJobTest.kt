/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.megaphone.ClientDeprecatedActivity

/**
 * Tellomi（tellomi/tellomi#1138，taishi 审查 b14 包 8 不阻塞 5）：「客户端已弃用」刚置上时的系统通知。
 * 点按去向随渠道不同：官网版（MANAGES_APP_UPDATES）打开 App 内更新页，其它渠道去商店。
 * 两个变体都要跑：testPlayProdDebugUnitTest 与 testWebsiteProdDebugUnitTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeprecatedNotificationJobTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the notification says an update is needed, not that the version expired`() {
    val notification = DeprecatedNotificationJob.buildNotification(context, "test-channel")

    assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
      .isEqualTo(context.getString(R.string.TellomiUpdateRequired__notification_text))
  }

  @Test
  fun `tapping it opens the in-app update page on website builds and the store otherwise`() {
    val intent = shadowOf(DeprecatedNotificationJob.buildNotification(context, "test-channel").contentIntent).savedIntent

    if (BuildConfig.MANAGES_APP_UPDATES) {
      assertThat(intent.component?.className).isEqualTo(ClientDeprecatedActivity::class.java.name)
    } else {
      assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
      assertThat(intent.data.toString()).isEqualTo("market://details?id=${context.packageName}")
    }
  }
}
