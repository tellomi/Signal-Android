/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R

/**
 * Tellomi（tellomi/tellomi#1157「撞词」）：设备被登出不能写成「注销」。
 * 中文里「注销」是注销账号（#1157 的另一半：注销有 7 天冷静期、本机记录立即删除），把「此设备不再登录」也叫「注销」，
 * 用户会以为账号没了。这几句都是上游翻译带进来的，同步上游翻译时容易被换回去，这里钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "zh-rCN")
class TellomiLogoutWordingTest {

  private val deviceLoggedOutStrings = listOf(
    R.string.RelinkDevicesReminderFragment__the_devices_you_added_were_unlinked,
    R.string.RestoreActivity__no_longer_registered_title,
    R.string.UnauthorizedReminder_this_is_likely_because_you_registered_your_phone_number_with_Signal_on_a_different_device,
    R.string.EditProfileFragment_unregistered_dialog_body,
    R.string.ReregisterSignalDialog__message
  )

  @Test
  fun `device logged out is worded as logged out, not account deletion`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    for (id in deviceLoggedOutStrings) {
      val text = context.getString(id)
      assertThat(text).doesNotContain("注销")
      assertThat(text).contains("退出登录")
    }
  }
}
