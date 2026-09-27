/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import java.util.Locale

/**
 * 用**显式指定的浏览器**打开链接：ADR-0063 §4.9 第 3 步的底层接入（tellomi/tellomi#1422；§8.1 第 7 行）。
 * 以后 `open_plan` 走到第 3 步、以及支付金融链接（只走这一步）都调它；点击入口现在还没改（`ConversationFragment.openLink` 照旧）。
 *
 * 顺序：
 * 1. 只收 `https` / `http`。`intent:`、`javascript:`、`data:`、`file:`、`content:` 这类一律拒绝，不产生任何跳转（§4.9 / §6.1）。
 * 2. 默认浏览器支持 Custom Tabs → Custom Tabs，**`setPackage` 钉在这个浏览器上**。不钉包名的 Custom Tabs 就是隐式 `ACTION_VIEW`，
 *    已验证的 App Links 照样会被 App 接走（Telegram `Browser.java:393` 只在服务绑上时才带包名，我们的支付那条不能靠它）。
 * 3. 否则 `ACTION_VIEW` + `CATEGORY_BROWSABLE`，selector 是 `ACTION_MAIN` + `CATEGORY_APP_BROWSER`：系统只在浏览器里挑，不会交给 App。
 *    selector 用的是 `Intent.makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_BROWSER)` **里面**那个 selector：
 *    那个方法返回的是一个 MAIN + LAUNCHER 的 Intent，selector 挂在它身上；直接拿它当 selector 会变成「所有能启动的 App」。
 * 4. 浏览器也打不开（极少见，系统没有浏览器）→ 复制链接，提示「已复制链接」（§5.5）。
 *
 * 日志不记 URL（§6.5）。
 */
object TellomiExplicitBrowser {

  private val TAG = Log.tag(TellomiExplicitBrowser::class.java)

  private const val CUSTOM_TABS_SERVICE_ACTION = "android.support.customtabs.action.CustomTabsService"
  private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"

  enum class Outcome {
    /** 在默认浏览器的 Custom Tab 里打开（包名已钉住）。 */
    CUSTOM_TAB,

    /** 交给 `CATEGORY_APP_BROWSER` selector 选出的浏览器。 */
    BROWSER,

    /** 没有浏览器能打开，已复制链接（§5.5）。 */
    COPIED,

    /** 不是网页链接，什么都没做。 */
    REJECTED
  }

  /** 只有带主机名的 `https` / `http` 链接才能打开；scheme 统一成小写，其余原样保留。 */
  @JvmStatic
  fun targetUri(url: String): Uri? {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "https" && scheme != "http") {
      return null
    }
    if (uri.host.isNullOrEmpty()) {
      return null
    }
    return uri.buildUpon().scheme(scheme).build()
  }

  /** 默认浏览器的包名，前提是它提供 Custom Tabs 服务；没有默认浏览器（系统弹选择器）或不支持时返回 null。 */
  @JvmStatic
  fun customTabsBrowser(packageManager: PackageManager): String? {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
    val browser = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: return null
    if (browser == "android") {
      return null
    }
    val service = Intent(CUSTOM_TABS_SERVICE_ACTION).setPackage(browser)
    return if (packageManager.queryIntentServices(service, 0).isNotEmpty()) browser else null
  }

  /** [customTabsPackage] 不为空 → 钉在它上面的 Custom Tab；为空 → 浏览器 selector。 */
  @JvmStatic
  fun buildIntent(uri: Uri, customTabsPackage: String?): Intent {
    return if (customTabsPackage != null) {
      Intent(Intent.ACTION_VIEW, uri)
        .setPackage(customTabsPackage)
        .putExtras(Bundle().apply { putBinder(EXTRA_CUSTOM_TABS_SESSION, null) })
    } else {
      Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .apply { selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER) }
    }
  }

  @JvmStatic
  fun open(context: Context, url: String): Outcome {
    val uri = targetUri(url)
    if (uri == null) {
      Log.w(TAG, "Refusing to open a link that is not http(s).")
      return Outcome.REJECTED
    }

    val customTabs = customTabsBrowser(context.packageManager)
    if (customTabs != null && start(context, buildIntent(uri, customTabs))) {
      return Outcome.CUSTOM_TAB
    }
    if (start(context, buildIntent(uri, null))) {
      return Outcome.BROWSER
    }

    Log.w(TAG, "No browser could open the link; copying it instead.")
    ContextCompat.getSystemService(context, ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, url))
    Toast.makeText(context, R.string.TellomiLinks__link_copied, Toast.LENGTH_SHORT).show()
    return Outcome.COPIED
  }

  private fun start(context: Context, intent: Intent): Boolean {
    if (context !is Activity) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
      context.startActivity(intent)
      true
    } catch (e: ActivityNotFoundException) {
      false
    }
  }
}
