/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.restoreselection

import org.signal.registration.RegistrationFlowState

sealed class ArchiveRestoreSelectionScreenEvents {
  /** The parent registration flow state changed and needs to be merged into this screen's state. */
  data class ParentStateChanged(val parentState: RegistrationFlowState) : ArchiveRestoreSelectionScreenEvents()

  data class RestoreOptionSelected(val option: ArchiveRestoreOption) : ArchiveRestoreSelectionScreenEvents()

  data object ConfirmSkip : ArchiveRestoreSelectionScreenEvents()

  data object DismissSkipWarning : ArchiveRestoreSelectionScreenEvents()

  /** Tellomi（tellomi/tellomi#1216 跟进）：「旧手机是 iPhone」说明页上的「直接注册」。 */
  data object TellomiRegisterDirectly : ArchiveRestoreSelectionScreenEvents()

  /** Tellomi：同一页上的「返回」和系统返回键。 */
  data object TellomiBack : ArchiveRestoreSelectionScreenEvents()
}
