package org.thoughtcrime.securesms.megaphone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.updaterequired.DefaultUpdateRequiredRepository
import org.thoughtcrime.securesms.updaterequired.UpdateRequired
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredScreen
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredScreenAction
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredScreenEvent
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredViewModel
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import org.thoughtcrime.securesms.util.PlayStoreUtil
import org.thoughtcrime.securesms.util.viewModel

/**
 * Shown when a users build fully expires. Controlled by [Megaphones.Event.CLIENT_DEPRECATED].
 *
 * Tellomi（tellomi/tellomi#1138，需求 app-update-and-version-policy 第 3.4 节）：改成「必须更新」阻断页 [UpdateRequiredScreen]。
 * - 上游的「不要更新」去掉了：必须档不可关闭，返回键只把 App 退到后台。
 * - 不继承 PassphraseRequiredActivity：页面上没有任何私人内容，应用锁没解开也能先更新。
 *   进入途径除了上游的全屏 megaphone，还有 PassphraseRequiredActivity 的路由（任何页面创建或回到前台时）。
 */
class ClientDeprecatedActivity : BaseActivity() {

  companion object {
    private val TAG = Log.tag(ClientDeprecatedActivity::class)

    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, ClientDeprecatedActivity::class.java)
    }
  }

  private val theme = DynamicNoActionBarTheme()
  private val viewModel: UpdateRequiredViewModel by viewModel { UpdateRequiredViewModel(DefaultUpdateRequiredRepository(application)) }

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    if (!UpdateRequired.isRequired()) {
      Log.w(TAG, "Opened while no update is required. Finishing.")
      finish()
      return
    }

    onBackPressedDispatcher.addCallback(this) {
      moveTaskToBack(true)
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.actions.collect { action ->
          when (action) {
            UpdateRequiredScreenAction.OpenInstallPermissionSettings -> openInstallPermissionSettings()
            UpdateRequiredScreenAction.OpenDownloadPage -> PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(this@ClientDeprecatedActivity)
          }
        }
      }
    }

    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()

      SignalTheme {
        UpdateRequiredScreen(
          state = state,
          onEvent = viewModel::onEvent
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
    viewModel.onEvent(UpdateRequiredScreenEvent.ScreenResumed)
  }

  private fun openInstallPermissionSettings() {
    if (Build.VERSION.SDK_INT < 26) {
      return
    }

    try {
      startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "No screen for the install-unknown-apps setting. Opening app details instead.", e)
      startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
  }
}
