package org.thoughtcrime.securesms.util

import com.google.android.gms.wallet.WalletConstants
import org.signal.donations.GooglePayApi
import org.signal.donations.StripeApi
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.region.TellomiRegions

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

  /**
   * Tellomi（tellomi/tellomi#1210）：**写死关闭**。上游是 `IS_STAGING`——staging 包的手机号页因此出现「Register without number」，
   * 与《网络安全法》即时通信实名要求冲突。上游 8.28.1 起对所有人开放了这个入口，升级到 8.28 时要再核一次别被合回去。
   */
  const val PHONENUMBERLESS_REGISTRATION: Boolean = false

  object Backups {
    /**
     * Tellomi：**不做付费备份档**（没有计费通道，也没有备份订阅服务）。
     *
     * 这些界面与推广气泡上写着「X 是一个非营利组织——付费备份有助于支持我们的使命」
     * 这类对 Tellomi 不成立的说法（#984）。上游把它们挡在 `RemoteConfig.backupsMegaphone()`
     * 后面，默认 false——但那是**服务端下发的**，服务端一旦下发就会冒出来；
     * 我们要的是本地保证，所以加这个常量。要做的时候翻成 true，上游代码原样留着没删。
     */
    const val PAID_BACKUPS_ENABLED = false

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
      // Tellomi（#1055）：从当前区取。我们的 staging 与 prod 用同一个会合点（SIGNAL_STAGING_SFU_URL 与 SIGNAL_SFU_URL 同值）
      return TellomiRegions.current().sfu
    }
  }
}
