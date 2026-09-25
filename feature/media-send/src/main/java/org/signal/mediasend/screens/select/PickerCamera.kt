/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import org.signal.camera.CameraThumbnailViewfinder
import org.signal.core.models.media.Media
import org.signal.mediasend.MediaSendDependencies

/**
 * Tellomi（tellomi/tellomi#1261 P-8）：「最近」第一格的相机格该怎么显示。
 *
 * 照 Telegram Android（ChatAttachAlertPhotoLayout）：有权限是实时取景；没权限也放一格（Telegram 是「允许访问相机」按钮），
 * 这里放相机图标，点了进拍照页，由拍照页自己要权限。没有相机就不挖这一格。
 */
enum class PickerCameraAccess {
  NONE,
  NOT_GRANTED,
  GRANTED;

  companion object {
    fun current(): PickerCameraAccess {
      val application = MediaSendDependencies.application
      return when {
        !application.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> NONE
        ContextCompat.checkSelfPermission(application, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> GRANTED
        else -> NOT_GRANTED
      }
    }
  }
}

/**
 * 网格里每个下标是哪一张：横幅、相机格、相机格下面那一格（被两行高的相机盖住的占位）都是 null，不能选。
 *
 * 有相机时的顺序照 Telegram Android：第 0 格相机，接着第一行剩下的 [columns] - 1 张，第二行第 0 格是占位，然后其余的。
 */
internal fun pickerGridEntries(files: List<Media>, showBanner: Boolean, showCamera: Boolean, columns: Int): List<Media?> {
  val entries = mutableListOf<Media?>()
  if (showBanner) {
    entries += null
  }
  if (showCamera) {
    entries += null
    entries += files.take(columns - 1)
    entries += null
    entries += files.drop(columns - 1)
  } else {
    entries += files
  }
  return entries
}

/** 相机格里的实时取景；测试里换成假的（Robolectric 没有相机）。 */
internal val LocalPickerCameraViewfinder = staticCompositionLocalOf<@Composable (Modifier) -> Unit> {
  { modifier -> CameraThumbnailViewfinder(modifier) }
}
