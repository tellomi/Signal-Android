/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.megaphone

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.main.EmptyMegaphoneActionController
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.SignalStoreRule

/**
 * Tellomi（tellomi/tellomi#1218 F-02）：卡片正显示着的时候来了第一个真人会话，找朋友三条路当场收起，不用等回到前台。
 *
 * 屏幕设宽：`LazyRow` 只组合看得见的卡片（每张 152dp），窄屏上后面几张本来就不存在，断言会假绿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w1200dp-h800dp")
class TellomiOnboardingMegaphoneTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val signalStore = SignalStoreRule()

  @Test
  fun `the three paths fold on screen as soon as the first real conversation shows up`() {
    SignalStore.onboarding.setShowFindByUsername(true)
    SignalStore.onboarding.setShowMyQrCode(true)
    SignalStore.onboarding.setShowInviteFriends(true)
    SignalStore.onboarding.setShowAddPhoto(true)

    composeTestRule.setContent {
      SignalTheme {
        OnboardingMegaphone(megaphoneActionController = EmptyMegaphoneActionController)
      }
    }

    composeTestRule.onNodeWithText("Search by username", useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithText("My QR code", useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithText("Invite friends", useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithText("Add a profile photo", useUnmergedTree = true).assertExists()

    TellomiOnboarding.onConversations(listOf(RecipientId.from(3)), selfId = RecipientId.from(1), officialAccountId = RecipientId.from(2))
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Search by username", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("My QR code", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("Invite friends", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("Add a profile photo", useUnmergedTree = true).assertExists()
  }
}
