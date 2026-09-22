/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.ui.captcha

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.navigation.fragment.findNavController
import org.signal.core.ui.logging.LoggingFragment
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ViewBinderDelegate
import org.thoughtcrime.securesms.databinding.FragmentRegistrationCaptchaBinding
import org.thoughtcrime.securesms.util.SystemWindowInsetsSetter
import org.thoughtcrime.securesms.util.TellomiLinks

abstract class CaptchaFragment : LoggingFragment(R.layout.fragment_registration_captcha) {

  private val binding: FragmentRegistrationCaptchaBinding by ViewBinderDelegate(FragmentRegistrationCaptchaBinding::bind)

  @SuppressLint("SetJavaScriptEnabled")
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    SystemWindowInsetsSetter.attach(view, viewLifecycleOwner)
    binding.registrationCaptchaWebView.settings.javaScriptEnabled = true
    binding.registrationCaptchaWebView.clearCache(true)

    binding.registrationCaptchaWebView.webViewClient = object : WebViewClient() {
      @Deprecated("Deprecated in Java")
      override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        // Tellomi：新旧两种 captcha 回跳都认（tellomicaptcha:// 与 signalcaptcha://），见 TellomiLinks。
        if (TellomiLinks.isCaptchaUrl(url)) {
          val token = TellomiLinks.stripCaptchaScheme(url)
          handleCaptchaToken(token)
          findNavController().navigateUp()
          return true
        }
        return false
      }
    }
    binding.registrationCaptchaWebView.loadUrl(BuildConfig.SIGNAL_CAPTCHA_URL)
  }

  abstract fun handleCaptchaToken(token: String)
}
