/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.video.exo

import android.app.Application
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.video.exo.ExoPlayerPool

/**
 * Tellomi（#1257）：查看器的倍速只在这一次查看器里有效。播放器是从池里借的，还回去以后
 * 聊天里自动播放的视频、GIF 再借到同一个播放器，要按正常速度播。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiExoPlayerPoolSpeedTest {

  @Test
  fun `a player lent out again after the viewer sped it up plays at normal speed`() {
    val player = mockk<ExoPlayer>(relaxUnitFun = true) {
      every { trackSelectionParameters } returns TrackSelectionParameters.DEFAULT
    }
    val pool = object : ExoPlayerPool<ExoPlayer>(0) {
      override fun createPlayer(): ExoPlayer = player

      override fun getMaxSimultaneousPlayback(): Int = 1
    }

    val viewerPlayer = pool.require("viewer")
    viewerPlayer.setPlaybackSpeed(2f)
    pool.pool(viewerPlayer)

    val nextPlayer = pool.require("conversation")

    assertSame(player, nextPlayer)
    verifyOrder {
      player.setPlaybackSpeed(2f)
      player.setPlaybackSpeed(1f)
    }
  }
}
