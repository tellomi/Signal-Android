/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.jobmanager.Job

/**
 * Tellomi（tellomi/tellomi#1138）：「必须更新」发起的检查允许用流量下载，这个标记要能存进任务队列再取出来。
 */
class ApkUpdateJobTest {

  @Test
  fun `the metered flag survives the job queue`() {
    val job = ApkUpdateJob(allowMeteredNetwork = true)

    val restored = ApkUpdateJob.Factory().create(Job.Parameters.Builder().build(), job.serialize())

    assertThat(restored.allowMeteredNetwork).isTrue()
  }

  @Test
  fun `background checks keep the upstream shape and stay on Wi-Fi`() {
    val job = ApkUpdateJob()

    assertThat(job.serialize()).isNull()
    assertThat(ApkUpdateJob.Factory().create(Job.Parameters.Builder().build(), null).allowMeteredNetwork).isFalse()
  }
}
