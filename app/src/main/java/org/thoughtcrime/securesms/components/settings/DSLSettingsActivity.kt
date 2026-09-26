package org.thoughtcrime.securesms.components.settings

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.NavHostFragment
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import org.thoughtcrime.securesms.util.DynamicTheme
import org.signal.core.ui.R as CoreUiR

/**
 * The DSL API can be completely replaced by compose.
 * See ComposeFragment or ComposeBottomSheetFragment for an alternative to this API"
 */
open class DSLSettingsActivity : PassphraseRequiredActivity() {

  protected open val dynamicTheme: DynamicTheme = DynamicNoActionBarTheme()

  protected lateinit var navController: NavController
    private set

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    enableSettingsEdgeToEdge()

    setContentView(R.layout.dsl_settings_activity)

    if (savedInstanceState == null) {
      val navGraphId = resolveNavGraphId()
      if (navGraphId == -1) {
        throw IllegalStateException("No navgraph id was passed to activity")
      }

      val fragment: NavHostFragment = NavHostFragment.create(navGraphId, resolveStartBundle())

      supportFragmentManager.beginTransaction()
        .replace(R.id.nav_host_fragment, fragment)
        .commitNow()

      navController = fragment.navController
    } else {
      val fragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
      navController = fragment.navController
    }

    dynamicTheme.onCreate(this)

    val onBackPressed = OnBackPressed()
    onBackPressedDispatcher.addCallback(this, onBackPressed)
    if (usesSystemBackAtRoot) {
      // Tellomi（交互审计 A-22）：根页的返回交给系统，拖返回手势时才看得到上一页（预测性返回）；子页照旧由这里弹栈。
      navController.addOnDestinationChangedListener { controller, _, _ ->
        onBackPressed.isEnabled = controller.previousBackStackEntry != null
      }
    }
  }

  override fun onResume() {
    super.onResume()
    dynamicTheme.onResume(this)
  }

  override fun onNavigateUp(): Boolean {
    return if (!Navigation.findNavController(this, R.id.nav_host_fragment).popBackStack()) {
      finish()
      true
    } else {
      false
    }
  }

  private fun enableSettingsEdgeToEdge() {
    val navBarColor = ContextCompat.getColor(this, CoreUiR.color.signal_colorSurface2)
    val isDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    if (Build.VERSION.SDK_INT >= 26) {
      enableEdgeToEdge(
        navigationBarStyle = if (isDark) {
          SystemBarStyle.dark(navBarColor)
        } else {
          SystemBarStyle.light(navBarColor, navBarColor)
        }
      )
    } else {
      enableEdgeToEdge()
    }
  }

  /**
   * Tellomi：根页返回交给系统后，系统直接 finish，不再经过 [onNavigateUp]；
   * [onWillFinish] 挪到这里，两条路都会先调到它（例如设置页要在结束前 setResult）。
   */
  override fun finish() {
    onWillFinish()
    super.finish()
  }

  protected open fun onWillFinish() {}

  /**
   * 根页的返回是否交给系统（预测性返回）。有共享元素返场或自定义退场动画的页面改成 false，照旧由自己 finish。
   */
  protected open val usesSystemBackAtRoot: Boolean = true

  protected open fun resolveNavGraphId(): Int = intent.getIntExtra(ARG_NAV_GRAPH, -1)

  protected open fun resolveStartBundle(): Bundle? = intent.getBundleExtra(ARG_START_BUNDLE)

  companion object {
    const val ARG_NAV_GRAPH = "nav_graph"
    const val ARG_START_BUNDLE = "start_bundle"
  }

  private inner class OnBackPressed : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
      onNavigateUp()
    }
  }
}
