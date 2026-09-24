/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.testutil.SignalStoreRule

/**
 * Tellomi（tellomi/tellomi#1138）：「有新版本」给「关于」页读，装上那个版本之后不能还挂着。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ApkUpdateValuesTest {

  @get:Rule
  val signalStore = SignalStoreRule()

  @Test
  fun `a newer version from the manifest is reported`() {
    SignalStore.apkUpdate.setAvailableUpdate(BuildConfig.VERSION_CODE + 1, "9.9.9")

    assertThat(SignalStore.apkUpdate.availableUpdateVersionName).isEqualTo("9.9.9")
  }

  @Test
  fun `once that version is installed it is no longer reported, even before the next check`() {
    SignalStore.apkUpdate.setAvailableUpdate(BuildConfig.VERSION_CODE, "0.1.2")

    assertThat(SignalStore.apkUpdate.availableUpdateVersionName).isNull()
  }

  @Test
  fun `a check that finds nothing newer clears it`() {
    SignalStore.apkUpdate.setAvailableUpdate(BuildConfig.VERSION_CODE + 1, "9.9.9")

    SignalStore.apkUpdate.clearAvailableUpdate()

    assertThat(SignalStore.apkUpdate.availableUpdateVersionName).isNull()
  }

  @Test
  fun `a download remembers whether it may use mobile data, and clearing forgets it`() {
    // taishi 审查 b14 要改 1 / 3。
    SignalStore.apkUpdate.setDownloadAttributes(42, null, 0, allowsMetered = true)
    assertThat(SignalStore.apkUpdate.downloadAllowsMetered).isTrue()

    SignalStore.apkUpdate.clearDownloadAttributes()
    assertThat(SignalStore.apkUpdate.downloadAllowsMetered).isFalse()

    // 上游后台检查排的：只许 Wi-Fi。
    SignalStore.apkUpdate.setDownloadAttributes(43, null, 0)
    assertThat(SignalStore.apkUpdate.downloadAllowsMetered).isFalse()
  }
}
