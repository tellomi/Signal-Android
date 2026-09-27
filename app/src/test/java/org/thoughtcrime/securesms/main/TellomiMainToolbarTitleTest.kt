/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

/**
 * Tellomi：顶栏标题等于当前 Tab 的名字，和 iOS 一致，不再显示 App 名（owner 2026-09-26，两端差异清单 N2）。
 *
 * 没连上服务器时仍显示连接状态（#1218），连上后回到 Tab 名。
 *
 * 渲染的是整条 [MainToolbar]，不是单独的标题。头像在检查模式下只画占位，免得去读头像文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiMainToolbarTitleTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    every { SignalStore.labs } returns mockk(relaxed = true)
  }

  @Test
  fun `the title is the name of the current tab, not the app name`() {
    var destination by mutableStateOf(MainListRoute.Chats)
    setContent { MainToolbarState(destination = destination) }

    listOf(MainListRoute.Calls, MainListRoute.Chats, MainListRoute.Stories).forEach { route ->
      destination = route
      composeTestRule.onNodeWithText(context.getString(route.label), useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithText(context.getString(R.string.app_name), useUnmergedTree = true).assertDoesNotExist()
    }
  }

  @Test
  fun `while not connected the title shows the connection state, then the tab name again`() {
    var connectionTitle by mutableStateOf(ConnectionTitle.CONNECTING)
    setContent { MainToolbarState(destination = MainListRoute.Calls, connectionTitle = connectionTitle) }

    val connecting = context.getString(R.string.MainToolbar__tellomi_connecting)
    val calls = context.getString(MainListRoute.Calls.label)
    composeTestRule.onNodeWithText(connecting, useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText(calls, useUnmergedTree = true).assertDoesNotExist()

    connectionTitle = ConnectionTitle.NONE
    composeTestRule.onNodeWithText(calls, useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText(connecting, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `the chats tab is titled 聊天 in simplified chinese`() {
    setContent { MainToolbarState(destination = MainListRoute.Chats) }

    composeTestRule.onNodeWithText("聊天", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Tellomi", useUnmergedTree = true).assertDoesNotExist()
  }

  private fun setContent(state: () -> MainToolbarState) {
    composeTestRule.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        SignalTheme {
          MainToolbar(state = state(), callback = MainToolbarCallback.Empty)
        }
      }
    }
  }
}
