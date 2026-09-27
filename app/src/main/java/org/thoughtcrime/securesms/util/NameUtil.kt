package org.thoughtcrime.securesms.util

import org.signal.core.util.TellomiNames

object NameUtil {

  /**
   * Returns an abbreviation of the input, up to two characters long.
   *
   * Tellomi（tellomi/tellomi#1215）：规则挪到 [TellomiNames.abbreviation]，注册页的头像预览也用它——
   * 中文名取最后两个字（「欧阳娜娜」→「娜娜」），其它名字照上游（「John Smith」→「JS」）。
   */
  @JvmStatic
  fun getAbbreviation(name: String): String? {
    return TellomiNames.abbreviation(name)
  }
}
