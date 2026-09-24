/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.util

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tellomi（tellomi/tellomi#1215）。Robolectric：拆字（CharacterIterable）在 API 24+ 走 android.icu。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiNamesTest {

  @Test
  fun `chinese names take the last two characters`() {
    assertEquals("娜娜", TellomiNames.abbreviation("欧阳娜娜"))
    assertEquals("张三", TellomiNames.abbreviation("张三"))
    assertEquals("李", TellomiNames.abbreviation("李"))
    assertEquals("志明", TellomiNames.abbreviation("陈 志明"))
    // 译名中间的间隔号不算字
    assertEquals("卡尔", TellomiNames.abbreviation("马克·卡尔"))
  }

  @Test
  fun `other names keep the upstream initials`() {
    assertEquals("JS", TellomiNames.abbreviation("John Smith"))
    assertEquals("j", TellomiNames.abbreviation("john"))
    // 混写不算中文名，照上游取两个词的首字
    assertEquals("张S", TellomiNames.abbreviation("张 San"))
    assertEquals("は", TellomiNames.abbreviation("はな"))
  }

  /**
   * 两端同一组样例（iOS `TellomiAvatarInitialsTest.testSingleFieldSamplesSharedWithAndroid` 逐条相同）。
   * 注册资料页只剩一个框，全名都存在 given name 里，默认头像上的字只看这一个串（taishi 审查 2026-09-24）。
   */
  @Test
  fun `single field samples shared with ios`() {
    val samples = listOf(
      "欧阳娜娜" to "娜娜",
      "张三" to "张三",
      "李" to "李",
      "陈 志明" to "志明",
      "马克·卡尔" to "卡尔",
      "张 三" to "张三",
      "Kevin Zhang" to "KZ",
      "John Smith" to "JS",
      "Kevin 张" to "K张",
      "小明 Wang" to "小W",
      "娜娜😀" to "娜",
      "😀" to "😀",
      "John" to "J",
      "" to null,
      "·" to null
    )
    samples.forEach { (input, expected) -> assertEquals(input, expected, TellomiNames.abbreviation(input)) }
  }

  @Test
  fun `blank names have no abbreviation`() {
    assertNull(TellomiNames.abbreviation(""))
    assertNull(TellomiNames.abbreviation("   "))
    assertNull(TellomiNames.abbreviation("·"))
  }

  @Test
  fun `single field joins family first without a space only when both parts are cjk`() {
    assertEquals("张三", TellomiNames.joinForSingleField("三", "张"))
    assertEquals("Alice Anderson", TellomiNames.joinForSingleField("Alice", "Anderson"))
    assertEquals("Ming 李", TellomiNames.joinForSingleField("Ming", "李"))
    assertEquals("Alice", TellomiNames.joinForSingleField(" Alice ", ""))
    assertEquals("张", TellomiNames.joinForSingleField("", "张"))
    assertEquals("", TellomiNames.joinForSingleField("", ""))
  }
}
