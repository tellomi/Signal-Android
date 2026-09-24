/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.restoreselection

import org.signal.core.util.censor

data class ArchiveRestoreSelectionState(
  val restoreOptions: List<ArchiveRestoreOption> = emptyList(),
  val showSkipWarningDialog: Boolean = false,
  /** Token that, if present, indicates that the user did a quick restore, and we should hit a network endpoint to indicate our restore selection.  */
  val restoreMethodToken: String? = null,
  /** Whether the account already has SVR/PIN data on the server. Determines whether skipping restore leads to PIN entry or PIN creation. */
  val storageCapable: Boolean = false,
  /** Whether the skip is underway. The last of the work it does is a network call, so the skip card shows a spinner until the flow moves on. */
  val isSkipping: Boolean = false,
  /**
   * Tellomi（tellomi/tellomi#1216，taishi 审查 b7）：还没注册时跳过恢复、接着注册，服务端会把旧手机登出（AccountsManager.reclaimAccount），
   * 跳过确认框要把这一句也说出来；注册之后才出现的这个框，旧手机其实已经登出了，不用再说。
   */
  val skippingSignsOutOldPhone: Boolean = false,
  /**
   * Tellomi（tellomi/tellomi#1216 跟进）：旧手机是 iPhone、又没有备份服务。这一页只说明传不过来，给「直接注册 / 返回」，
   * 和 iOS「旧手机是 Android」那页对齐。
   */
  val showsNoTransferFromIphone: Boolean = false
) {
  override fun toString(): String = "ArchiveRestoreSelectionState(restoreOptions=$restoreOptions, showSkipWarningDialog=$showSkipWarningDialog, restoreMethodToken=${restoreMethodToken?.censor()}, storageCapable=$storageCapable, isSkipping=$isSkipping, skippingSignsOutOldPhone=$skippingSignsOutOldPhone, showsNoTransferFromIphone=$showsNoTransferFromIphone)"
}
