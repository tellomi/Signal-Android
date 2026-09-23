/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.banner.banners

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.banner.Banner
import org.thoughtcrime.securesms.banner.ui.compose.Action
import org.thoughtcrime.securesms.banner.ui.compose.DefaultBanner
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.TellomiNotificationPrompts

/**
 * Tellomi（tellomi/tellomi#1218 F-01）：系统通知关着时，会话列表顶部常驻一条「通知已关闭 · 去设置」。
 *
 * - 没有关闭按钮：在系统设置里打开通知后自己消失（会话列表每次 `onResume` 都让 BannerManager 重新判 [enabled]）。
 * - Android 13+ 上要等首屏说明页（`TellomiNotificationPrimerBottomSheet`）处理过才出：还没问过就挂「已关闭」，
 *   等于在说明页后面先把结论写了。更早的系统通知默认开着，关着就是用户自己在系统设置里关的。
 * - 上游的 NOTIFICATIONS megaphone（每 30 天「开启通知 / 以后再说」）在系统通知关着时让给这一条，见 `Megaphones`。
 */
class NotificationsDisabledBanner(private val context: Context) : Banner<Unit>() {

  companion object {
    private val TAG = Log.tag(NotificationsDisabledBanner::class)
  }

  override val enabled: Boolean
    get() = TellomiNotificationPrompts.shouldShowDisabledBanner(
      sdkInt = Build.VERSION.SDK_INT,
      areNotificationsEnabled = NotificationChannels.getInstance().areNotificationsEnabled(),
      hasSeenPrimer = SignalStore.uiHints.hasSeenTellomiNotificationPrimer()
    )

  override val dataFlow: Flow<Unit>
    get() = flowOf(Unit)

  @Composable
  override fun DisplayBanner(model: Unit, contentPadding: PaddingValues) {
    Banner(
      contentPadding = contentPadding,
      onOpenSettings = { openSystemNotificationSettings() }
    )
  }

  private fun openSystemNotificationSettings() {
    val intent = if (Build.VERSION.SDK_INT >= 26) {
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    try {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
      Log.w(TAG, "Unable to open system notification settings", e)
    }
  }
}

@Composable
private fun Banner(contentPadding: PaddingValues, onOpenSettings: () -> Unit = {}) {
  DefaultBanner(
    title = stringResource(id = R.string.NotificationsDisabledBanner__notifications_are_off),
    body = stringResource(id = R.string.NotificationsDisabledBanner__you_wont_be_alerted),
    actions = listOf(
      Action(R.string.NotificationsDisabledBanner__go_to_settings) {
        onOpenSettings()
      }
    ),
    paddingValues = contentPadding
  )
}

@DayNightPreviews
@Composable
private fun BannerPreview() {
  Previews.Preview {
    Banner(contentPadding = PaddingValues(0.dp))
  }
}
