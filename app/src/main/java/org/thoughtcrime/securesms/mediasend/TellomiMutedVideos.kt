/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend

import org.signal.core.models.media.Media

/**
 * Tellomi（tellomi/tellomi#1518 V-6，owner 2026-10-14 照 Telegram 两端）：静音的视频当 GIF 发，而且**单独成一条消息**，
 * 其余的照常成组（照 TG 安卓 `SendMessagesHelper` 静音那一项不进相册组；Signal Desktop 也只在整条消息只有一个附件时才按 GIF 画）。
 */
object TellomiMutedVideos {

  /** 发送端选了静音、所以要当 GIF 单独发的视频。键盘里来的 GIF 视频不算（它们本来就能和别的一起发）。 */
  fun isMutedGif(media: Media): Boolean {
    return media.isVideoGif && media.transformProperties?.videoMuted == true
  }

  /**
   * 按消息分组：其余的（保持顺序）一组在前，每个静音视频各一组跟在后面。只有一项、或者没有静音视频时就是原样一组。
   * 返回的是下标，调用方按下标取预上传结果或幻灯片。
   */
  fun messageGroups(media: List<Media>): List<List<Int>> {
    if (media.size < 2 || media.none(::isMutedGif)) {
      return listOf(media.indices.toList())
    }
    val (muted, rest) = media.indices.partition { isMutedGif(media[it]) }
    return listOfNotNull(rest.takeIf { it.isNotEmpty() }) + muted.map { listOf(it) }
  }
}
