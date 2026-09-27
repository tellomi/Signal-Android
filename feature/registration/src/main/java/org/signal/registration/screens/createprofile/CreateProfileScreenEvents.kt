/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.usernames.Username
import org.signal.network.service.UsernameService.ReserveUsernameError

sealed class CreateProfileScreenEvents {
  data class GivenNameChanged(val value: String) : CreateProfileScreenEvents() {
    override fun toString(): String = "GivenNameChanged(value=${value.length} chars)"
  }
  data class FamilyNameChanged(val value: String) : CreateProfileScreenEvents() {
    override fun toString(): String = "FamilyNameChanged(value=${value.length} chars)"
  }
  data class AvatarSelected(val bytes: ByteArray) : CreateProfileScreenEvents() {
    override fun toString(): String = "AvatarSelected(${bytes.size} bytes)"
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is AvatarSelected) return false
      return bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = bytes.contentHashCode()
  }
  data object AvatarCleared : CreateProfileScreenEvents()
  data object WhoCanFindMeClicked : CreateProfileScreenEvents()
  data class DiscoverabilityChanged(val discoverable: Boolean) : CreateProfileScreenEvents()
  data object NextClicked : CreateProfileScreenEvents()

  /** Tellomi（tellomi/tellomi#1215 第二刀）：用户名框改了。 */
  data class UsernameChanged(val value: String) : CreateProfileScreenEvents() {
    override fun toString(): String = "UsernameChanged(value=${value.length} chars)"
  }

  /** Tellomi：点了一个候选用户名。 */
  data class UsernameCandidateClicked(val value: String) : CreateProfileScreenEvents() {
    override fun toString(): String = "UsernameCandidateClicked(value=${value.length} chars)"
  }

  /** Tellomi，内部：停顿够久了，检查并保留 [nickname]。 */
  data class UsernameSettled(val nickname: String) : CreateProfileScreenEvents() {
    override fun toString(): String = "UsernameSettled(nickname=${nickname.length} chars)"
  }

  /** Tellomi，内部：保留 [nickname] 的请求回来了。 */
  data class UsernameReservationCompleted(val nickname: String, val result: RequestResult<Username, ReserveUsernameError>) : CreateProfileScreenEvents() {
    override fun toString(): String = "UsernameReservationCompleted(nickname=${nickname.length} chars, result=${result.javaClass.simpleName})"
  }
  data object UploadFailedDialogDismissed : CreateProfileScreenEvents()
}
