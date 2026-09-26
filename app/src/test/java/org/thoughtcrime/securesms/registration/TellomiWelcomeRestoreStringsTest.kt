/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R

/**
 * Tellomi（tellomi/tellomi#1210）：欢迎页「恢复或转移」两行的简体误译（「我可以用旧手机 / 我无法用旧手机 / 您可以在同一个设备上重新安装」）。
 *
 * 注册模块（feature/registration）里改过了，但 app 模块有同名同语言的键，合并资源时 app 模块优先，APK 里还是旧译文。
 * 这里用 app 的 R 取值，取到的就是 APK 里显示的那一份。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiWelcomeRestoreStringsTest {

  private val context: Application
    get() = ApplicationProvider.getApplicationContext()

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `simplified chinese restore actions use the corrected wording`() {
    assertAll {
      assertThat(context.getString(R.string.WelcomeFragment_restore_action_i_have_my_old_phone)).isEqualTo("我有旧手机")
      assertThat(context.getString(R.string.WelcomeFragment_restore_action_i_dont_have_my_old_phone)).isEqualTo("我没有旧手机")
      assertThat(context.getString(R.string.WelcomeFragment_restore_action_reinstalling)).isEqualTo("或者你是在同一台设备上重新安装 Tellomi")
    }
  }
}
