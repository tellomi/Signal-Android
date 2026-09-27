/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R

/**
 * Tellomi（#1257）：查看器新加的文案，粤语界面也要有（不回落成英文）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiMediaPreviewStringsTest {

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  @Test
  @Config(qualifiers = "yue")
  fun `cantonese viewer wording`() {
    assertThat(context.getString(R.string.MediaPreviewFragment__forward_this_photo)).isEqualTo("呢張相片")
    assertThat(context.getString(R.string.MediaPreviewFragment__forward_this_video)).isEqualTo("呢段影片")
    assertThat(context.resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_photos, 3, 3)).isEqualTo("全部 3 張相片")
    assertThat(context.resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_videos, 2, 2)).isEqualTo("全部 2 段影片")
    assertThat(context.resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_items, 4, 4)).isEqualTo("全部 4 項")
    assertThat(context.getString(R.string.MediaPreviewFragment__playback_speed)).isEqualTo("播放速度")
    assertThat(context.getString(R.string.MediaPreviewFragment__speed)).isEqualTo("速度")
    assertThat(context.getString(R.string.MediaPreviewFragment__speed_normal)).isEqualTo("正常")
    assertThat(context.getString(R.string.MediaPreviewFragment__share)).isEqualTo("分享")
    assertThat(context.getString(R.string.AlbumCarouselView__item_d_of_d, 1, 5)).isEqualTo("第 1 項，共 5 項")
  }
}
