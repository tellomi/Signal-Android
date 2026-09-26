/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R

/**
 * Tellomi（tellomi/tellomi#1235、#1124，taishi 审查 b9 不阻塞 1）：高德接上之前「位置」置灰。
 * 只降透明度的话，读屏念的是普通的「位置，按钮」，点了才从 Toast 听到「即将支持」；置灰的格子要把「即将支持」带在状态说明里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiAttachmentKeyboardComingSoonTest {

  @Test
  fun `the greyed location button tells the screen reader it is coming soon`() {
    val context = ContextThemeWrapper(getApplicationContext(), R.style.Signal_DayNight)
    val parent = FrameLayout(context)
    val adapter = AttachmentKeyboardButtonAdapter { }
    adapter.setButtons(listOf(AttachmentKeyboardButton.LOCATION, AttachmentKeyboardButton.GALLERY))

    val location = adapter.onCreateViewHolder(parent, 0).also { adapter.onBindViewHolder(it, 0) }
    val gallery = adapter.onCreateViewHolder(parent, 0).also { adapter.onBindViewHolder(it, 1) }

    // 测试构建没有地图 key，MAPS_AVAILABLE 为 false；给了 key 的构建里「位置」可用，也就没有这句说明。
    val expected = if (BuildConfig.MAPS_AVAILABLE) null else context.getString(R.string.TellomiLocation__coming_soon)
    assertEquals(expected, ViewCompat.getStateDescription(location.itemView)?.toString())
    assertNull(ViewCompat.getStateDescription(gallery.itemView))
  }
}
