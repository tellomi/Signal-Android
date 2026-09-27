/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.net.Uri
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder

internal fun NavBackStack<NavKey>.goToEdit() {
  goToSingle(MediaSendRoute.Edit)
}

internal fun NavBackStack<NavKey>.goToSend() {
  goToSingle(MediaSendRoute.Send)
}

internal fun NavBackStack<NavKey>.goToFolders() {
  goToSingle(MediaSendRoute.Select.Folders)
}

internal fun NavBackStack<NavKey>.goToFiles(mediaFolder: MediaFolder) {
  add(MediaSendRoute.Select.Files(mediaFolder))
}

/**
 * Tellomi（tellomi/tellomi#1261 P-1）：选图页直接是「最近」网格（照 Telegram），相册在顶栏「最近 ⌄」里换，不再先进相册列表。
 * 路由上用一个只有 bucketId 的「全部媒体」相册代表「最近」，标题由界面按 bucketId 显示成「最近」。
 */
internal fun recentsFolder(): MediaFolder = MediaFolder(
  thumbnailUri = Uri.EMPTY,
  title = "",
  itemCount = 0,
  bucketId = Media.ALL_MEDIA_BUCKET_ID,
  folderType = MediaFolder.FolderType.NORMAL
)

/** Tellomi（#1261）：回到选图网格——栈里已有就退回到它，没有就开一个「最近」。 */
internal fun NavBackStack<NavKey>.goToRecents() {
  val existing = lastOrNull { it is MediaSendRoute.Select.Files }
  if (existing != null) {
    popTo(existing)
  } else {
    add(MediaSendRoute.Select.Files(recentsFolder()))
  }
}

/** Tellomi（#1261 P-1）：顶栏下拉里换相册——换掉当前的选图页，不压栈，返回时不会一路退回各个相册。 */
internal fun NavBackStack<NavKey>.switchFolder(mediaFolder: MediaFolder) {
  val route = MediaSendRoute.Select.Files(mediaFolder)
  if (lastOrNull() is MediaSendRoute.Select.Files) {
    set(size - 1, route)
  } else {
    add(route)
  }
}

internal fun NavBackStack<NavKey>.goToTextStory() {
  goToSingle(MediaSendRoute.Capture.TextStory)
}

internal fun NavBackStack<NavKey>.goToCamera() {
  goToSingle(MediaSendRoute.Capture.Camera)
}

/**
 * Discards the entire back stack in favor of [key], which becomes the flow's root. Backing out of it leaves the flow.
 */
internal fun NavBackStack<NavKey>.resetTo(key: NavKey) {
  clear()
  add(key)
}

internal fun NavBackStack<NavKey>.pop() {
  if (isNotEmpty()) {
    removeAt(size - 1)
  }
}

private fun NavBackStack<NavKey>.goToSingle(key: NavKey) {
  if (contains(key)) {
    popTo(key)
  } else {
    add(key)
  }
}

private fun NavBackStack<NavKey>.popTo(key: NavKey) {
  while (size > 1 && get(size - 1) != key) {
    removeAt(size - 1)
  }
}
