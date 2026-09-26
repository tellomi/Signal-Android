/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.motion

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import org.signal.core.util.AccessibilityUtil
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Tellomi 交互与动效标准（tellomi/tellomi `docs/product/INTERACTION_MOTION.md` 第三节）在 Android 上的数值。
 *
 * 页面代码不写时长和曲线，只从这里取。数值取自 Material 3 MotionScheme 的官方 token：
 * owner 2026-09-26 定的弹簧性格是「活泼」，所以空间类用 Expressive 一档，效果类（透明度、颜色）用 Standard，永不过冲。
 * 两处不是 M3 预设：[Move] 的阻尼按 owner「明显回弹，两端一致」对齐 iOS；[Scroll] 是临界阻尼（M3 没有滚动定位的 token）。
 * 不依赖仍是 alpha 的 Compose Expressive API，常量写在这里。
 *
 * View 体系用同样两个数：`SpringForce().setStiffness(token.stiffness).setDampingRatio(token.dampingRatio)`。
 */
object TellomiMotion {

  /** 一根弹簧：[stiffness] 与 [dampingRatio]（1 = 临界阻尼，不回弹）。 */
  data class SpringToken(val stiffness: Float, val dampingRatio: Float) {
    /** 与 iOS `(duration, bounce)` 对照：duration = 2π / √stiffness（质量 1）。 */
    val perceptualDurationSeconds: Double get() = 2 * PI / sqrt(stiffness.toDouble())

    /** 与 iOS 对照：bounce = 1 − dampingRatio。 */
    val bounce: Float get() = 1f - dampingRatio
  }

  /** 按下缩放。按下永远不回弹：Standard 空间快。 */
  val Press = SpringToken(stiffness = 1400f, dampingRatio = 0.9f)

  /** 按下高亮（透明度 / 颜色）：Effects 快。 */
  val PressHighlight = SpringToken(stiffness = 3800f, dampingRatio = 1f)

  /** 松开弹回：Expressive 空间快。 */
  val Release = SpringToken(stiffness = 800f, dampingRatio = 0.6f)

  /** 小元素出现 / 消失、胶囊、角标：Expressive 空间快。 */
  val Snap = SpringToken(stiffness = 800f, dampingRatio = 0.6f)

  /**
   * 菜单、Sheet 落档、共享元素、列表补位：刚度取 Expressive 空间默认，阻尼 0.7——回弹约 5%，看得出来，
   * 与 iOS `move` 同一个回弹（owner 2026-09-26 定「明显回弹，两端一致」；M3 预设的 0.8 只回弹 1.5%）。
   */
  val Move = SpringToken(stiffness = 380f, dampingRatio = 0.7f)

  /** 整屏级（查看器开合）：Expressive 空间慢，回弹约 1.5%，与 iOS `large` 同一个回弹。 */
  val Large = SpringToken(stiffness = 200f, dampingRatio = 0.8f)

  /** 回应落定、一次性成功：Expressive 空间快。 */
  val Emphasis = SpringToken(stiffness = 800f, dampingRatio = 0.6f)

  /** 透明度、颜色：Effects 默认，永不过冲。 */
  val Fade = SpringToken(stiffness = 1600f, dampingRatio = 1f)

  /** 滚动位置（跳转、回到底部）。滚动位置永远不回弹：临界阻尼，不是 M3 预设。 */
  val Scroll = SpringToken(stiffness = 300f, dampingRatio = 1f)

  /** Compose 用的弹簧规格。「移除动画」打开时 Compose 自己把动画缩到零，这里不用再判断。 */
  fun <T> spec(token: SpringToken, visibilityThreshold: T? = null): SpringSpec<T> {
    return spring(dampingRatio = token.dampingRatio, stiffness = token.stiffness, visibilityThreshold = visibilityThreshold)
  }

  /**
   * 动效档位（标准第七节）：
   * - [FULL]：全部动效。实时模糊另要 API 31+（[allowsLiveBlur]）；Android 11 及以下也是完整档，只是不模糊（owner 2026-09-26）。
   * - [SIMPLIFIED]：省电模式、低内存机。关实时模糊、循环 / 背景动画、飞行类动画（例如回应飞入），共享元素改淡入淡出；弹簧照常。
   * - [MINIMAL]：用户关了动画（「移除动画」/ 动画时长缩放为 0），只保留跟手与状态变化。
   */
  enum class Tier { FULL, SIMPLIFIED, MINIMAL }

  /** 判档要用的设备信号；拆出来是为了判档规则能脱离设备单测。 */
  data class DeviceSignals(
    val animatorsEnabled: Boolean,
    val powerSave: Boolean,
    val lowRamDevice: Boolean,
    /** `ActivityManager.getMemoryClass()`，单位 MB；拿不到时为 0。 */
    val memoryClassMb: Int
  )

  /** 每个应用堆上限不超过这个值（MB）的机器按低端处理。 */
  const val LOW_MEMORY_CLASS_MB = 128

  fun tierFor(signals: DeviceSignals): Tier {
    return when {
      !signals.animatorsEnabled -> Tier.MINIMAL
      signals.powerSave -> Tier.SIMPLIFIED
      signals.lowRamDevice -> Tier.SIMPLIFIED
      signals.memoryClassMb in 1..LOW_MEMORY_CLASS_MB -> Tier.SIMPLIFIED
      else -> Tier.FULL
    }
  }

  fun signals(context: Context): DeviceSignals {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val animatorsEnabled = if (Build.VERSION.SDK_INT >= 26) {
      ValueAnimator.areAnimatorsEnabled()
    } else {
      !AccessibilityUtil.areAnimationsDisabled(context)
    }
    return DeviceSignals(
      animatorsEnabled = animatorsEnabled,
      powerSave = powerManager?.isPowerSaveMode == true,
      lowRamDevice = activityManager?.isLowRamDevice == true,
      memoryClassMb = activityManager?.memoryClass ?: 0
    )
  }

  fun tier(context: Context): Tier = tierFor(signals(context))

  /**
   * 是否允许实时模糊。owner 2026-09-26 定：Android 长按消息的背景按档位，只有「完整」档模糊，其余只压暗；
   * 实时模糊（RenderEffect）要 API 31+，更早的系统完整档也只压暗。
   */
  fun allowsLiveBlur(tier: Tier, sdkInt: Int = Build.VERSION.SDK_INT): Boolean = tier == Tier.FULL && sdkInt >= Build.VERSION_CODES.S

  /** 是否播放飞行类、循环类等装饰性更强的动效（例如回应表情飞到胶囊）。简化档与最少档只做落点处的状态变化。 */
  fun allowsFlourish(tier: Tier): Boolean = tier == Tier.FULL
}
