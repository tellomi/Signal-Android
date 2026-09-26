/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.video.exo

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

fun ExoPlayer.configureForVideoPlayback() {
  repeatMode = Player.REPEAT_MODE_OFF
  volume = 1f
  // Tellomi（#1257）：查看器的倍速只在那一次查看器里有效，播放器回到池里再借出去要按正常速度播
  setPlaybackSpeed(1f)
  trackSelectionParameters = trackSelectionParameters.buildUpon()
    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
    .build()
}
