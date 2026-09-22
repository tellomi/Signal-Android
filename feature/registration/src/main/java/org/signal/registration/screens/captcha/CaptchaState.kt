/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.captcha

import org.signal.core.util.censor

sealed class CaptchaLoadState {
  data object Loading : CaptchaLoadState()
  data object Loaded : CaptchaLoadState()
  data object Error : CaptchaLoadState()
}

data class CaptchaState(
  val captchaUrl: String,
  /**
   * Tellomi：验证页做完之后回跳的自定义 scheme。
   *
   * **新旧两种都要认**（两阶段迁移，见 docs/signal/LINKS_AND_SCHEMES.md）：验证页是服务端托管的，
   * 客户端与服务端不会同时发版。只认一种的话，另一种回跳会被 WebView 当成未知协议，
   * 页面上是 `net::ERR_UNKNOWN_URL_SCHEME`，用户卡在验证页过不去——2026-09-22 在无 GMS 模拟器上实测撞到。
   *
   * app 模块里的 util/TellomiLinks.kt 有同一份常量，但 feature:registration 反过来不依赖 app，
   * 所以这里只能各写一份；改的时候两边一起改。
   */
  val captchaScheme: String = "tellomicaptcha://",
  val legacyCaptchaScheme: String = "signalcaptcha://",
  val loadState: CaptchaLoadState = CaptchaLoadState.Loading
) {
  /** 命中的那个前缀（新的优先），都不命中返回 null。 */
  fun captchaSchemeOf(url: String): String? = when {
    url.startsWith(captchaScheme) -> captchaScheme
    url.startsWith(legacyCaptchaScheme) -> legacyCaptchaScheme
    else -> null
  }

  override fun toString(): String = "CaptchaState(captchaUrl=${captchaUrl.censor()}, captchaScheme=$captchaScheme, loadState=$loadState)"
}
