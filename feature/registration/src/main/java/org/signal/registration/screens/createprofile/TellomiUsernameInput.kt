/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import androidx.compose.ui.text.input.TextFieldValue
import org.signal.core.util.TellomiUsernames

/**
 * Tellomi（ADR-0066 §6.1b，owner 2026-09-27）：用户名一律小写，所有填用户名的地方（注册资料页、设置里的设置 / 修改 / 找回）一致：
 *
 * 1. 输入框下常驻一行灰色规则提示（`TellomiUsername__rules_hint`）；
 * 2. 打了（或粘贴了）大写当场转小写，光标 / 选区不跳；规则提示那一行换成「已自动转成小写」[LOWERCASED_HINT_MS]，然后恢复；
 * 3. 别的不合规字符（空格、中文、减号、点……）不动，照旧就地红字报错。
 *
 * 只转 `A`–`Z`（[TellomiUsernames.lowercaseAscii]），所以长度不变，选区原样可用。
 */
object TellomiUsernameInput {
  /** 「已自动转成小写」停留多久。 */
  const val LOWERCASED_HINT_MS = 2_000L

  /** Compose 输入框：只改文字，选区和输入法的组字区原样保留。没有大写时原样返回（同一个对象）。 */
  fun lowercase(value: TextFieldValue): TextFieldValue {
    val lowered = TellomiUsernames.lowercaseAscii(value.text)
    return if (lowered == value.text) value else value.copy(text = lowered)
  }
}

/**
 * EditText（设置里的用户名编辑页）用的过滤器：键入、粘贴、输入法提交的文字里有大写就换成小写再插进去；替换文字和原文一样长，
 * 光标照常落在插入点之后。每次真的转了就回调 [onLowercased]（用来把规则提示换成「已自动转成小写」）。
 */
class TellomiUsernameInputFilter(private val onLowercased: () -> Unit) : InputFilter {
  override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
    val original = source.subSequence(start, end).toString()
    val lowered = TellomiUsernames.lowercaseAscii(original)
    if (lowered == original) {
      return null
    }

    onLowercased()

    return if (source is Spanned) {
      // 保留输入法的组字等 span，不然有的输入法会把正在组的字当成已提交
      SpannableString(lowered).also { TextUtils.copySpansFrom(source, start, end, null, it, 0) }
    } else {
      lowered
    }
  }
}
