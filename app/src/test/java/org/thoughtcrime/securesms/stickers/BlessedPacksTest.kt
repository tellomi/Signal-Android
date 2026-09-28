/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.stickers

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import org.junit.Test

/**
 * tellomi/tellomi#1406: Tellomi ships no Signal sticker packs by default.
 */
class BlessedPacksTest {

  private val signalPacks = listOf(
    BlessedPacks.ZOZO,
    BlessedPacks.BANDIT,
    BlessedPacks.SWOON_HANDS,
    BlessedPacks.SWOON_FACES,
    BlessedPacks.DAY_BY_DAY,
    BlessedPacks.MY_DAILY_LIFE,
    BlessedPacks.MY_DAILY_LIFE_2,
    BlessedPacks.ROCKY_TALK,
    BlessedPacks.COZY_SEASON,
    BlessedPacks.CHUG_THE_MOUSE,
    BlessedPacks.CROCOS_FEELINGS
  )

  @Test
  fun `first launch schedules no sticker pack downloads`() {
    assertThat(BlessedPacks.getFirstInstallJobs()).isEmpty()
  }

  @Test
  fun `no Signal pack counts as blessed`() {
    signalPacks.forEach { pack ->
      assertThat(BlessedPacks.contains(pack.packId), pack.packId).isFalse()
    }
  }
}
