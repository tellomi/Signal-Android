/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal object FileTypeIconMetrics {
  val side = 40.dp
  val foldSide = 12.dp
  val cornerRadius = 6.dp
}

/**
 * Tellomi（tellomi/tellomi#1121 F-6）：40dp 带折角的文件图标，底部写扩展名；颜色按扩展名分组（同 iOS 端，
 * 分组照 Telegram iOS `ListMessageFileItemNode` 的机制：演示 / PDF 红、表格绿、压缩包橙、其它蓝；图形独立画的）。
 */
@Composable
internal fun FileTypeIcon(fileName: String, modifier: Modifier = Modifier) {
  val fileExtension = FileTypeIcons.extensionOf(fileName)
  val tint = FileTypeIcons.tintOf(fileExtension)
  Box(
    contentAlignment = Alignment.BottomCenter,
    modifier = modifier
      .size(FileTypeIconMetrics.side)
      .clearAndSetSemantics { }
      .drawBehind {
        val fold = FileTypeIconMetrics.foldSide.toPx()
        val radius = FileTypeIconMetrics.cornerRadius.toPx()
        // 右上角切掉一个三角（折角），其余三个角是圆角
        val body = Path().apply {
          addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
          val corner = Path().apply {
            moveTo(size.width - fold, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, fold)
            close()
          }
          op(this, corner, PathOperation.Difference)
        }
        drawPath(body, tint.color)
        val flap = Path().apply {
          moveTo(size.width - fold, 0f)
          lineTo(size.width - fold, fold - radius / 2)
          quadraticTo(size.width - fold, fold, size.width - fold + radius / 2, fold)
          lineTo(size.width, fold)
          close()
        }
        drawPath(flap, Color.White.copy(alpha = 0.35f))
      }
  ) {
    if (fileExtension.isNotEmpty()) {
      BasicText(
        text = fileExtension,
        style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 10.sp, stepSize = 0.5.sp),
        modifier = Modifier.padding(start = 3.dp, end = 3.dp, bottom = 6.dp)
      )
    }
  }
}

internal object FileTypeIcons {

  enum class Tint(val color: Color) {
    RED(Color(0xFFE5484D)),
    GREEN(Color(0xFF30A46C)),
    ORANGE(Color(0xFFF76B15)),
    BLUE(Color(0xFF3E8BF0))
  }

  /** 扩展名 = 最后一个「.」之后，小写；没有就空。 */
  fun extensionOf(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    if (dot < 0 || dot == fileName.length - 1) {
      return ""
    }
    return fileName.substring(dot + 1).lowercase(Locale.ROOT)
  }

  fun tintOf(fileExtension: String): Tint {
    return when (fileExtension) {
      "ppt", "pptx", "pdf", "key" -> Tint.RED
      "xls", "xlsx", "csv", "numbers" -> Tint.GREEN
      "zip", "rar", "gz", "gzip", "7z", "tar", "ai" -> Tint.ORANGE
      else -> Tint.BLUE
    }
  }
}
