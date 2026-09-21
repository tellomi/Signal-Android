package org.thoughtcrime.securesms.util

import com.google.android.gms.wallet.WalletConstants
import org.signal.donations.GooglePayApi
import org.signal.donations.StripeApi
import org.thoughtcrime.securesms.BuildConfig

object Environment {
  /**
   * Tellomi：阶段一**不做捐赠**（owner 2026-09-22 定）。
   *
   * 为 false 时隐藏所有用户能点到的捐赠入口。这不是「部署差异」而是产品阶段决定，
   * 所以写成常量而不是按 flavor 的 BuildConfig——要做的时候翻成 true 即可，
   * 上游的捐赠代码原样留着，没有删。
   *
   * 背景：我们既没有支付通道，服务端下发的也还是上游的测试配置（客户端解析不了）；
   * 这些页面上还写着「Proudly Nonprofit」这类对 Tellomi 不成立的资质说法。
   */
  const val DONATIONS_ENABLED = false

  private const val GOOGLE_PLAY_BILLING_APPLICATION_ID = "org.thoughtcrime.securesms"

  const val IS_STAGING: Boolean = BuildConfig.BUILD_ENVIRONMENT_TYPE == "Staging" || BuildConfig.BUILD_ENVIRONMENT_TYPE == "Pnp" || BuildConfig.BUILD_ENVIRONMENT_TYPE == "Backup"
  const val IS_NIGHTLY: Boolean = BuildConfig.BUILD_DISTRIBUTION_TYPE == "nightly"
  const val IS_WEBSITE: Boolean = BuildConfig.BUILD_DISTRIBUTION_TYPE == "website"
  const val IS_BENCHMARK: Boolean = BuildConfig.BUILD_VARIANT_TYPE == "Benchmark"
  const val IS_PERF: Boolean = BuildConfig.BUILD_VARIANT_TYPE == "Perf"

  @JvmField
  var IS_INSTRUMENTATION: Boolean = IS_BENCHMARK

  @JvmStatic
  fun isInternal(): Boolean {
    return !IS_INSTRUMENTATION && (BuildConfig.DEBUG || IS_NIGHTLY || IS_PERF || IS_STAGING)
  }

  @JvmField
  val USE_NEW_REGISTRATION: Boolean = true

  @JvmField
  val IS_LINK_AND_SYNC_AVAILABLE: Boolean = true

  const val PHONENUMBERLESS_REGISTRATION: Boolean = IS_STAGING

  object Backups {
    @JvmStatic
    fun supportsGooglePlayBilling(): Boolean {
      return BuildConfig.APPLICATION_ID == GOOGLE_PLAY_BILLING_APPLICATION_ID
    }

    @JvmStatic
    fun isNewFormatSupportedForLocalBackup(): Boolean = true
  }

  object Donations {
    @JvmStatic
    @get:JvmName("getGooglePayConfiguration")
    val GOOGLE_PAY_CONFIGURATION = GooglePayApi.Configuration(
      walletEnvironment = if (IS_STAGING) WalletConstants.ENVIRONMENT_TEST else WalletConstants.ENVIRONMENT_PRODUCTION
    )

    @JvmStatic
    @get:JvmName("getStripeConfiguration")
    val STRIPE_CONFIGURATION = StripeApi.Configuration(
      baseUrl = BuildConfig.STRIPE_BASE_URL,
      publishableKey = BuildConfig.STRIPE_PUBLISHABLE_KEY
    )
  }

  object Calling {
    @JvmStatic
    fun defaultSfuUrl(): String {
      return if (IS_STAGING) BuildConfig.SIGNAL_STAGING_SFU_URL else BuildConfig.SIGNAL_SFU_URL
    }
  }
}
