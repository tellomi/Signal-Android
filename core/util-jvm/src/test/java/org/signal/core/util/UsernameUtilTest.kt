package org.signal.core.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import org.signal.core.util.UsernameUtil.checkDiscriminator
import org.signal.core.util.UsernameUtil.checkNickname

class UsernameUtilTest {
  @Test
  fun checkUsername_tooShort() {
    assertThat(checkNickname(null)).isEqualTo(UsernameUtil.InvalidReason.TOO_SHORT)
    assertThat(checkNickname("")).isEqualTo(UsernameUtil.InvalidReason.TOO_SHORT)
    assertThat(checkNickname("ab")).isEqualTo(UsernameUtil.InvalidReason.TOO_SHORT)
  }

  @Test
  fun checkUsername_tooLong() {
    assertThat(checkNickname("abcdefghijklmnopqrstuvwxyz1234567")).isEqualTo(UsernameUtil.InvalidReason.TOO_LONG)
  }

  @Test
  fun checkUsername_startsWithNumber() {
    assertThat(checkNickname("0abcdefg")).isEqualTo(UsernameUtil.InvalidReason.STARTS_WITH_NUMBER)
    assertThat(checkNickname("9abcdefg")).isEqualTo(UsernameUtil.InvalidReason.STARTS_WITH_NUMBER)
    assertThat(checkNickname("8675309")).isEqualTo(UsernameUtil.InvalidReason.STARTS_WITH_NUMBER)
  }

  @Test
  fun checkUsername_invalidCharacters() {
    assertThat(checkNickname("\$abcd")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
    assertThat(checkNickname(" abcd")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
    assertThat(checkNickname("ab cde")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
    assertThat(checkNickname("%%%%%")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
    assertThat(checkNickname("-----")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
    assertThat(checkNickname("asĸ_me")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
    assertThat(checkNickname("+18675309")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
  }

  @Test
  fun checkUsername_validUsernames() {
    assertThat(checkNickname("abcd")).isNull()
    // Tellomi（tellomi/tellomi#1181）：上游这两条是 26 个字母；新建上限改成 20 之后换成 20 个
    assertThat(checkNickname("abcdefghijklmnopqrst")).isNull()
    assertThat(checkNickname("ABCDEFGHIJKLMNOPQRST")).isNull()
    assertThat(checkNickname("web_head")).isNull()
    assertThat(checkNickname("Spider_Fan_1991")).isNull()
  }

  /** Tellomi（tellomi/tellomi#1181，TR-ID-01）：新建 / 修改时昵称最长 20——21 位起就是 TOO_LONG，上游认为合法的 26 位也是。 */
  @Test
  fun checkUsername_tellomiMaxIs20() {
    assertThat(checkNickname("abcdefghijklmnopqrst")).isNull()
    assertThat(checkNickname("abcdefghijklmnopqrstu")).isEqualTo(UsernameUtil.InvalidReason.TOO_LONG)
    assertThat(checkNickname("abcdefghijklmnopqrstuvwxyz")).isEqualTo(UsernameUtil.InvalidReason.TOO_LONG)
  }

  /**
   * Tellomi（ADR-0066 §六 第 73 行 / ADR-0036）：新建 / 修改时必须字母开头。libsignal 只拒数字开头、放行 `_` 开头，
   * 服务端只见到哈希，所以这条只能在客户端收紧。`_` 在中间、结尾照旧合法。
   */
  @Test
  fun checkUsername_tellomiMustStartWithLetter() {
    assertThat(checkNickname("_kaixin")).isEqualTo(UsernameUtil.InvalidReason.STARTS_WITH_UNDERSCORE)
    assertThat(checkNickname("___")).isEqualTo(UsernameUtil.InvalidReason.STARTS_WITH_UNDERSCORE)
    assertThat(checkNickname("_1abc")).isEqualTo(UsernameUtil.InvalidReason.STARTS_WITH_UNDERSCORE)
    // 其余判断照旧先由 libsignal 给出：太短、非法字符不会被报成「下划线开头」
    assertThat(checkNickname("_a")).isEqualTo(UsernameUtil.InvalidReason.TOO_SHORT)
    assertThat(checkNickname("_ab cd")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
    assertThat(checkNickname("kai_xin")).isNull()
    assertThat(checkNickname("kaixin_")).isNull()
  }

  /** 搜索不跟着收紧：别人（或 Desktop 修好之前）已有的 `_` 开头用户名照样能找到。 */
  @Test
  fun isValidUsernameForSearch_underscoreStartStillSearchable() {
    assertThat(UsernameUtil.isValidUsernameForSearch("_kaixin")).isTrue()
    assertThat(UsernameUtil.isValidUsernameForSearch("_kaixin.01")).isTrue()
  }

  /** Tellomi（tellomi/tellomi#1181）：搜索仍按协议上限 32——之前已有的长用户名必须还能搜到，20 只约束新建。 */
  @Test
  fun isValidUsernameForSearch_existingLongUsernamesStillSearchable() {
    assertThat(UsernameUtil.isValidUsernameForSearch("abcdefghijklmnopqrstuvwxyz_12345")).isTrue()
    assertThat(UsernameUtil.isValidUsernameForSearch("abcdefghijklmnopqrstuvwxyz_12345.01")).isTrue()
    assertThat(UsernameUtil.isValidUsernameForSearch("abcdefghijklmnopqrstu.01")).isTrue()
    // 反例用纯字母：上游 SEARCH_PATTERN 的后缀是 `(.[0-9]+)?`，点没转义、能吃任意字符，
    // 于是带数字的 33 位串会被拆成「30 位昵称 + 任意字符 + 数字」而判成合法（上游行为，这里不动它）
    assertThat(UsernameUtil.isValidUsernameForSearch("abcdefghijklmnopqrstuvwxyzabcdefg")).isFalse()
  }

  @Test
  fun checkDiscriminator_valid() {
    assertThat(checkDiscriminator(null)).isNull()
    assertThat(checkDiscriminator("01")).isNull()
    assertThat(checkDiscriminator("111111111")).isNull()
  }

  @Test
  fun checkDiscriminator_tooShort() {
    assertThat(checkDiscriminator("0")).isEqualTo(UsernameUtil.InvalidReason.TOO_SHORT)
    assertThat(checkDiscriminator("")).isEqualTo(UsernameUtil.InvalidReason.TOO_SHORT)
  }

  @Test
  fun checkDiscriminator_tooLong() {
    assertThat(checkDiscriminator("1111111111")).isEqualTo(UsernameUtil.InvalidReason.TOO_LONG)
  }

  @Test
  fun checkDiscriminator_00() {
    assertThat(checkDiscriminator("00")).isEqualTo(UsernameUtil.InvalidReason.INVALID_NUMBER_00)
  }

  @Test
  fun checkDiscriminator_prefixZero() {
    assertThat(checkDiscriminator("001")).isEqualTo(UsernameUtil.InvalidReason.INVALID_NUMBER_PREFIX_0)
    assertThat(checkDiscriminator("0001")).isEqualTo(UsernameUtil.InvalidReason.INVALID_NUMBER_PREFIX_0)
    assertThat(checkDiscriminator("011")).isEqualTo(UsernameUtil.InvalidReason.INVALID_NUMBER_PREFIX_0)
  }

  @Test
  fun checkDiscriminator_invalidChars() {
    assertThat(checkDiscriminator("a1")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
    assertThat(checkDiscriminator("1x")).isEqualTo(UsernameUtil.InvalidReason.INVALID_CHARACTERS)
  }
}
