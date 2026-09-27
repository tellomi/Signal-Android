/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.welcome

data class WelcomeScreenState(
  /** Gates whether the link device option is shown as the primary path on large devices. */
  val isLinkAndSyncAvailable: Boolean = false,
  /**
   * Whether to offer the restore-or-transfer option. Hidden during a re-registration, and kept hidden until the parent
   * flow has finished loading so we never briefly show it before learning that pre-existing data exists.
   */
  val showRestoreOrTransfer: Boolean = true,
  /**
   * Tellomi（ADR-0072 §4.1 第 4 步）：本机是主动退出登录的账号时，欢迎页上的「上次登录」；其余时候为 null。
   */
  val lastLogin: LastLogin? = null
) {

  /**
   * Tellomi：「上次登录」一块显示的东西——打码的号码（`+86 138****5678`）和本机保存的头像，没有头像时显示名字的第一个字。
   * 完整号码不放进状态（状态会写日志）。
   */
  data class LastLogin(
    val maskedE164: String,
    val avatar: ByteArray? = null,
    val initial: String = ""
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is LastLogin) return false
      return maskedE164 == other.maskedE164 && initial == other.initial && avatar.contentEquals(other.avatar)
    }

    override fun hashCode(): Int {
      var result = maskedE164.hashCode()
      result = 31 * result + (avatar?.contentHashCode() ?: 0)
      result = 31 * result + initial.hashCode()
      return result
    }

    override fun toString(): String = "LastLogin(maskedE164=$maskedE164, hasAvatar=${avatar != null}, hasInitial=${initial.isNotEmpty()})"
  }
}
