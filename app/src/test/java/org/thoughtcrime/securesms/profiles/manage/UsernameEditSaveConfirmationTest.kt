/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.profiles.manage

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.thoughtcrime.securesms.profiles.manage.UsernameEditViewModel.SaveConfirmation

/**
 * Tellomi（ADR-0066 §6.2）：保存用户名前弹哪种确认框。保留期内「删了再设」服务端也当改名，要和换名一样先提醒；
 * 与 Desktop `getUsernameSaveConfirmation` 同一判法。
 */
class UsernameEditSaveConfirmationTest {

  private val day = 24L * 60 * 60 * 1000
  private val now = 1_700_000_000_000L

  @Test
  fun `first username with no deletion on record needs no confirmation`() {
    assertThat(UsernameEditViewModel.saveConfirmation(isCaseChange = false, currentUsername = null, deletedAtMillis = 0, nowMillis = now)).isEqualTo(SaveConfirmation.NONE)
  }

  @Test
  fun `replacing an existing username asks for the change confirmation`() {
    assertThat(UsernameEditViewModel.saveConfirmation(false, "kaixin.01", 0, now)).isEqualTo(SaveConfirmation.CHANGE)
    // 有用户名时，删除记录不影响：照旧是换名提醒
    assertThat(UsernameEditViewModel.saveConfirmation(false, "kaixin.01", now - day, now)).isEqualTo(SaveConfirmation.CHANGE)
  }

  @Test
  fun `only changing the case needs no confirmation`() {
    assertThat(UsernameEditViewModel.saveConfirmation(true, "kaixin.01", now - day, now)).isEqualTo(SaveConfirmation.NONE)
  }

  @Test
  fun `setting a username within the hold after deleting one asks for the set-after-delete confirmation`() {
    assertThat(UsernameEditViewModel.saveConfirmation(false, null, now - day, now)).isEqualTo(SaveConfirmation.SET_AFTER_DELETE)
    // 存储服务同步下来的「删掉」是空串，不是 null
    assertThat(UsernameEditViewModel.saveConfirmation(false, "", now - day, now)).isEqualTo(SaveConfirmation.SET_AFTER_DELETE)
  }

  @Test
  fun `setting a username after the hold has passed needs no confirmation`() {
    assertThat(UsernameEditViewModel.saveConfirmation(false, null, now - 31 * day, now)).isEqualTo(SaveConfirmation.NONE)
  }
}
