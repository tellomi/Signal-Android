/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.net

import android.os.Build
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.BuildConfig

/**
 * Tellomi（tellomi/tellomi#1137）：User-Agent 带构建号，服务端才分得出同一个 versionName 的热修包。
 * 格式由 taishi 在中转包 8 第〇节第 1 条定：版本号不动，构建号放在最后的 Build/ 段。
 */
class StandardUserAgentTest {

  @Test
  fun `the user agent carries the version code in a trailing build segment`() {
    assertThat(StandardUserAgentInterceptor.USER_AGENT)
      .isEqualTo("Signal-Android/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.SDK_INT} Build/${BuildConfig.VERSION_CODE}")
  }

  @Test
  fun `the server's user agent pattern reads the platform, the plain version and the build segment`() {
    // 服务端 UserAgentUtil 的 STANDARD_UA_PATTERN，原样照抄
    val serverPattern = Regex("^Signal-(Android|Desktop|iOS)/([^ ]+)( (.+))?$", RegexOption.IGNORE_CASE)
    val match = serverPattern.matchEntire(StandardUserAgentInterceptor.USER_AGENT)
    assertThat(match != null).isTrue()
    assertThat(match!!.groupValues[1]).isEqualTo("Android")
    assertThat(match.groupValues[2]).isEqualTo(BuildConfig.VERSION_NAME)
    assertThat(match.groupValues[4]).isEqualTo("Android/${Build.VERSION.SDK_INT} Build/${BuildConfig.VERSION_CODE}")
  }
}
