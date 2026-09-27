/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.account.logout

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import org.signal.appsettings.logout.LogoutAction
import org.signal.appsettings.logout.LogoutEvent
import org.signal.appsettings.logout.LogoutScreen
import org.signal.core.ui.compose.CollectActions
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.util.ServiceUtil
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.util.navigation.safeNavigate

/**
 * Tellomi（ADR-0072，tellomi/tellomi#1414）：设置 → 账号 →「退出登录」。执行 [LogoutAction] 里要 Activity 或导航图的动作。
 */
class LogoutFragment : ComposeFragment() {

  private val viewModel: LogoutViewModel by viewModels()

  override fun onResume() {
    super.onResume()
    viewModel.onEvent(LogoutEvent.ScreenResumed)
  }

  @Composable
  override fun FragmentContent() {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CollectActions(viewModel.actions) { action -> handleAction(action) }

    LogoutScreen(
      state = state,
      onEvent = viewModel::onEvent
    )
  }

  private fun handleAction(action: LogoutAction) {
    when (action) {
      LogoutAction.NavigateBack -> requireActivity().onBackPressedDispatcher.onBackPressed()
      LogoutAction.NavigateToScreenLock -> {
        // 屏幕锁定页在隐私设置的子图里，导航库不会从这里直接找进子图：先进隐私设置，再从那一页进屏幕锁定。返回时经过隐私设置。
        val navController = findNavController()
        navController.safeNavigate(R.id.action_logoutFragment_to_privacySettings)
        navController.safeNavigate(R.id.action_privacySettingsFragment_to_screenLockSettingsFragment)
      }
      LogoutAction.NavigateToManageStorage -> findNavController().safeNavigate(R.id.action_logoutFragment_to_storagePreferenceFragment)
      LogoutAction.NavigateToChangePhoneNumber -> findNavController().safeNavigate(R.id.action_logoutFragment_to_changePhoneNumberFragment)
      LogoutAction.NavigateToWelcome -> {
        // 清掉整个任务栈；MainActivity 的路由看到「已退出登录」，只会去欢迎页（带「上次登录」）。
        val intent = MainActivity.clearTop(requireContext()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        requireActivity().finishAffinity()
      }
      LogoutAction.WipeAllData -> {
        if (!ServiceUtil.getActivityManager(AppDependencies.application).clearApplicationUserData()) {
          viewModel.onEvent(LogoutEvent.DataWipeFailed)
        }
      }
      LogoutAction.ShowDataWipeFailed -> Toast.makeText(requireContext(), R.string.preferences_account_delete_all_data_failed, Toast.LENGTH_LONG).show()
    }
  }
}
