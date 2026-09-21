/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.appearance.appicon.util

import android.content.ComponentName
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R

// Tellomi：上游的 WHITE / COLOR / DARK / DARK_VARIANT 四个预设画的是 Signal 的标，
// 按 ADR-0061 的商标红线整套删掉（连同 manifest 的 activity-alias 与资源文件）。
// 剩下的是「伪装」图标（新闻 / 备忘 / 天气…），不含 Signal 品牌，保留。
enum class AppIconPreset(private val componentName: String, @DrawableRes val iconPreviewResId: Int, @StringRes val labelResId: Int) {
  DEFAULT(".RoutingActivity", R.drawable.ic_app_icon_default_top_preview, R.string.app_name),
  CHAT(".RoutingActivityAltChat", R.drawable.ic_app_icon_chat_top_preview, R.string.app_name),
  BUBBLES(".RoutingActivityAltBubbles", R.drawable.ic_app_icon_bubbles_top_preview, R.string.app_name),
  YELLOW(".RoutingActivityAltYellow", R.drawable.ic_app_icon_yellow_top_preview, R.string.app_name),
  NEWS(".RoutingActivityAltNews", R.drawable.ic_app_icon_news_top_preview, R.string.app_icon_label_news),
  NOTES(".RoutingActivityAltNotes", R.drawable.ic_app_icon_notes_top_preview, R.string.app_icon_label_notes),
  WEATHER(".RoutingActivityAltWeather", R.drawable.ic_app_icon_weather_top_preview, R.string.app_icon_label_weather),
  WAVES(".RoutingActivityAltWaves", R.drawable.ic_app_icon_waves_top_preview, R.string.app_icon_label_waves);

  fun getComponentName(context: Context): ComponentName {
    val applicationContext = context.applicationContext
    return ComponentName(applicationContext, "org.thoughtcrime.securesms" + componentName)
  }
}
