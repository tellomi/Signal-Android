/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.motion

import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.ui.motion.TellomiMotion.DeviceSignals
import org.signal.core.ui.motion.TellomiMotion.SpringToken
import org.signal.core.ui.motion.TellomiMotion.Tier
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

class TellomiMotionTest {

  private val goodPhone = DeviceSignals(animatorsEnabled = true, powerSave = false, lowRamDevice = false, memoryClassMb = 256, sdkInt = 34)

  /** 数值必须等于 Material 3 MotionScheme 的官方 token（Standard / Expressive），改了就是和标准脱节。 */
  @Test
  fun `tokens match the Material 3 motion scheme values the standard names`() {
    assertEquals(SpringToken(1400f, 0.9f), TellomiMotion.Press) // Standard 空间快
    assertEquals(SpringToken(3800f, 1f), TellomiMotion.PressHighlight) // Effects 快
    assertEquals(SpringToken(800f, 0.6f), TellomiMotion.Release) // Expressive 空间快
    assertEquals(SpringToken(800f, 0.6f), TellomiMotion.Snap)
    assertEquals(SpringToken(380f, 0.8f), TellomiMotion.Move) // Expressive 空间默认
    assertEquals(SpringToken(200f, 0.8f), TellomiMotion.Large) // Expressive 空间慢
    assertEquals(SpringToken(800f, 0.6f), TellomiMotion.Emphasis)
    assertEquals(SpringToken(1600f, 1f), TellomiMotion.Fade) // Effects 默认
  }

  /** 按下、滚动位置、透明度这三类不许有看得见的回弹（标准第三节）。 */
  @Test
  fun `press scroll and fade have no visible overshoot`() {
    assertTrue(overshoot(TellomiMotion.Press) < 0.002)
    assertEquals(0.0, overshoot(TellomiMotion.Scroll), 0.0)
    assertEquals(0.0, overshoot(TellomiMotion.PressHighlight), 0.0)
    assertEquals(0.0, overshoot(TellomiMotion.Fade), 0.0)
  }

  /** owner 2026-09-26 选了「活泼」：落定与强调类要有看得见的回弹。 */
  @Test
  fun `lively tokens do bounce`() {
    assertTrue(overshoot(TellomiMotion.Snap) > 0.05)
    assertTrue(overshoot(TellomiMotion.Emphasis) > 0.05)
    assertTrue(overshoot(TellomiMotion.Move) > 0.01)
  }

  @Test
  fun `perceptual duration converts the way iOS duration and bounce do`() {
    assertEquals(2 * PI / sqrt(380.0), TellomiMotion.Move.perceptualDurationSeconds, 1e-9)
    assertEquals(0.322, TellomiMotion.Move.perceptualDurationSeconds, 0.001)
    assertEquals(0.2f, TellomiMotion.Move.bounce, 1e-6f)
  }

  @Test
  fun `compose spec carries the token unchanged`() {
    val spec: SpringSpec<Float> = TellomiMotion.spec(TellomiMotion.Move)
    assertEquals(380f, spec.stiffness, 0f)
    assertEquals(0.8f, spec.dampingRatio, 0f)
  }

  @Test
  fun `a capable phone gets the full tier`() {
    assertEquals(Tier.FULL, TellomiMotion.tierFor(goodPhone))
    assertTrue(TellomiMotion.allowsLiveBlur(Tier.FULL))
    assertTrue(TellomiMotion.allowsFlourish(Tier.FULL))
  }

  @Test
  fun `animations turned off wins over everything`() {
    assertEquals(Tier.MINIMAL, TellomiMotion.tierFor(goodPhone.copy(animatorsEnabled = false)))
    assertEquals(Tier.MINIMAL, TellomiMotion.tierFor(goodPhone.copy(animatorsEnabled = false, powerSave = true, lowRamDevice = true)))
  }

  @Test
  fun `battery saver low ram small heap and pre-S phones are simplified`() {
    assertEquals(Tier.SIMPLIFIED, TellomiMotion.tierFor(goodPhone.copy(powerSave = true)))
    assertEquals(Tier.SIMPLIFIED, TellomiMotion.tierFor(goodPhone.copy(lowRamDevice = true)))
    assertEquals(Tier.SIMPLIFIED, TellomiMotion.tierFor(goodPhone.copy(memoryClassMb = TellomiMotion.LOW_MEMORY_CLASS_MB)))
    assertEquals(Tier.SIMPLIFIED, TellomiMotion.tierFor(goodPhone.copy(sdkInt = 30)))
  }

  /** memoryClass 拿不到（0）时不因此降档，由其它信号决定。 */
  @Test
  fun `an unknown memory class does not demote the phone`() {
    assertEquals(Tier.FULL, TellomiMotion.tierFor(goodPhone.copy(memoryClassMb = 0)))
    assertEquals(Tier.FULL, TellomiMotion.tierFor(goodPhone.copy(memoryClassMb = TellomiMotion.LOW_MEMORY_CLASS_MB + 1)))
  }

  @Test
  fun `only the full tier blurs or plays flourishes`() {
    assertFalse(TellomiMotion.allowsLiveBlur(Tier.SIMPLIFIED))
    assertFalse(TellomiMotion.allowsLiveBlur(Tier.MINIMAL))
    assertFalse(TellomiMotion.allowsFlourish(Tier.SIMPLIFIED))
    assertFalse(TellomiMotion.allowsFlourish(Tier.MINIMAL))
  }

  /** 欠阻尼弹簧从 0 到 1 的最大过冲比例：exp(−πζ / √(1 − ζ²))；ζ ≥ 1 时为 0。 */
  private fun overshoot(token: SpringToken): Double {
    val z = token.dampingRatio.toDouble()
    return if (z >= 1.0) 0.0 else exp(-PI * z / sqrt(1 - z * z))
  }
}
