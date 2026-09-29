/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The resource side of [TellomiLinkDisplay.Strings] (tellomi/tellomi#1422), pinned to the finalized card spec
 * (card-visual §3.9 / §3.10, 2026-09-29) in the four languages the app ships: the brand shell's kind names,
 * the track count and the place fallback, and the publish date format.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkDisplayStringsTest {

  private val strings get() = TellomiLinkDisplay.Strings.from(ApplicationProvider.getApplicationContext())

  /** card-visual §3.10, in kinds.toml order. */
  private val kinds = listOf(
    "video", "channel", "music.track", "music.album", "music.playlist", "place", "app", "repo",
    "article", "product", "package", "question", "deal", "ride", "payment", "web"
  )

  private fun assertKindNames(vararg names: String) {
    assertEquals(kinds.size, names.size)
    kinds.zip(names).forEach { (kind, name) -> assertEquals(kind, name, strings.kindName(kind)) }
    assertNull("no name for a kind the table does not have", strings.kindName("podcast"))
  }

  @Test
  fun `english`() {
    assertKindNames(
      "Video", "Channel", "Song", "Album", "Playlist", "Place", "App", "Repository",
      "Article", "Product", "Package", "Q&A", "Deal", "Ride", "Payment", "Web page"
    )
    assertEquals("1 track", strings.trackCount(1))
    assertEquals("12 tracks", strings.trackCount(12))
    assertEquals("1,234 tracks", strings.trackCount(1234))
    assertEquals("Location", strings.place)
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `simplified chinese`() {
    assertKindNames(
      "视频", "频道", "单曲", "专辑", "歌单", "地点", "应用", "代码仓库",
      "文章", "商品", "快递", "问答", "团购", "行程", "支付", "网页"
    )
    assertEquals("12 首", strings.trackCount(12))
    assertEquals("位置", strings.place)
  }

  @Test
  @Config(qualifiers = "zh-rHK")
  fun `traditional chinese, hong kong`() {
    assertKindNames(
      "影片", "頻道", "單曲", "專輯", "歌單", "地點", "應用程式", "程式碼倉庫",
      "文章", "商品", "包裹", "問答", "團購", "行程", "付款", "網頁"
    )
    assertEquals("12 首", strings.trackCount(12))
    assertEquals("位置", strings.place)
  }

  @Test
  @Config(qualifiers = "zh-rTW")
  fun `traditional chinese, taiwan`() {
    assertKindNames(
      "影片", "頻道", "單曲", "專輯", "播放清單", "地點", "App", "程式碼倉庫",
      "文章", "商品", "包裹", "問答", "團購", "行程", "付款", "網頁"
    )
    assertEquals("12 首", strings.trackCount(12))
    assertEquals("位置", strings.place)
  }

  /**
   * The localized pattern comes from the platform (`DateFormat.getBestDateTimePattern`, the same way Signal's
   * DateUtils formats dates), so this pins the rule rather than one locale's exact text: the year shows only
   * when it is not this year.
   */
  @Test
  fun `a publish date has no year when it is this year`() {
    val utc = TimeZone.getTimeZone("UTC")
    val previous = TimeZone.getDefault()
    TimeZone.setDefault(utc)
    try {
      val now = millis(2026, Calendar.SEPTEMBER, 29)
      for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE, Locale.forLanguageTag("zh-HK"), Locale.forLanguageTag("zh-TW"))) {
        val thisYear = TellomiLinkDisplay.formatDate(millis(2026, Calendar.SEPTEMBER, 3), locale, now)
        assertFalse("$locale: $thisYear", thisYear.contains("2026"))
        assertTrue("$locale: $thisYear", thisYear.contains("3"))

        val lastYear = TellomiLinkDisplay.formatDate(millis(2025, Calendar.DECEMBER, 1), locale, now)
        assertTrue("$locale: $lastYear", lastYear.contains("2025"))
      }
    } finally {
      TimeZone.setDefault(previous)
    }
  }

  private fun millis(year: Int, month: Int, day: Int): Long {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
      clear()
      set(year, month, day, 12, 0)
    }.timeInMillis
  }
}
