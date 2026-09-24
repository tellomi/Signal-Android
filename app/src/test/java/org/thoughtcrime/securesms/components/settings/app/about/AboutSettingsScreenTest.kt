/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.about

import android.app.Application
import android.content.res.Configuration
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import java.util.Locale

/**
 * Tellomi（tellomi/tellomi#1165）：「关于 Tellomi」页的每一行都做它该做的事，地址一个字都不能错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AboutSettingsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private class RecordingCallbacks : AboutSettingsCallbacks {
    val events = mutableListOf<String>()
    override fun onCopy(text: String) { events += "copy:$text" }
    override fun onCheckForUpdates() { events += "update" }
    override fun onOpenUrl(url: String) { events += "open:$url" }
    override fun onWriteEmail(address: String) { events += "email:$address" }
    override fun onLicensesClick() { events += "licenses" }
  }

  private fun setContent(): RecordingCallbacks {
    val callbacks = RecordingCallbacks()
    composeTestRule.setContent {
      SignalTheme {
        AboutSettingsScreen(versionName = "0.1.2", versionCode = "175101", callbacks = callbacks)
      }
    }
    return callbacks
  }

  private fun clickRow(text: String) {
    composeTestRule.onNodeWithTag(AboutSettingsTestTags.LIST).performScrollToNode(hasText(text))
    composeTestRule.onNodeWithText(text).performClick()
  }

  @Test
  fun `tapping the version copies the full version`() {
    val callbacks = setContent()

    composeTestRule.onNodeWithText("Version 0.1.2 (build 175101)").performClick()

    assertThat(callbacks.events).containsExactly("copy:0.1.2 (175101)")
  }

  @Test
  fun `email rows copy on tap and write an email on long press`() {
    val callbacks = setContent()

    clickRow("support@tellomi.app")
    clickRow("privacy@tellomi.app")
    clickRow("abuse@tellomi.app")
    composeTestRule.onNodeWithText("support@tellomi.app").performTouchInput { longClick() }

    assertThat(callbacks.events).containsExactly(
      "copy:support@tellomi.app",
      "copy:privacy@tellomi.app",
      "copy:abuse@tellomi.app",
      "email:support@tellomi.app"
    )
  }

  @Test
  fun `the six legal documents open their official pages`() {
    val callbacks = setContent()

    clickRow("Terms of Service")
    clickRow("Privacy Policy")
    clickRow("Personal information we collect")
    clickRow("Third-party sharing and SDKs")
    clickRow("System permissions we use")
    clickRow("Complaints and reporting rules")

    assertThat(callbacks.events).containsExactly(
      "open:https://www.tellomi.app/legal/terms/",
      "open:https://www.tellomi.app/legal/privacy/",
      "open:https://www.tellomi.app/legal/pi-collection/",
      "open:https://www.tellomi.app/legal/third-party/",
      "open:https://www.tellomi.app/legal/permissions/",
      "open:https://www.tellomi.app/legal/complaints/"
    )
  }

  @Test
  fun `website, updates, licenses and source code`() {
    val callbacks = setContent()

    clickRow("Check for updates")
    clickRow("Official website")
    clickRow("Open source licenses")
    clickRow("Source code")

    assertThat(callbacks.events).containsExactly(
      "update",
      "open:https://www.tellomi.app/",
      "licenses",
      "open:https://github.com/tellomi"
    )
  }

  @Test
  fun `footer keeps the three attribution lines from the help page`() {
    setContent()

    composeTestRule.onNodeWithTag(AboutSettingsTestTags.LIST).performScrollToNode(hasText("Licensed under the", substring = true))
    composeTestRule.onNodeWithText("Copyright Signal Messenger\nModifications Copyright 重庆半格智能科技有限公司\nLicensed under the GNU AGPLv3").assertExists()
  }

  /** owner 2026-09-24：四种语言都写公司全称（一字不差，繁体也不转字）和 GNU AGPLv3，不再出现品牌名。 */
  @Test
  fun `footer names the company and the full license in every language`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale("zh", "HK"), Locale.TRADITIONAL_CHINESE)) {
      val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
      val footer = listOf(
        R.string.HelpFragment__copyright_signal_messenger,
        R.string.HelpFragment__modifications_copyright_tellomi,
        R.string.HelpFragment__licenced_under_the_agplv3
      ).joinToString("\n") { localized.getString(it) }

      assertThat(footer, locale.toString()).contains("Signal Messenger")
      assertThat(footer, locale.toString()).contains("重庆半格智能科技有限公司")
      assertThat(footer, locale.toString()).contains("GNU AGPLv3")
      assertThat(footer, locale.toString()).doesNotContain("Tellomi")
    }
  }
}
