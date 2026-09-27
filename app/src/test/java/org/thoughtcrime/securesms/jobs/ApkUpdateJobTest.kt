/*
 * Copyright 2026 重庆半格智能科技有限公司
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

  // ==================== taishi 审查 b14（包 4）====================

  @Test
  fun `only a Wi-Fi-only download that is not running gets re-enqueued for mobile data`() {
    // 要改 3：已经允许流量的（暂停中也算）不删、不从 0 重下；正在下的不打断；后台检查不改排。
    assertThat(ApkUpdateJob.shouldReenqueueForMeteredNetwork(allowMeteredNetwork = true, existingIsRunning = false, existingAllowsMetered = false)).isTrue()
    assertThat(ApkUpdateJob.shouldReenqueueForMeteredNetwork(allowMeteredNetwork = true, existingIsRunning = false, existingAllowsMetered = true)).isFalse()
    assertThat(ApkUpdateJob.shouldReenqueueForMeteredNetwork(allowMeteredNetwork = true, existingIsRunning = true, existingAllowsMetered = false)).isFalse()
    assertThat(ApkUpdateJob.shouldReenqueueForMeteredNetwork(allowMeteredNetwork = false, existingIsRunning = false, existingAllowsMetered = false)).isFalse()

    // taishi 审查 b14 包 7 不阻塞 2：用户在「重试」上点了重来，已经允许流量却卡着的那条也重排；正在下的照旧不打断，后台检查照旧不动。
    assertThat(ApkUpdateJob.shouldReenqueueForMeteredNetwork(allowMeteredNetwork = true, existingIsRunning = false, existingAllowsMetered = true, restartStuckDownload = true)).isTrue()
    assertThat(ApkUpdateJob.shouldReenqueueForMeteredNetwork(allowMeteredNetwork = true, existingIsRunning = true, existingAllowsMetered = true, restartStuckDownload = true)).isFalse()
    assertThat(ApkUpdateJob.shouldReenqueueForMeteredNetwork(allowMeteredNetwork = false, existingIsRunning = false, existingAllowsMetered = true, restartStuckDownload = true)).isFalse()
  }

  @Test
  fun `the manifest's version name and download address are checked before they are shown or used`() {
    // 不阻塞 3：清单还没签名（#1136），版本号会原样显示，下载地址会被拿去下。
    val manifest = "https://updates.tellomi.app/android/latest.json"
    val apk = "https://updates.tellomi.app/android/Tellomi-website-prod-universal-release-0.1.3.apk"

    assertThat(ApkUpdateJob.isTrustedDescriptor("0.1.3", apk, manifest)).isTrue()
    assertThat(ApkUpdateJob.isTrustedDescriptor("1.2.3.4", apk, manifest)).isTrue()

    assertThat(ApkUpdateJob.isTrustedDescriptor("0.1.3 请立即转账", apk, manifest)).isFalse()
    assertThat(ApkUpdateJob.isTrustedDescriptor("1", apk, manifest)).isFalse()
    assertThat(ApkUpdateJob.isTrustedDescriptor("1.2.3.4.5", apk, manifest)).isFalse()
    assertThat(ApkUpdateJob.isTrustedDescriptor(null, apk, manifest)).isFalse()

    assertThat(ApkUpdateJob.isTrustedDescriptor("0.1.3", "https://updates.tellomi.app.evil.example/x.apk", manifest)).isFalse()
    assertThat(ApkUpdateJob.isTrustedDescriptor("0.1.3", "http://updates.tellomi.app/android/x.apk", manifest)).isFalse()
    assertThat(ApkUpdateJob.isTrustedDescriptor("0.1.3", "https://example.com/x.apk", manifest)).isFalse()
    assertThat(ApkUpdateJob.isTrustedDescriptor("0.1.3", null, manifest)).isFalse()
  }
}
