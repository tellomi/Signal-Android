/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.usernamelinks

import androidx.compose.ui.unit.IntOffset
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#947）：用户名二维码默认不带中心标，生成的码要能解出原文。
 * 带标为什么不用，见 [QrCodeData.forData] 的注释。
 */
class QrCodeDataTest {

  @Test
  fun `username links render without the center overlay by default`() {
    val data = QrCodeData.forData("https://tell.cc/u#eu/" + "A".repeat(64))

    assertFalse(data.canSupportIconOverlay)
  }

  @Test
  fun `default QR data decodes back to the same link`() {
    repeat(30) {
      val link = "https://tell.cc/u#eu/" + (1..64).map { ALPHABET.random() }.joinToString("")

      val decoded = decode(QrCodeData.forData(link))

      assertEquals(link, decoded)
    }
  }

  private fun decode(data: QrCodeData): String {
    // 每个模块放大成 4×4 像素，四周留 4 个模块的静区，和屏幕上扫码时的条件接近。
    val scale = 4
    val quietZone = 4 * scale
    val size = data.width * scale + quietZone * 2
    val matrix = BitMatrix(size, size)
    for (x in 0 until data.width) {
      for (y in 0 until data.height) {
        if (data.get(IntOffset(x, y))) {
          matrix.setRegion(quietZone + x * scale, quietZone + y * scale, scale, scale)
        }
      }
    }

    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) BLACK else WHITE }
    val source = RGBLuminanceSource(size, size, pixels)
    val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to true))
    return result.text
  }

  private companion object {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    val ALPHABET = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')
  }
}
