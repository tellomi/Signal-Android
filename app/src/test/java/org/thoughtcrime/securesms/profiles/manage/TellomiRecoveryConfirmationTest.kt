/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.profiles.manage

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.TellomiUsernames
import org.thoughtcrime.securesms.R
import java.util.Locale

/**
 * ADR-0066 §6.2：恢复用户名要 confirm 一个用户名，服务端按换名算，会开始（或重新开始）冷却，所以恢复前的确认框也要说。
 * 句子与 Desktop#9 的 `EditUsernameModalBody__recover-confirmation--tellomi` 逐字相同（英文、简中、港、台、粤）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiRecoveryConfirmationTest {

  private val context: Application = ApplicationProvider.getApplicationContext()

  private fun render(locale: Locale): String {
    val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
    return context.createConfigurationContext(configuration).resources.getQuantityString(
      R.plurals.UsernameEditFragment__tellomi_recovery_confirmation,
      TellomiUsernames.RENAME_COOLDOWN_DAYS,
      TellomiUsernames.RENAME_COOLDOWN_DAYS
    )
  }

  @Test
  fun `recovery confirmation names the cooldown in every language we ship`() {
    assertThat(render(Locale.ENGLISH)).isEqualTo("Recovering your username will reset your existing QR code and link, and you won’t be able to change it again for 30 days. Are you sure?")
    assertThat(render(Locale.forLanguageTag("zh-CN"))).isEqualTo("恢复用户名会重置你现有的二维码和链接，而且之后 30 天内不能再改。确定要恢复吗？")
    assertThat(render(Locale.forLanguageTag("zh-HK"))).isEqualTo("恢復用戶名稱將重設你現有的二維碼和連結，之後 30 天內不能再更改。你確定嗎？")
    assertThat(render(Locale.forLanguageTag("zh-TW"))).isEqualTo("恢復用戶名稱將重設你現有的二維碼和連結，之後 30 天內不能再更改。你確定嗎？")
    assertThat(render(Locale.forLanguageTag("yue"))).isEqualTo("恢復用戶名稱會將你宜家用緊嘅二維碼同連結重設，之後 30 日內唔可以再改。你係咪確定？")
  }
}
