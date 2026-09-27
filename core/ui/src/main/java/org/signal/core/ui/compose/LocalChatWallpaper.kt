/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Tellomi（tellomi/tellomi#1261 P-3）：在 app 层绑定，画某个会话的聊天背景（壁纸；没设壁纸就是会话的默认底色）。
 *
 * 同 [LocalChatColorProvider]：RecipientId 与 ChatWallpaper 都在 app 模块里，这里只用裸类型。收件人不确定（null）时用全局壁纸。
 * 没绑定时（预览、测试）画会话的默认底色。
 */
val LocalChatWallpaper = compositionLocalOf<@Composable (recipientId: Long?, modifier: Modifier) -> Unit> {
  { _, modifier -> Box(modifier = modifier.background(MaterialTheme.colorScheme.surface)) }
}
