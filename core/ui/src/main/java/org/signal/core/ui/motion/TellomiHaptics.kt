/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.motion

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 触感的唯一入口（tellomi/tellomi `docs/product/INTERACTION_MOTION.md` 第五节）。
 *
 * - 只用系统预定义常量，经 [View.performHapticFeedback] 触发，所以自动遵守系统的「触摸反馈」开关。
 *   不直接调 Vibrator，也不带 `FLAG_IGNORE_GLOBAL_SETTING`。
 * - 同一语义在所有页面给同一种感觉；只在下面这几个时刻给，滚动、消息到达、发送成功、页面切换都不给。
 */
object TellomiHaptics {

  enum class Event {
    /** 长按触发菜单 / 多选。 */
    LONG_PRESS,

    /** 越过手势阈值（滑动回复、滑动归档、下拉）。 */
    THRESHOLD_ACTIVATE,

    /** 退回阈值以内。 */
    THRESHOLD_DEACTIVATE,

    /** 滑动选择换了一项（回应条、滚轮、分段）。 */
    SELECTION_TICK,

    TOGGLE_ON,
    TOGGLE_OFF,

    /** 需要确认的完成（验证通过、录音锁定）。 */
    CONFIRM,

    /** 错误（验证码错、录音太短）。 */
    REJECT,

    /** 拿起可拖的东西。 */
    DRAG_START
  }

  /**
   * 事件 → 常量。API 不够时回落到最接近、而且更轻的老常量——官方没写回落规则，
   * 按「宁可轻，不要长振」取。版本判断用的是参数 [sdkInt]，lint 认不出来，所以压掉 InlinedApi。
   */
  @SuppressLint("InlinedApi")
  fun constantFor(event: Event, sdkInt: Int = Build.VERSION.SDK_INT): Int {
    val api30 = sdkInt >= Build.VERSION_CODES.R
    val api34 = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    return when (event) {
      Event.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
      Event.THRESHOLD_ACTIVATE -> if (api34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
      Event.THRESHOLD_DEACTIVATE -> if (api34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CLOCK_TICK
      Event.SELECTION_TICK -> if (api34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
      Event.TOGGLE_ON -> if (api34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
      Event.TOGGLE_OFF -> if (api34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
      Event.CONFIRM -> if (api30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
      Event.REJECT -> if (api30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
      Event.DRAG_START -> if (api34) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.LONG_PRESS
    }
  }

  /** 触发一次。返回系统是否执行了（系统关了触摸反馈时为 false）。 */
  fun perform(view: View, event: Event): Boolean {
    return view.performHapticFeedback(constantFor(event))
  }
}

/** Compose 里取一个触发器：`val haptics = rememberTellomiHaptics(); haptics(TellomiHaptics.Event.CONFIRM)`。 */
@Composable
fun rememberTellomiHaptics(): (TellomiHaptics.Event) -> Unit {
  val view = LocalView.current
  return remember(view) { { event -> TellomiHaptics.perform(view, event) } }
}
