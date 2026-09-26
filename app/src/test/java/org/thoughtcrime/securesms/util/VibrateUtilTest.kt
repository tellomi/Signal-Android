/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Application
import android.content.Context
import android.os.Vibrator
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 界面上的轻触感（点提及、提及列表弹出、下拉筛选）要听系统「触摸反馈」开关——
 * 直接调 Vibrator 会绕过它（tellomi/tellomi 交互审计 A-02）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class VibrateUtilTest {

  private lateinit var context: Context
  private lateinit var vibrator: Vibrator

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    vibrator = context.getSystemService(Vibrator::class.java)
  }

  @Test
  fun `a tick does not vibrate when the system touch feedback switch is off`() {
    Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)

    VibrateUtil.vibrateTick(context)

    assertFalse(shadowOf(vibrator).isVibrating)
  }

  @Test
  fun `a tick vibrates when the system touch feedback switch is on`() {
    Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)

    VibrateUtil.vibrateTick(context)

    assertTrue(shadowOf(vibrator).isVibrating)
  }
}
