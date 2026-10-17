/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.motion.TellomiMotion

/**
 * Tellomi (interaction audit A-27): switching the chat list to search results and back fades the new content in instead of swapping it in
 * one frame. Parameters follow Telegram Android's `DialogsActivity.showSearch`: search results settle from 1.05, the chat list from 0.95,
 * 200 / 180 ms ease-out; the simplified tier only fades (Telegram's low-end path), the minimal tier does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiSearchResultsTransitionTest {

  @Test
  fun full_tier_scales_and_fades_like_telegram() {
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = true, tier = TellomiMotion.Tier.FULL))
      .isEqualTo(TellomiSearchResultsTransition.Spec(animate = true, durationMs = 200L, startScale = 1.05f))
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = false, tier = TellomiMotion.Tier.FULL))
      .isEqualTo(TellomiSearchResultsTransition.Spec(animate = true, durationMs = 180L, startScale = 0.95f))
  }

  @Test
  fun simplified_tier_only_fades() {
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = true, tier = TellomiMotion.Tier.SIMPLIFIED))
      .isEqualTo(TellomiSearchResultsTransition.Spec(animate = true, durationMs = 200L, startScale = 1f))
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = false, tier = TellomiMotion.Tier.SIMPLIFIED))
      .isEqualTo(TellomiSearchResultsTransition.Spec(animate = true, durationMs = 180L, startScale = 1f))
  }

  @Test
  fun minimal_tier_does_not_animate() {
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = true, tier = TellomiMotion.Tier.MINIMAL).animate).isEqualTo(false)
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = false, tier = TellomiMotion.Tier.MINIMAL).animate).isEqualTo(false)
  }

  @Test
  fun play_queues_the_fade_in_with_the_tier_duration() {
    val list = View(ApplicationProvider.getApplicationContext<Application>())

    TellomiSearchResultsTransition.play(list, toSearch = true)
    assertThat(list.animate().duration).isEqualTo(200L)

    TellomiSearchResultsTransition.play(list, toSearch = false)
    assertThat(list.animate().duration).isEqualTo(180L)
  }
}
