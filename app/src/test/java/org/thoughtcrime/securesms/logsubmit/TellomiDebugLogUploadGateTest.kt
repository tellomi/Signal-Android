/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.logsubmit

import android.app.Application
import assertk.assertThat
import assertk.assertions.contains
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.keyvalue.MiscellaneousValues
import org.thoughtcrime.securesms.net.DeviceTransferBlockingInterceptor
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

/**
 * tellomi/tellomi#1133（Android #110 审查记的漏网）：跨境同意之前一个请求都不发。
 *
 * 调试日志上传用的是自建的 OkHttpClient，原来没挂 [DeviceTransferBlockingInterceptor]——那道闸同时管「设备转移时断网」
 * 和「跨境同意之前断网」。于是还没同意就在注册页主动提交日志，日志会发到香港的 debuglogs 端点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiDebugLogUploadGateTest {

  // DeviceTransferBlockingInterceptor 的静态初始化会读 SignalStore.misc
  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(MiscellaneousValues::class))

  @Test
  fun `debug log upload goes through the same network gate as every other server request`() {
    val client = SubmitDebugLogRepository.UploadClient.build()

    assertThat(client.interceptors).contains(DeviceTransferBlockingInterceptor.getInstance())
  }
}
