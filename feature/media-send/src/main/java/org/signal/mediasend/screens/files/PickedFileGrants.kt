/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import android.content.ContentResolver
import android.net.Uri

/**
 * Tellomi（tellomi/tellomi#1121 F-4）：「文件」页系统选择器挑的文件的读授权。
 *
 * 选择器（ACTION_OPEN_DOCUMENT）结果里的读授权挂在「接收结果的那个 Activity」上，也就是附件 Sheet；Sheet 一 finish 就收回。
 * 真正读文件的是会话页：取元数据很快，可之后每个文件要等上一个整份拷进附件库才轮到，多选或者文件大时早就过了 Sheet 的
 * 生命周期，读的时候 `Permission Denial`。只把授权转给会话页（ClipData + FLAG_GRANT）也挡不住「发到一半离开会话」。
 *
 * 所以 Sheet 收到结果时 [take]：转成持久授权（ACTION_OPEN_DOCUMENT 的结果带可持久标志，不挂在哪个 Activity 上）；
 * 会话页这一串发完、出错或者没发成都 [release]，不长期占着系统给每个 App 的持久授权名额。
 */
object PickedFileGrants {

  // 先红：旧行为——不转持久授权，读权限跟着 Sheet 走。
  @Suppress("UNUSED_PARAMETER")
  fun take(contentResolver: ContentResolver, uris: List<Uri>) = Unit

  // 先红：旧行为——没转过，也就没什么可放的。
  @Suppress("UNUSED_PARAMETER")
  fun release(contentResolver: ContentResolver, uris: List<Uri>) = Unit
}
