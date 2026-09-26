/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.ComposeBottomSheetDialogFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.horizontalGutters
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * Tellomi（tellomi/tellomi#1218 F-01、#1112）：注册完成后**第一次进首屏**时的通知说明页。
 *
 * 注册流程里不再要任何权限（#1112），通知挪到这里：先用一张说明讲清用途，点「继续」才出系统的授权框。
 * - **只有一个按钮「继续」**：不能有关闭 / 取消，不能叫「允许」，不画指向「允许」的提示（Apple HIG · Privacy；
 *   Android 同一页顺便满足工信部「申请时说明目的」）。所以 [isCancelable] = false：返回键、点外面、下滑都关不掉，没有拖动条。
 * - 只出一次：点了「继续」就记下（不管系统框里选什么）。拒了的由会话列表顶部的 `NotificationsDisabledBanner` 常驻提示。
 * - 只在需要运行时授权的系统（Android 13+）上出；更早的系统通知默认开着，没有可问的。
 *
 * Telegram reference（只看机制，一行没搬）：Android `DialogsActivity` 注册后**先直接弹系统框**，被拒才出
 * `NotificationPermissionDialog`（可划走，1/3/7/30 天后再问）；iOS `ApplicationContext` 登录后按分组实验出
 * `PermissionController` 闪屏页（按钮叫「允许」）。Tellomi 反过来：先说明、只一个「继续」、只问一次。
 */
class TellomiNotificationPrimerBottomSheet : ComposeBottomSheetDialogFragment() {

  companion object {
    private val TAG = Log.tag(TellomiNotificationPrimerBottomSheet::class.java)
    private const val FRAGMENT_TAG = "tellomi_notification_primer"

    /**
     * 首屏 `onResume` 里调用。推到下一帧再判：同一个 `onResume` 里别的底部弹层（恢复完成、重新链接提醒…）
     * 是异步提交的，等它们落进 FragmentManager 再看——已经有弹层在就这次不弹，下次回到首屏再说。
     */
    @JvmStatic
    fun showIfNeeded(activity: FragmentActivity) {
      if (!TellomiNotificationPrompts.shouldShowPrimer(Build.VERSION.SDK_INT, isPermissionGranted(activity), SignalStore.uiHints.hasSeenTellomiNotificationPrimer())) {
        return
      }

      activity.window.decorView.post {
        val fragmentManager = activity.supportFragmentManager
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || fragmentManager.isStateSaved) {
          return@post
        }
        if (fragmentManager.fragments.any { it is DialogFragment }) {
          Log.i(TAG, "Another sheet is showing; deferring the notification primer.")
          return@post
        }
        if (!TellomiNotificationPrompts.shouldShowPrimer(Build.VERSION.SDK_INT, isPermissionGranted(activity), SignalStore.uiHints.hasSeenTellomiNotificationPrimer())) {
          return@post
        }
        TellomiNotificationPrimerBottomSheet().show(fragmentManager, FRAGMENT_TAG)
      }
    }

    private fun isPermissionGranted(context: Context): Boolean {
      return Build.VERSION.SDK_INT < TellomiNotificationPrompts.RUNTIME_PERMISSION_SDK ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
  }

  private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    Log.i(TAG, "POST_NOTIFICATIONS granted: $granted")
    TellomiNotificationPrompts.onPermissionAnswered()
    dismissAllowingStateLoss()
  }

  override val peekHeightPercentage: Float = 1f

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    isCancelable = false
  }

  @Composable
  override fun SheetContent() {
    NotificationPrimerContent(onContinueClick = this::onContinueClicked)
  }

  private fun onContinueClicked() {
    SignalStore.uiHints.markHasSeenTellomiNotificationPrimer()
    if (Build.VERSION.SDK_INT >= TellomiNotificationPrompts.RUNTIME_PERMISSION_SDK) {
      requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      dismissAllowingStateLoss()
    }
  }
}

@Composable
private fun NotificationPrimerContent(onContinueClick: () -> Unit = {}) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .horizontalGutters()
      .padding(top = 40.dp, bottom = 54.dp)
  ) {
    Icon(
      painter = painterResource(R.drawable.symbol_bell_24),
      tint = MaterialTheme.colorScheme.primary,
      contentDescription = null,
      modifier = Modifier.size(64.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = stringResource(id = R.string.TellomiNotificationPrimer__title),
      style = MaterialTheme.typography.titleLarge,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = stringResource(id = R.string.TellomiNotificationPrimer__body),
      style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    )

    Spacer(modifier = Modifier.height(54.dp))

    Buttons.LargeTonal(
      onClick = onContinueClick,
      modifier = Modifier.widthIn(min = 220.dp)
    ) {
      Text(text = stringResource(R.string.TellomiNotificationPrimer__continue))
    }
  }
}

@DayNightPreviews
@Composable
private fun NotificationPrimerContentPreview() {
  Previews.Preview {
    NotificationPrimerContent()
  }
}
