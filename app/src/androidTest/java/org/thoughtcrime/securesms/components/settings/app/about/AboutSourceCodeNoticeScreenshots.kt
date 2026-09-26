/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.about

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.io.File

/**
 * Tellomi（owner 2026-09-25）：「关于」里去掉「源代码 github.com/tellomi」，「许可证」页最上面一句指向官网 /source。
 * 在设备上从设置首页一路点进去（真的导航、真的点击）：设置 →「关于 Tellomi」拉到底，没有 GitHub 那一行；
 * 点「开源许可」，许可证页最上面是那一句。带 `-e tellomiShots 1` 时截图到 `files/tellomi-shots/<屏宽>dp/`。
 */
@RunWith(AndroidJUnit4::class)
class AboutSourceCodeNoticeScreenshots {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  @get:Rule
  val composeTestRule = createEmptyComposeRule()

  @Test
  fun aboutHasNoGitHubRowAndTheLicensesPageStartsWithTheNotice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
      // 文案从 Activity 取：界面语言以 Activity 为准（跑的时候会把 App 语言设成中文）
      lateinit var activity: Activity
      scenario.onActivity { activity = it }
      val aboutTitle = activity.getString(R.string.AboutSettings__tellomi_about_tellomi)
      val licensesRow = activity.getString(R.string.AboutSettings__tellomi_open_source_licenses)
      val notice = activity.getString(R.string.AboutSettings__tellomi_source_code_notice)

      // 设置首页 →「关于 Tellomi」
      composeTestRule.waitUntil(15_000) { composeTestRule.hasNode { onAllNodes(hasScrollAction()).onFirst().fetchSemanticsNode() } }
      composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(aboutTitle))
      composeTestRule.onNodeWithText(aboutTitle).performClick()

      // 「关于」拉到底：「开源」一组只剩「开源许可」，没有 GitHub 那一行
      composeTestRule.waitUntil(10_000) { composeTestRule.hasNode { onAllNodesWithTag(AboutSettingsTestTags.LIST).onFirst().fetchSemanticsNode() } }
      val list = composeTestRule.onNodeWithTag(AboutSettingsTestTags.LIST)
      list.performScrollToNode(hasText("GNU AGPLv3", substring = true))
      val gitHubRow = runCatching { list.performScrollToNode(hasText("github", substring = true, ignoreCase = true)) }
      assertTrue("「关于」里没有 GitHub 那一行", gitHubRow.isFailure)
      list.performScrollToNode(hasText("GNU AGPLv3", substring = true))
      shot("about-bottom")

      // 点「开源许可」：许可证页最上面是那一句
      list.performScrollToNode(hasText(licensesRow))
      composeTestRule.onNodeWithText(licensesRow).performClick()
      composeTestRule.waitUntil(15_000) { composeTestRule.hasNode { onNodeWithText(notice).fetchSemanticsNode() } }
      val noticeTop = composeTestRule.onNodeWithText(notice).fetchSemanticsNode().boundsInRoot.top
      val quarterOfScreen = activity.resources.displayMetrics.heightPixels / 4f
      assertTrue("许可证页最上面是源代码那一句（top=$noticeTop，屏幕四分之一=$quarterOfScreen）", noticeTop < quarterOfScreen)
      shot("licenses-top")
    }
  }

  private fun ComposeTestRule.hasNode(find: ComposeTestRule.() -> Any): Boolean {
    return runCatching { find() }.isSuccess
  }

  private fun shot(name: String) {
    if (InstrumentationRegistry.getArguments().getString("tellomiShots") != "1") return
    composeTestRule.waitForIdle()
    Thread.sleep(600)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
    val dir = File(context.getExternalFilesDir(null), "tellomi-shots/${context.resources.configuration.screenWidthDp}dp").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
