/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.motion

import android.app.Application
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.signal.core.ui.motion.TellomiHaptics.Event

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiHapticsTest {

  @Test
  fun `android 14 and later use the gesture specific constants`() {
    assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, TellomiHaptics.constantFor(Event.THRESHOLD_ACTIVATE, sdkInt = 34))
    assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE, TellomiHaptics.constantFor(Event.THRESHOLD_DEACTIVATE, sdkInt = 34))
    assertEquals(HapticFeedbackConstants.SEGMENT_TICK, TellomiHaptics.constantFor(Event.SELECTION_TICK, sdkInt = 34))
    assertEquals(HapticFeedbackConstants.TOGGLE_ON, TellomiHaptics.constantFor(Event.TOGGLE_ON, sdkInt = 34))
    assertEquals(HapticFeedbackConstants.TOGGLE_OFF, TellomiHaptics.constantFor(Event.TOGGLE_OFF, sdkInt = 34))
    assertEquals(HapticFeedbackConstants.DRAG_START, TellomiHaptics.constantFor(Event.DRAG_START, sdkInt = 34))
  }

  @Test
  fun `before android 14 gesture events fall back to a light tick`() {
    assertEquals(HapticFeedbackConstants.CLOCK_TICK, TellomiHaptics.constantFor(Event.THRESHOLD_ACTIVATE, sdkInt = 33))
    assertEquals(HapticFeedbackConstants.CLOCK_TICK, TellomiHaptics.constantFor(Event.THRESHOLD_DEACTIVATE, sdkInt = 33))
    assertEquals(HapticFeedbackConstants.CLOCK_TICK, TellomiHaptics.constantFor(Event.SELECTION_TICK, sdkInt = 33))
    assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, TellomiHaptics.constantFor(Event.TOGGLE_ON, sdkInt = 33))
    assertEquals(HapticFeedbackConstants.CLOCK_TICK, TellomiHaptics.constantFor(Event.TOGGLE_OFF, sdkInt = 33))
    assertEquals(HapticFeedbackConstants.LONG_PRESS, TellomiHaptics.constantFor(Event.DRAG_START, sdkInt = 33))
  }

  @Test
  fun `confirm and reject exist from android 11`() {
    assertEquals(HapticFeedbackConstants.CONFIRM, TellomiHaptics.constantFor(Event.CONFIRM, sdkInt = 30))
    assertEquals(HapticFeedbackConstants.REJECT, TellomiHaptics.constantFor(Event.REJECT, sdkInt = 30))
    assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, TellomiHaptics.constantFor(Event.CONFIRM, sdkInt = 29))
    assertEquals(HapticFeedbackConstants.LONG_PRESS, TellomiHaptics.constantFor(Event.REJECT, sdkInt = 29))
  }

  @Test
  fun `long press is the same on every version`() {
    for (sdk in listOf(23, 29, 30, 33, 34, 36)) {
      assertEquals(HapticFeedbackConstants.LONG_PRESS, TellomiHaptics.constantFor(Event.LONG_PRESS, sdkInt = sdk))
    }
  }

  /** 触发走 View.performHapticFeedback(常量)，不带 FLAG_IGNORE_GLOBAL_SETTING：系统关了触摸反馈就不震。 */
  @Test
  fun `perform goes through the view with the mapped constant and no override flags`() {
    val view = RecordingView(RuntimeEnvironment.getApplication())
    TellomiHaptics.perform(view, Event.CONFIRM)
    assertEquals(listOf(TellomiHaptics.constantFor(Event.CONFIRM)), view.constants)
    assertEquals(listOf(0), view.flags)
  }

  private class RecordingView(context: Context) : View(context) {
    val constants = mutableListOf<Int>()
    val flags = mutableListOf<Int>()

    override fun performHapticFeedback(feedbackConstant: Int): Boolean {
      constants += feedbackConstant
      flags += 0
      return true
    }

    override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean {
      constants += feedbackConstant
      this.flags += flags
      return true
    }
  }
}
