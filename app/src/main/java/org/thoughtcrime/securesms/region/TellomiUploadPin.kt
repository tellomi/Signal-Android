/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import org.signal.protos.resumableuploads.ResumableUpload

/**
 * Tellomi（tellomi/tellomi#1055 第三刀）：在途的续传钉住开始时的区（RegionProfile 契约第六节最后一条）。
 *
 * 上游续传时按**当前**配置的 CDN 改写主机（`PushServiceSocket.buildConfiguredUrl`），切区之后，在途的续传会静默换到新区。
 * 契约只许两种做法：用旧区跑完，或者显式从头传。Android 走后一种：规格里盖上开始时的区，接着传之前核一下；
 * 区变了就当规格过期（和 `timeout` 过期走同一条路：清掉、重新取表单、从 0 开始，落到现在的区）。
 *
 * 规格整个存在 job 数据里（`ResumableUpload`；附件上传、附件进备份、消息备份三个 job），所以不用迁移。
 * 没有区戳的旧规格按 global。
 */
object TellomiUploadPin {

  /** 现在的区（盖章、比对都用它）。 */
  @JvmStatic
  fun currentRegionId(): String = TellomiRegions.current().id.id

  /** 盖上现在的区：拿到新规格的那一刻调。 */
  @JvmStatic
  fun stamp(spec: ResumableUpload): ResumableUpload = spec.copy(tellomiRegionId = currentRegionId())

  /** 规格开始时的区。没盖章的（这一刀之前存下的）按 global。 */
  @JvmStatic
  fun startedIn(spec: ResumableUpload): String = startedIn(spec.tellomiRegionId)

  @JvmStatic
  fun startedIn(regionId: String?): String = regionId?.takeIf { it.isNotEmpty() } ?: TellomiRegionId.GLOBAL.id

  /** 开始时的区就是 [currentId]，才能接着传；否则当规格过期，从头传。 */
  @JvmStatic
  @JvmOverloads
  fun canResume(spec: ResumableUpload, currentId: String = currentRegionId()): Boolean = startedIn(spec) == currentId

  @JvmStatic
  @JvmOverloads
  fun canResume(regionId: String?, currentId: String = currentRegionId()): Boolean = startedIn(regionId) == currentId
}
