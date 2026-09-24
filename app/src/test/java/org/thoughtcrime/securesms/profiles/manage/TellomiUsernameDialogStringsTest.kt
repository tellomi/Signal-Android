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
 * 用户名相关确认框的 Tellomi 文案（ADR-0066 §6.2），英文、简中、港、台、粤五份都要解析得到、参数都填对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiUsernameDialogStringsTest {

  private val context: Application = ApplicationProvider.getApplicationContext()

  private fun resources(locale: Locale) = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }).resources

  private fun render(locale: Locale): String {
    return resources(locale).getQuantityString(
      R.plurals.UsernameEditFragment__tellomi_recovery_confirmation,
      TellomiUsernames.RENAME_COOLDOWN_DAYS,
      TellomiUsernames.RENAME_COOLDOWN_DAYS
    )
  }

  /** 参数顺序与 EditProfileFragment / UsernameEditFragment 的删除框相同。 */
  private fun renderDelete(locale: Locale): String {
    return resources(locale).getQuantityString(
      R.plurals.ManageProfileFragment__tellomi_delete_username_dialog_body,
      TellomiUsernames.RENAME_COOLDOWN_DAYS,
      "kaixin",
      TellomiUsernames.USERNAME_HOLD_DAYS,
      TellomiUsernames.RENAME_COOLDOWN_DAYS
    )
  }

  /**
   * 恢复要 confirm 一个用户名，服务端按换名算，会开始（或重新开始）冷却，所以恢复前的确认框也要说。
   * 句子与 Desktop#9 的 `EditUsernameModalBody__recover-confirmation--tellomi` 逐字相同。
   */
  @Test
  fun `recovery confirmation names the cooldown in every language we ship`() {
    assertThat(render(Locale.ENGLISH)).isEqualTo("Recovering your username will reset your existing QR code and link, and you won’t be able to change it again for 30 days. Are you sure?")
    assertThat(render(Locale.forLanguageTag("zh-CN"))).isEqualTo("恢复用户名会重置你现有的二维码和链接，而且之后 30 天内不能再改。确定要恢复吗？")
    assertThat(render(Locale.forLanguageTag("zh-HK"))).isEqualTo("恢復用戶名稱將重設你現有的二維碼和連結，之後 30 天內不能再更改。你確定嗎？")
    assertThat(render(Locale.forLanguageTag("zh-TW"))).isEqualTo("恢復用戶名稱將重設你現有的二維碼和連結，之後 30 天內不能再更改。你確定嗎？")
    assertThat(render(Locale.forLanguageTag("yue"))).isEqualTo("恢復用戶名稱會將你宜家用緊嘅二維碼同連結重設，之後 30 日內唔可以再改。你係咪確定？")
  }

  /**
   * owner 2026-09-24 晚第 9 条：删除框加一句通用提示，不管在不在冷却期都显示——30 天内改过用户名的，删除后只能改回原来的名字。
   * 三端逐字一致（iOS a6 同句，Desktop 半边交 taishi）。
   */
  @Test
  fun `delete confirmation says a recent rename leaves only the original name`() {
    assertThat(renderDelete(Locale.ENGLISH)).isEqualTo(
      "This will remove your username and disable your QR code and link. “kaixin” stays reserved for you for 30 days; if you set a username during that time, you won’t be able to change it again for 30 days. " +
        "If you changed your username in the last 30 days, you can only change back to your original username after deleting it. Are you sure?"
    )
    assertThat(renderDelete(Locale.forLanguageTag("zh-CN"))).isEqualTo(
      "这样做将会删除你的用户名，并使你的二维码和链接失效。“kaixin”会为你保留 30 天；这期间再设置用户名，之后 30 天内不能再改。如果你 30 天内改过用户名，删除后只能改回原来的名字。确定要删除吗？"
    )
    assertThat(renderDelete(Locale.forLanguageTag("zh-HK"))).isEqualTo(
      "這將刪除你的用戶名稱及停用你的二維碼和連結。「kaixin」會為你保留 30 天；這期間再設定用戶名稱，之後 30 天內不能再更改。如果你 30 天內改過用戶名稱，刪除後只能改回原來的名稱。你確定嗎？"
    )
    assertThat(renderDelete(Locale.forLanguageTag("zh-TW"))).isEqualTo(
      "這將刪除你的用戶名稱及停用你的二維碼和連結。「kaixin」會為你保留 30 天；這期間再設定用戶名稱，之後 30 天內不能再更改。如果你 30 天內改過用戶名稱，刪除後只能改回原來的名稱。你確定嗎？"
    )
    assertThat(renderDelete(Locale.forLanguageTag("yue"))).isEqualTo(
      "咁做嘅話，系統就會移除你嘅用戶名稱同埋停用你嘅二維碼同連結。「kaixin」會幫你保留 30 日；呢段時間再設定用戶名稱，之後 30 日內唔可以再改。如果你 30 日內改過用戶名稱，刪除之後只可以改返原本個名。你係咪確定？"
    )
  }
}
