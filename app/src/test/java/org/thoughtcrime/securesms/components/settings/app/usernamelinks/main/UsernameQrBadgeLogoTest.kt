/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.usernamelinks.main

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.QrCodeData
import java.util.BitSet

/**
 * Tellomi（tellomi/tellomi#947）：屏幕上的用户名码去了中心标，保存 / 分享出去的那张图也得去掉——
 * 不然 logo 直接压在数据模块上（码生成时已经不挖空了），发到微信里的图扫不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UsernameQrBadgeLogoTest {

  private val resources = ApplicationProvider.getApplicationContext<Application>().resources

  @Test
  fun `the shared badge of a username code has no center logo`() {
    val canvas = mockk<Canvas>(relaxed = true)

    drawBadgeCenterLogo(canvas, resources, QrCodeData.forData("https://tell.cc/u#eu/" + "A".repeat(64)), Color.BLACK, 848, 2)

    verify(exactly = 0) { canvas.drawBitmap(any<Bitmap>(), any<Rect>(), any<RectF>(), any<Paint>()) }
  }

  @Test
  fun `a code generated with room for the logo still gets it`() {
    val canvas = mockk<Canvas>(relaxed = true)

    drawBadgeCenterLogo(canvas, resources, QrCodeData(1, 1, canSupportIconOverlay = true, BitSet()), Color.BLACK, 848, 2)

    verify(exactly = 1) { canvas.drawBitmap(any<Bitmap>(), any<Rect>(), any<RectF>(), any<Paint>()) }
  }
}
