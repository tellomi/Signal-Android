/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.util

import java.util.regex.Pattern

/**
 * Tellomi（tellomi/tellomi#1215）：名字相关的两条规则，注册页和 App 其它地方共用，保证注册时看到的就是之后看到的。
 */
object TellomiNames {

  /**
   * 去掉每个词**开头**的非字母 / 数字 / 符号，与 iOS `TellomiNames` 相同。
   * 上游 NameUtil 的这条没锚定，`replaceFirst` 删的是词里第一段非字母，不一定在开头：❤️ 会丢掉 FE0F、泰文 / 阿拉伯文丢元音符号、
   * ZWJ 表情被拆开、NFD 的 é 变成 e（taishi 审查包 5，两端对照表见 PR）。
   */
  private val LEADING_NON_LETTERS = Pattern.compile("^[^\\p{L}\\p{Nd}\\p{S}]+")

  /** 与 iOS `StringSanitizer` 同一条线：一个字素超过 16 个码位（Zalgo）就换成 U+FFFD，免得组合符叠出头像的圆。 */
  private const val MAX_CODE_POINTS_PER_GRAPHEME = 16

  private val CJKV_SCRIPTS = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL
  )

  /**
   * 默认头像上的字（NameUtil.getAbbreviation 也走这里）。
   * - 中文名（去掉空白和标点后全是汉字）取**最后两个字**：「欧阳娜娜」→「娜娜」、「张三」→「张三」、「李」→「李」、「阿·卡」→「阿卡」，
   *   和国内常见的默认头像一致；
   * - 其它名字照上游：第一个词的首字母，有第二个词再加第二个词的首字母（「John Smith」→「JS」）。
   */
  @JvmStatic
  fun abbreviation(name: String): String? {
    hanAbbreviation(name)?.let { return it }

    val parts = name
      .split(" ")
      .map { it.trim() }
      .map { LEADING_NON_LETTERS.matcher(it).replaceFirst("") }
      .filter { it.isNotEmpty() }

    return when {
      parts.isEmpty() -> null
      parts.size == 1 -> parts[0].firstGrapheme()
      else -> "${parts[0].firstGrapheme()}${parts[1].firstGrapheme()}"
    }
  }

  /**
   * 注册页只剩一个「名字」框：已有的名 + 姓合成一个串放进去（再保存时全进 given name，family name 留空）。
   * 与 ProfileName 的拼法一致：两边都是中日韩文字时姓在前、不加空格（「张三」），否则名在前、空格分隔。
   */
  @JvmStatic
  fun joinForSingleField(givenName: String, familyName: String): String {
    val given = givenName.trim()
    val family = familyName.trim()
    return when {
      family.isEmpty() -> given
      given.isEmpty() -> family
      isCjkv(given) && isCjkv(family) -> family + given
      else -> "$given $family"
    }
  }

  private fun hanAbbreviation(name: String): String? {
    val letters = name.codePoints()
      .filter { !isSpace(it) && !isPunctuation(it) }
      .toArray()

    if (letters.isEmpty() || letters.any { Character.UnicodeScript.of(it) != Character.UnicodeScript.HAN }) {
      return null
    }

    return String(letters, maxOf(0, letters.size - 2), minOf(2, letters.size))
  }

  private fun isCjkv(text: String): Boolean {
    return text.codePoints()
      .filter { !isSpace(it) && !isPunctuation(it) }
      .allMatch { Character.UnicodeScript.of(it) in CJKV_SCRIPTS }
  }

  /** `Character.isWhitespace` 不算不换行空格（U+00A0 / U+2007 / U+202F），`isSpaceChar` 算；两个都认，与 iOS 的 `\s` 一致。 */
  private fun isSpace(codePoint: Int): Boolean {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
  }

  private fun isPunctuation(codePoint: Int): Boolean {
    return when (Character.getType(codePoint).toByte()) {
      Character.CONNECTOR_PUNCTUATION,
      Character.DASH_PUNCTUATION,
      Character.START_PUNCTUATION,
      Character.END_PUNCTUATION,
      Character.INITIAL_QUOTE_PUNCTUATION,
      Character.FINAL_QUOTE_PUNCTUATION,
      Character.OTHER_PUNCTUATION -> true
      else -> false
    }
  }

  private fun String.firstGrapheme(): String {
    val grapheme = CharacterIterable(this).first()
    return if (grapheme.codePointCount(0, grapheme.length) > MAX_CODE_POINTS_PER_GRAPHEME) "\uFFFD" else grapheme
  }
}
