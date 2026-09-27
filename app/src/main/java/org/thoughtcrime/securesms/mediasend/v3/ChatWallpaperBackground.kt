/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.rememberRecipientField
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper
import org.thoughtcrime.securesms.wallpaper.ChatWallpaperDimLevelUtil

/**
 * Tellomi（tellomi/tellomi#1261 P-3）：选图面板「只看已选」铺的聊天背景 = 这个会话的壁纸（会话没单独设就是全局的，
 * 都没有就是默认底色）；夜间按壁纸自己的暗化程度压暗，同会话页。绑定到 core/ui 的 LocalChatWallpaper。
 */
@Composable
fun ChatWallpaperBackground(recipientId: Long?, modifier: Modifier) {
  val wallpaper: ChatWallpaper? = if (recipientId != null) {
    rememberRecipientField(RecipientId.from(recipientId)) { wallpaper }.value
  } else {
    remember { SignalStore.wallpaper.getWallpaper() }
  }

  if (wallpaper == null) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surface))
    return
  }

  AndroidView(
    factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
    update = { view ->
      wallpaper.loadInto(view)
      view.colorFilter = ChatWallpaperDimLevelUtil.getDimColorFilterForNightMode(view.context, wallpaper)
    },
    modifier = modifier
  )
}
