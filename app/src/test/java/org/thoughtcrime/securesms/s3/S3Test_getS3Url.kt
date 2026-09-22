/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.s3

import assertk.assertThat
import assertk.assertions.isEqualTo
import okio.IOException
import org.junit.Test

// Tellomi（#1017）：这里**故意写死 updates.tellomi.app**，不读 BuildConfig.UPDATES2_HOST——
// 读同一个常量的断言是恒真的：常量被改回上游那个主机时，它照样绿。
@Suppress("ClassName")
class S3Test_getS3Url {
  @Test
  fun validS3Urls() {
    assertThat(S3.s3Url("/static/heart.png").toString()).isEqualTo("https://updates.tellomi.app/static/heart.png")
    assertThat(S3.s3Url("/static/heart.png?weee=1").toString()).isEqualTo("https://updates.tellomi.app/static/heart.png%3Fweee=1")
    assertThat(S3.s3Url("/@signal.org").toString()).isEqualTo("https://updates.tellomi.app/@signal.org")
  }

  @Test(expected = IOException::class)
  fun invalid() {
    S3.s3Url("@signal.org")
  }

  @Test(expected = IOException::class)
  fun invalidRelative() {
    S3.s3Url("static/heart.png")
  }
}
