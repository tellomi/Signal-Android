/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.profiles.manage

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import org.signal.core.util.UsernameUtil.InvalidReason

/**
 * Tellomi（ADR-0066 §六；taishi 审 a3 与 Signal-Desktop#4 第三版）：编辑页只对「新起的名字」收紧「字母开头」和「最长 20」。
 * 已有的 `_xxx` 或 21–32 位的名字，`.01` 只改大小写、修复模式原样认领时不能被拦（hash 没变）；
 * 旧后缀迁到 `.01` 是换成一个新用户名，照新名字的规则拦。
 */
class UsernameEditLetterFirstTest {

  @Test
  fun `only changing the case of an existing underscore name is allowed`() {
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_KaiXin", "_kaixin.01", isRecovery = false)).isNull()
  }

  @Test
  fun `recovery reclaiming an existing underscore name is allowed`() {
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_kaixin", "_kaixin.57", isRecovery = true)).isNull()
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_kaixin", "_kaixin.01", isRecovery = true)).isNull()
  }

  @Test
  fun `moving an old suffix to 01 is a new name and follows the new-name rules`() {
    // 非修复模式下 `_kaixin.57` 输入同一昵称 = 预约 `_kaixin.01`：换了 hash、服务端按改名开始冷却（Desktop#4 第三版同一条）
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_kaixin", "_kaixin.57", isRecovery = false)).isEqualTo(InvalidReason.STARTS_WITH_UNDERSCORE)
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_KaiXin", "_kaixin.57", isRecovery = false)).isEqualTo(InvalidReason.STARTS_WITH_UNDERSCORE)
  }

  @Test
  fun `a new underscore name is rejected`() {
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_other", "_kaixin.01", isRecovery = false)).isEqualTo(InvalidReason.STARTS_WITH_UNDERSCORE)
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_other", "_kaixin.57", isRecovery = true)).isEqualTo(InvalidReason.STARTS_WITH_UNDERSCORE)
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_kaixin", null, isRecovery = false)).isEqualTo(InvalidReason.STARTS_WITH_UNDERSCORE)
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_kaixin", "kaixin.01", isRecovery = false)).isEqualTo(InvalidReason.STARTS_WITH_UNDERSCORE)
  }

  @Test
  fun `an existing name longer than 20 is allowed only when kept`() {
    val existing = "abcdefghijklmnopqrstuvwxy" // 25 位：#1181 之前建的
    assertThat(UsernameEditViewModel.checkNicknameForEdit(existing.uppercase(), "$existing.01", isRecovery = false)).isNull()
    assertThat(UsernameEditViewModel.checkNicknameForEdit(existing, "$existing.37", isRecovery = true)).isNull()
    // 旧后缀迁到 `.01` 算新名字：超过 20 位照拦
    assertThat(UsernameEditViewModel.checkNicknameForEdit(existing, "$existing.37", isRecovery = false)).isEqualTo(InvalidReason.TOO_LONG)
    assertThat(UsernameEditViewModel.checkNicknameForEdit(existing, null, isRecovery = false)).isEqualTo(InvalidReason.TOO_LONG)
    assertThat(UsernameEditViewModel.checkNicknameForEdit(existing + "z", "$existing.01", isRecovery = false)).isEqualTo(InvalidReason.TOO_LONG)
  }

  @Test
  fun `other reasons still apply even to the current name`() {
    // 只放过上面两种；别的原因（这里是太短）即使和当前昵称相同也照报
    assertThat(UsernameEditViewModel.checkNicknameForEdit("ab", "ab.01", isRecovery = false)).isEqualTo(InvalidReason.TOO_SHORT)
    assertThat(UsernameEditViewModel.checkNicknameForEdit("kaixin", "_kaixin.01", isRecovery = false)).isNull()
  }
}
