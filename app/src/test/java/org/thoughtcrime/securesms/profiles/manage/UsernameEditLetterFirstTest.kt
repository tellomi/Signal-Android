package org.thoughtcrime.securesms.profiles.manage

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import org.signal.core.util.UsernameUtil.InvalidReason

/**
 * Tellomi（ADR-0066 §六；taishi 审 a3 的意见）：编辑页只对「新起的名字」收紧「字母开头」和「最长 20」。
 * 已有的 `_xxx` 或 21–32 位的名字，只改大小写、修复模式认领原名时不能被拦——否则这些用户就改不了、认领不回自己的名字。
 */
class UsernameEditLetterFirstTest {

  @Test
  fun `only changing the case of an existing underscore name is allowed`() {
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_KaiXin", "_kaixin.01")).isNull()
  }

  @Test
  fun `recovery reclaiming an existing underscore name is allowed`() {
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_kaixin", "_kaixin.57")).isNull()
  }

  @Test
  fun `a new underscore name is rejected`() {
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_other", "_kaixin.01")).isEqualTo(InvalidReason.STARTS_WITH_UNDERSCORE)
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_kaixin", null)).isEqualTo(InvalidReason.STARTS_WITH_UNDERSCORE)
    assertThat(UsernameEditViewModel.checkNicknameForEdit("_kaixin", "kaixin.01")).isEqualTo(InvalidReason.STARTS_WITH_UNDERSCORE)
  }

  @Test
  fun `an existing name longer than 20 is allowed only when kept`() {
    val existing = "abcdefghijklmnopqrstuvwxy" // 25 位：#1181 之前建的
    assertThat(UsernameEditViewModel.checkNicknameForEdit(existing.uppercase(), "$existing.01")).isNull()
    assertThat(UsernameEditViewModel.checkNicknameForEdit(existing, null)).isEqualTo(InvalidReason.TOO_LONG)
    assertThat(UsernameEditViewModel.checkNicknameForEdit(existing + "z", "$existing.01")).isEqualTo(InvalidReason.TOO_LONG)
  }

  @Test
  fun `other reasons still apply even to the current name`() {
    // 只放过上面两种；别的原因（这里是太短）即使和当前昵称相同也照报
    assertThat(UsernameEditViewModel.checkNicknameForEdit("ab", "ab.01")).isEqualTo(InvalidReason.TOO_SHORT)
    assertThat(UsernameEditViewModel.checkNicknameForEdit("kaixin", "_kaixin.01")).isNull()
  }
}
