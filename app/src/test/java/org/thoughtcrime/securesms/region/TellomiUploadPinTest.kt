/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.signal.protos.resumableuploads.ResumableUpload
import org.thoughtcrime.securesms.jobs.protos.AttachmentUploadJobData
import java.io.File

/**
 * tellomi/tellomi#1055 第三刀：在途的续传钉住开始时的区（RegionProfile 契约第六节最后一条）。
 */
class TellomiUploadPinTest {

  private val spec = ResumableUpload(cdnNumber = 3, cdnKey = "abc", location = "https://cdn3.tellomi.app/upload/abc", timeout = 1L)

  @Test
  fun `a spec without a stamp counts as started in global`() {
    assertThat(TellomiUploadPin.startedIn(spec)).isEqualTo("global")
    assertThat(TellomiUploadPin.canResume(spec, "global")).isTrue()
    assertThat(TellomiUploadPin.canResume(spec, "cn")).isFalse()
  }

  @Test
  fun `a spec started in one region is not resumed in another`() {
    val startedInCn = spec.copy(tellomiRegionId = "cn")

    assertThat(TellomiUploadPin.canResume(startedInCn, "cn")).isTrue()
    assertThat(TellomiUploadPin.canResume(startedInCn, "global")).isFalse()
    // 消息备份的规格只存了区 id
    assertThat(TellomiUploadPin.canResume("cn", "global")).isFalse()
    assertThat(TellomiUploadPin.canResume("", "global")).isTrue()
  }

  @Test
  fun `stamping records the current region`() {
    // 单测里 SignalStore 没初始化：TellomiRegions.current() 按规则回落 global（契约第五节第 8 条）
    assertThat(TellomiUploadPin.stamp(spec).tellomiRegionId).isEqualTo("global")
    assertThat(TellomiUploadPin.stamp(spec.copy(tellomiRegionId = "cn")).tellomiRegionId).isEqualTo("global")
  }

  @Test
  fun `the stamp survives the job data round trip and older job data has none`() {
    val job = AttachmentUploadJobData(attachmentId = 7, uploadSpec = spec.copy(tellomiRegionId = "cn"))
    assertThat(AttachmentUploadJobData.ADAPTER.decode(job.encode()).uploadSpec?.tellomiRegionId).isEqualTo("cn")

    // 这一刀之前写下的 job 数据没有这个字段：解出来是空串，按 global，所以升级后在 global 能照常续传
    val older = AttachmentUploadJobData.ADAPTER.decode(AttachmentUploadJobData(attachmentId = 7, uploadSpec = spec).encode())
    assertThat(older.uploadSpec!!.tellomiRegionId).isEqualTo("")
    assertThat(TellomiUploadPin.canResume(older.uploadSpec!!, "global")).isTrue()
  }

  // ── 门禁：把续传规格存进 job 数据的地方，都要盖章、都要在接着传之前核区 ──

  private val sources = File("src/main/java")
  private val pinFile = File(sources, "org/thoughtcrime/securesms/region/TellomiUploadPin.kt")
  private val persistsSpec = Regex("""\bResumableUpload\b""")

  private fun filesPersistingSpecs(): List<File> {
    return sources.walkTopDown()
      .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
      .filterNot { it.canonicalPath == pinFile.canonicalPath }
      .filter { persistsSpec.containsMatchIn(it.readText()) }
      .toList()
  }

  @Test
  fun `every job that keeps an upload spec stamps it and checks the region before resuming`() {
    val offenders = filesPersistingSpecs().filterNot { file ->
      val text = file.readText()
      text.contains("TellomiUploadPin.canResume(") && (text.contains("TellomiUploadPin.stamp(") || text.contains("TellomiUploadPin.currentRegionId()"))
    }
    assertThat(offenders.map { it.name }).isEmpty()
  }

  @Test
  fun `the scan finds the three jobs that keep upload specs`() {
    // 上一条的「空」要有意义：今天存续传规格的正好是这三个 job
    assertThat(filesPersistingSpecs().map { it.name }.sorted())
      .isEqualTo(listOf("AttachmentUploadJob.kt", "BackupMessagesJob.kt", "UploadAttachmentToArchiveJob.kt"))
  }
}
