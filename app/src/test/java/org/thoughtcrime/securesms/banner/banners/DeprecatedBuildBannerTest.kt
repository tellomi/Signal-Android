/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.banner.banners

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.megaphone.ClientDeprecatedActivity

/**
 * Tellomi（tellomi/tellomi#1138，taishi 审查 b14 包 8 可选项）：服务端要求更新和构建到期都走这条只读横幅，
 * 服务端要求更新时版本并没有过期，所以横幅说「需要更新才能继续收发消息」，不说上游的「已过期」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeprecatedBuildBannerTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the read-only banner says an update is needed, not that the version expired`() {
    composeTestRule.setContent {
      SignalTheme {
        DeprecatedBuildBanner().DisplayBanner(Unit, PaddingValues(0.dp))
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateRequired__read_only_banner)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.ExpiredBuildReminder_this_version_of_signal_has_expired)).assertDoesNotExist()
  }

  @Test
  fun `update now opens the in-app update page on website builds and the store otherwise`() {
    // taishi 审查 b14 包 8 不阻塞 5：官网版的路由在 playProd 变体里是死代码，这条要在 testWebsiteProdDebugUnitTest 里也跑。
    composeTestRule.setContent {
      SignalTheme {
        DeprecatedBuildBanner().DisplayBanner(Unit, PaddingValues(0.dp))
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.ExpiredBuildReminder_update_now)).performClick()

    val started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
    if (BuildConfig.MANAGES_APP_UPDATES) {
      assertThat(started.component?.className).isEqualTo(ClientDeprecatedActivity::class.java.name)
    } else {
      assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
      assertThat(started.data.toString()).isEqualTo("market://details?id=${context.packageName}")
    }
  }
}
