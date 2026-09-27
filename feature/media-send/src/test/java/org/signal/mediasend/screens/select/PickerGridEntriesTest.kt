/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.mediasend.MediaSendDependenciesRule

/**
 * Tellomi（tellomi/tellomi#1261 P-8）：网格下标 → 媒体。有相机时照 Telegram Android：第 0 格相机、第一行剩下的、
 * 第二行第 0 格是被相机盖住的占位、然后其余的；横幅在最前面。不是媒体的格子是 null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PickerGridEntriesTest {

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val files = (0 until 8).map { media(it) }

  @Test
  fun `Camera access follows the device camera and the camera permission`() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val packageManager = shadowOf(application.packageManager)

    packageManager.setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
    assertEquals("没有相机：不挖这一格", PickerCameraAccess.NONE, PickerCameraAccess.current())

    packageManager.setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
    assertEquals("有相机没权限：相机图标", PickerCameraAccess.NOT_GRANTED, PickerCameraAccess.current())

    shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
    assertEquals("有权限：实时取景", PickerCameraAccess.GRANTED, PickerCameraAccess.current())
  }

  @Test
  fun `Without the camera, the grid is just the files`() {
    assertEquals(files, pickerGridEntries(files, showBanner = false, showCamera = false, columns = 3))
    assertEquals(listOf(null) + files, pickerGridEntries(files, showBanner = true, showCamera = false, columns = 3))
  }

  @Test
  fun `With the camera in three columns, the second row starts with the placeholder under it`() {
    val entries = pickerGridEntries(files, showBanner = false, showCamera = true, columns = 3)
    assertEquals(listOf(null, files[0], files[1], null, files[2], files[3], files[4], files[5], files[6], files[7]), entries)
  }

  @Test
  fun `With the banner and the camera in four columns (landscape), both are skipped`() {
    val entries = pickerGridEntries(files, showBanner = true, showCamera = true, columns = 4)
    assertEquals(listOf(null, null, files[0], files[1], files[2], null, files[3], files[4], files[5], files[6], files[7]), entries)
  }

  @Test
  fun `With fewer files than a row, the placeholder follows them`() {
    val entries = pickerGridEntries(files.take(1), showBanner = false, showCamera = true, columns = 3)
    assertEquals(listOf(null, files[0], null), entries)
  }

  private fun media(index: Int) = Media(
    uri = "content://media/$index".toUri(),
    contentType = "image/jpeg",
    date = index.toLong(),
    width = 100,
    height = 100,
    size = 1024,
    duration = 0,
    isBorderless = false,
    isVideoGif = false,
    bucketId = "bucket",
    caption = null,
    transformProperties = null,
    fileName = "media_$index.jpg"
  )
}
