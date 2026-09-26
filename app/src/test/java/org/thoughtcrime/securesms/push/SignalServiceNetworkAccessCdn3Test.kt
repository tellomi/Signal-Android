/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.keyvalue.MiscellaneousValues
import org.thoughtcrime.securesms.keyvalue.ProxyValues
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

/**
 * tellomi/tellomi#1055：[SignalServiceNetworkAccess.getConfiguration] 可能返回的每一份，cdn3 都恰好一个（RegionProfile 契约第五节第 3 条）。
 *
 * 构造的是真的 [SignalServiceNetworkAccess]，所以它的 init 断言也跑到了：以后有人照上游的形状重建规避配置（每个 CDN 3–6 个 URL），
 * 构造就会抛，这条用例跟着红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class SignalServiceNetworkAccessCdn3Test {

  // DeviceTransferBlockingInterceptor 的静态初始化会读 SignalStore.misc
  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(MiscellaneousValues::class))

  @Before
  fun setUp() {
    val proxy = mockk<ProxyValues>(relaxed = true)
    every { proxy.isProxyEnabled } returns false
    every { SignalStore.proxy } returns proxy
  }

  @Test
  fun `every configuration getConfiguration can return has exactly one cdn3`() {
    val networkAccess = SignalServiceNetworkAccess(ApplicationProvider.getApplicationContext())

    // 没号码、中国、美国，加上游默认走规避的 9 个国家（埃及、阿联酋、阿曼、卡塔尔、伊朗、古巴、乌兹别克斯坦、委内瑞拉、巴基斯坦）
    val numbers = listOf(
      null,
      "+8613800138000",
      "+12025550123",
      "+201001234567",
      "+971501234567",
      "+96892123456",
      "+97433123456",
      "+989121234567",
      "+5351234567",
      "+998901234567",
      "+584121234567",
      "+923001234567"
    )

    var checked = 0
    for (setting in SettingsValues.CensorshipCircumventionEnabled.entries) {
      every { signalStore.settings.censorshipCircumventionEnabled } returns setting
      for (e164 in numbers) {
        val configuration = networkAccess.getConfiguration(e164)
        assertThat(configuration.signalCdnUrlMap[3]!!.size, "cdn3 of $e164 / $setting").isEqualTo(1)
        checked++
      }
    }

    // 三种设置 × 12 个号码都真的取过一遍
    assertThat(checked).isEqualTo(36)
  }
}
