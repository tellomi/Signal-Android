/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.profiles.manage

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#1106 第二刀 b，ADR-0066 §六）：修复模式里什么时候还能沿用旧判别位
 * （[UsernameEditViewModel.keepsEnteredDiscriminator]）。
 */
class UsernameEditRecoveryDiscriminatorTest {

  @Test
  fun recoveryReclaimingTheSameNameKeepsTheOldDiscriminator() {
    // 原样重新认领 kaixin.37：hash 相同，不算造新候选
    assertThat(UsernameEditViewModel.keepsEnteredDiscriminator(true, UsernameEditMode.RECOVERY, "kaixin", "kaixin.37")).isTrue()
  }

  @Test
  fun recoveryWithOnlyACaseChangeKeepsTheOldDiscriminator() {
    // 用户名 hash 不分大小写，Kaixin.37 与 kaixin.37 是同一个
    assertThat(UsernameEditViewModel.keepsEnteredDiscriminator(true, UsernameEditMode.RECOVERY, "Kaixin", "kaixin.37")).isTrue()
  }

  @Test
  fun recoveryWithAChangedNicknameDropsTheOldDiscriminator() {
    // 上游会去占 kaixin2.37；ADR-0066 §六：新候选只产 .01
    assertThat(UsernameEditViewModel.keepsEnteredDiscriminator(true, UsernameEditMode.RECOVERY, "kaixin2", "kaixin.37")).isFalse()
  }

  @Test
  fun recoveryWithoutAStoredUsernameDropsIt() {
    assertThat(UsernameEditViewModel.keepsEnteredDiscriminator(true, UsernameEditMode.RECOVERY, "kaixin", null)).isFalse()
  }

  @Test
  fun nothingEnteredByTheUserMeansNoDiscriminator() {
    assertThat(UsernameEditViewModel.keepsEnteredDiscriminator(false, UsernameEditMode.RECOVERY, "kaixin", "kaixin.37")).isFalse()
    assertThat(UsernameEditViewModel.keepsEnteredDiscriminator(false, UsernameEditMode.NORMAL, "kaixin", "kaixin.01")).isFalse()
  }
}
