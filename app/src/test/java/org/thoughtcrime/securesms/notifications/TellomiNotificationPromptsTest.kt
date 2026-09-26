/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.notifications

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#1218 F-01、#1112）：首屏通知说明页与「通知已关闭」横幅的判定（[TellomiNotificationPrompts]）。
 */
class TellomiNotificationPromptsTest {

  @Test
  fun primerShowsOnceOnAndroid13PlusWhenNotGranted() {
    assertThat(TellomiNotificationPrompts.shouldShowPrimer(sdkInt = 33, isPermissionGranted = false, hasSeenPrimer = false)).isTrue()
    assertThat(TellomiNotificationPrompts.shouldShowPrimer(sdkInt = 36, isPermissionGranted = false, hasSeenPrimer = false)).isTrue()
  }

  @Test
  fun primerDoesNotShowAgainAfterContinueWasTapped() {
    assertThat(TellomiNotificationPrompts.shouldShowPrimer(sdkInt = 36, isPermissionGranted = false, hasSeenPrimer = true)).isFalse()
  }

  @Test
  fun primerDoesNotShowWhenAlreadyGranted() {
    // 例如 0.1.2 在注册时已经授过权的用户升级上来
    assertThat(TellomiNotificationPrompts.shouldShowPrimer(sdkInt = 36, isPermissionGranted = true, hasSeenPrimer = false)).isFalse()
  }

  @Test
  fun primerNeverShowsBeforeAndroid13() {
    // Android 12 及以前没有 POST_NOTIFICATIONS，通知默认开着，没有可问的
    assertThat(TellomiNotificationPrompts.shouldShowPrimer(sdkInt = 32, isPermissionGranted = false, hasSeenPrimer = false)).isFalse()
    assertThat(TellomiNotificationPrompts.shouldShowPrimer(sdkInt = 23, isPermissionGranted = false, hasSeenPrimer = false)).isFalse()
  }

  @Test
  fun bannerWaitsForThePrimerOnAndroid13Plus() {
    // 新装还没进过首屏：通知关着是因为还没问，不能先挂「已关闭」
    assertThat(TellomiNotificationPrompts.shouldShowDisabledBanner(sdkInt = 33, areNotificationsEnabled = false, hasSeenPrimer = false)).isFalse()
  }

  @Test
  fun bannerShowsAfterThePrimerWasDeclined() {
    assertThat(TellomiNotificationPrompts.shouldShowDisabledBanner(sdkInt = 33, areNotificationsEnabled = false, hasSeenPrimer = true)).isTrue()
  }

  @Test
  fun bannerShowsBeforeAndroid13WhenTheUserTurnedNotificationsOff() {
    assertThat(TellomiNotificationPrompts.shouldShowDisabledBanner(sdkInt = 32, areNotificationsEnabled = false, hasSeenPrimer = false)).isTrue()
  }

  @Test
  fun bannerDisappearsOnceNotificationsAreOn() {
    assertThat(TellomiNotificationPrompts.shouldShowDisabledBanner(sdkInt = 36, areNotificationsEnabled = true, hasSeenPrimer = true)).isFalse()
    assertThat(TellomiNotificationPrompts.shouldShowDisabledBanner(sdkInt = 32, areNotificationsEnabled = true, hasSeenPrimer = false)).isFalse()
  }

  @Test
  fun primerAndBannerNeverShowTogether() {
    // 同一时刻只该有一个在说「通知」这件事
    for (sdk in listOf(23, 32, 33, 36)) {
      for (granted in listOf(false, true)) {
        for (seen in listOf(false, true)) {
          val primer = TellomiNotificationPrompts.shouldShowPrimer(sdk, isPermissionGranted = granted, hasSeenPrimer = seen)
          // 13+ 上「没授权」即「系统通知关着」；更早的系统用 granted 代表用户有没有在系统设置里关
          val banner = TellomiNotificationPrompts.shouldShowDisabledBanner(sdk, areNotificationsEnabled = granted, hasSeenPrimer = seen)
          assertThat(primer && banner, "sdk=$sdk granted=$granted seen=$seen").isFalse()
        }
      }
    }
  }
}
