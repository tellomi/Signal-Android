/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.view.View
import androidx.core.view.animation.PathInterpolatorCompat
import org.signal.core.ui.motion.TellomiMotion

/**
 * Tellomi（交互审计 A-27）：会话列表 ⇄ 搜索结果换适配器时，结果区淡入，不再整块瞬换。
 *
 * 照 Telegram 安卓 `DialogsActivity.showSearch`（`searchAnimator`）：进搜索时搜索结果从 1.05 缩回 1、淡入，200 ms；
 * 退出时会话列表从 0.95 放大回 1、淡入，180 ms；都是 `CubicBezierInterpolator.EASE_OUT`（0, 0, 0.58, 1）。
 * Telegram 低端机只淡不缩，这里对应简化档；最少档（用户关了动画）不做过渡。
 * Telegram 是两层视图同时交叉淡入淡出；Tellomi 只有一个列表换适配器，所以旧内容直接让位、新内容按上面的参数淡入。
 */
object TellomiSearchResultsTransition {

  const val SHOW_SEARCH_DURATION_MS = 200L
  const val HIDE_SEARCH_DURATION_MS = 180L
  const val SEARCH_START_SCALE = 1.05f
  const val LIST_START_SCALE = 0.95f

  private val EASE_OUT by lazy { PathInterpolatorCompat.create(0f, 0f, 0.58f, 1f) }

  data class Spec(val animate: Boolean, val durationMs: Long, val startScale: Float)

  fun specFor(toSearch: Boolean, tier: TellomiMotion.Tier): Spec {
    val duration = if (toSearch) SHOW_SEARCH_DURATION_MS else HIDE_SEARCH_DURATION_MS
    return when (tier) {
      TellomiMotion.Tier.FULL -> Spec(animate = true, durationMs = duration, startScale = if (toSearch) SEARCH_START_SCALE else LIST_START_SCALE)
      TellomiMotion.Tier.SIMPLIFIED -> Spec(animate = true, durationMs = duration, startScale = 1f)
      TellomiMotion.Tier.MINIMAL -> Spec(animate = false, durationMs = 0L, startScale = 1f)
    }
  }

  /** 列表刚换上新适配器时调；[toSearch] = 换成的是搜索结果。 */
  @JvmStatic
  fun play(list: View, toSearch: Boolean) {
    val spec = specFor(toSearch, TellomiMotion.tier(list.context))
    list.animate().cancel()
    if (!spec.animate) {
      list.alpha = 1f
      list.scaleX = 1f
      list.scaleY = 1f
      return
    }
    list.alpha = 0f
    list.scaleX = spec.startScale
    list.scaleY = spec.startScale
    list.animate()
      .alpha(1f)
      .scaleX(1f)
      .scaleY(1f)
      .setDuration(spec.durationMs)
      .setInterpolator(EASE_OUT)
      .start()
  }
}
