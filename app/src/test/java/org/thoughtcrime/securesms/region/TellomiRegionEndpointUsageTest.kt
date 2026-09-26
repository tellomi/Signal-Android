/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import org.junit.Test
import java.io.File

/**
 * tellomi/tellomi#1055：端点只从区域表取（RegionProfile 契约第五节第 5 条）。
 * 除了 `region/TellomiRegions.kt`，app 的源码里不许再直接读这些构建常量；新加的调用点要走 `TellomiRegions.current()`。
 * cdsi / svr2 与静态 IP 表不进区域，不在这张单子里。
 */
class TellomiRegionEndpointUsageTest {

  private val endpointKeys = listOf(
    "SIGNAL_URL",
    "STORAGE_URL",
    "SIGNAL_CDN_URL",
    "SIGNAL_CDN2_URL",
    "SIGNAL_CDN3_URL",
    "LIBSIGNAL_CUSTOM_SERVER_HOST",
    "UPDATES2_HOST",
    "BADGE_STATIC_ROOT",
    "APK_UPDATE_MANIFEST_URL",
    "CONTENT_PROXY_HOST",
    "CONTENT_PROXY_PORT",
    "SIGNAL_CAPTCHA_URL",
    "RECAPTCHA_PROOF_URL",
    "SIGNAL_SFU_URL",
    "SIGNAL_STAGING_SFU_URL",
    "SIGNAL_SERVICE_STATUS_URL"
  )

  private val directRead = Regex("""BuildConfig\.(${endpointKeys.joinToString("|")})\b""")

  private val sources = File("src/main/java")
  private val table = File(sources, "org/thoughtcrime/securesms/region/TellomiRegions.kt")

  @Test
  fun `endpoints are read only from the region table`() {
    val offenders = sources.walkTopDown()
      .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
      .filterNot { it.canonicalPath == table.canonicalPath }
      .flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
          if (directRead.containsMatchIn(line)) "${file.relativeTo(sources).invariantSeparatorsPath}:${index + 1}: ${line.trim()}" else null
        }
      }
      .toList()

    assertThat(offenders).isEmpty()
  }

  @Test
  fun `the scan really looks at the sources`() {
    // 上一条的「空」要有意义：工作目录下确实有源码，表文件本身确实命中
    assertThat(sources.isDirectory).isTrue()
    assertThat(directRead.findAll(table.readText()).count()).isGreaterThan(10)
    assertThat(directRead.containsMatchIn("val url = BuildConfig.SIGNAL_URL")).isTrue()
    assertThat(directRead.containsMatchIn("val url = BuildConfig.SIGNAL_URL_OTHER")).isFalse()
  }
}
