/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.camera

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.signal.core.util.logging.Log
import androidx.camera.core.Preview as CameraPreview

private object CameraThumbnailViewfinderLog

private val TAG = Log.tag(CameraThumbnailViewfinderLog::class)

/**
 * Tellomi（tellomi/tellomi#1261 P-8）：选图网格「最近」第一格的相机实时取景。
 *
 * 只取景、不拍：后置相机、一个 Preview 用例，跟着宿主的生命周期绑定（退到后台 CameraX 自己停），离开组合就解绑；
 * 铺满、居中裁切。点了去哪由调用方决定（进拍照页时它已离开组合，拍照页再自己绑定）。调用方负责只在有相机权限时用它。
 */
@Composable
fun CameraThumbnailViewfinder(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val isInPreview = LocalInspectionMode.current
  var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

  if (!isInPreview) {
    DisposableEffect(lifecycleOwner) {
      val preview = CameraPreview.Builder().build().apply {
        setSurfaceProvider { request -> surfaceRequest = request }
      }
      var cameraProvider: ProcessCameraProvider? = null
      var disposed = false
      val providerFuture = ProcessCameraProvider.getInstance(context)
      providerFuture.addListener(
        {
          if (disposed) return@addListener
          try {
            val provider = providerFuture.get()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            cameraProvider = provider
          } catch (e: Exception) {
            Log.w(TAG, "Could not bind the picker viewfinder", e)
          }
        },
        ContextCompat.getMainExecutor(context)
      )

      onDispose {
        disposed = true
        cameraProvider?.unbind(preview)
      }
    }
  }

  Box(modifier = modifier) {
    surfaceRequest?.let { request ->
      CameraXViewfinder(
        surfaceRequest = request,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
      )
    }
  }
}
