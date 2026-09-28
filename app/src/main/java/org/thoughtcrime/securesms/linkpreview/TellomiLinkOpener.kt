/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R

/**
 * Opens a link from a message the way rust/links' `open_plan` says (ADR-0063 §4.9 / §5.5 / §6.1).
 * The card and the text both reach `ConversationFragment.openLink`, so both come through here.
 *
 * The target is always the URL itself; the plan only says how to hand it over, in order:
 * 1. `in_app` — tell.cc: Tellomi's own router (an `ACTION_VIEW` pinned to this package).
 * 2. `installed_app_only` — only an installed, non-browser app may take it: API 30+
 *    `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`; before that, only when exactly one non-browser app handles it.
 * 3. `scheme` — the registry's app scheme, filled from what this device matched on the URL.
 * 4. `browser` — an explicit browser ([TellomiExplicitBrowser]), which copies the link if even that fails.
 * 5. `copy_link` — copy it and say so (§5.5); reached when nothing before it took the link.
 * Payment and ride-hailing plans only have the browser step. An empty plan (`intent:`, `javascript:`,
 * `data:`, `file:`) opens nothing. A domain that imitates a well-known one gets one warning first.
 * No registry: open the way Signal does.
 */
object TellomiLinkOpener {

  private val TAG = Log.tag(TellomiLinkOpener::class.java)

  private val JSON = Json { ignoreUnknownKeys = true }

  @Serializable
  data class Step(val type: String, val url: String)

  @Serializable
  data class Plan(
    val steps: List<Step>,
    val label: String,
    @SerialName("app_name") val appName: TellomiLinkCard.LocalizedName? = null,
    val lookalike: String? = null
  )

  /** How each step is attempted; true when something took the link. */
  interface Launcher {
    fun inApp(url: String): Boolean
    fun installedAppOnly(url: String): Boolean
    fun scheme(url: String): Boolean
    fun browser(url: String): Boolean
    fun copyLink(url: String): Boolean
  }

  @JvmStatic
  fun parse(json: String): Plan? {
    return try {
      JSON.decodeFromString(Plan.serializer(), json)
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  @JvmStatic
  fun plan(url: String): Plan? {
    val registry = TellomiLinkRegistry.get() ?: return null
    return try {
      parse(registry.openPlan(url))
    } catch (e: Exception) {
      Log.w(TAG, "openPlan failed: ${e.javaClass.simpleName}")
      null
    }
  }

  /** Tries the steps in order; returns the type of the step that took the link, or null. */
  @VisibleForTesting
  @JvmStatic
  fun run(steps: List<Step>, launcher: Launcher): String? {
    for (step in steps) {
      val took = when (step.type) {
        "in_app" -> launcher.inApp(step.url)
        "installed_app_only" -> launcher.installedAppOnly(step.url)
        "scheme" -> launcher.scheme(step.url)
        "browser" -> launcher.browser(step.url)
        "copy_link" -> launcher.copyLink(step.url)
        else -> false
      }
      if (took) {
        return step.type
      }
    }
    return null
  }

  @JvmStatic
  fun open(activity: Activity, url: String, fallback: Runnable) {
    val plan = plan(url)
    if (plan == null) {
      fallback.run()
      return
    }
    if (plan.steps.isEmpty()) {
      Log.w(TAG, "Not opening a link that is not http(s).")
      return
    }

    val go = Runnable {
      val took = run(plan.steps, ActivityLauncher(activity))
      // Only the step type, never the URL (§6.5).
      Log.i(TAG, "Opened via ${took ?: "nothing"}")
    }

    val lookalike = plan.lookalike
    if (lookalike != null) {
      MaterialAlertDialogBuilder(activity)
        .setMessage(activity.getString(R.string.TellomiLinkOpen__lookalike_message, lookalike))
        .setPositiveButton(R.string.TellomiLinkOpen__open_anyway) { _, _ -> go.run() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    } else {
      go.run()
    }
  }

  @VisibleForTesting
  class ActivityLauncher(private val activity: Activity) : Launcher {

    override fun inApp(url: String): Boolean {
      return start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(activity.packageName))
    }

    override fun installedAppOnly(url: String): Boolean {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
      if (Build.VERSION.SDK_INT >= 30) {
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER))
      }
      val app = soleNonBrowserApp(intent) ?: return false
      return start(intent.setPackage(app))
    }

    override fun scheme(url: String): Boolean {
      return start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    override fun browser(url: String): Boolean {
      return TellomiExplicitBrowser.open(activity, url) != TellomiExplicitBrowser.Outcome.REJECTED
    }

    override fun copyLink(url: String): Boolean {
      val clipboard = ContextCompat.getSystemService(activity, ClipboardManager::class.java) ?: return false
      clipboard.setPrimaryClip(ClipData.newPlainText(null, url))
      Toast.makeText(activity, R.string.TellomiLinks__link_copied, Toast.LENGTH_SHORT).show()
      return true
    }

    /** Before API 30: the one app (not a browser, not us) that handles this URL, if there is exactly one. */
    private fun soleNonBrowserApp(intent: Intent): String? {
      val packageManager = activity.packageManager
      val handlers = packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
      val browsers = packageManager
        .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE), 0)
        .map { it.activityInfo.packageName }
        .toSet()
      val apps = handlers - browsers - activity.packageName
      return apps.singleOrNull()
    }

    private fun start(intent: Intent): Boolean {
      return try {
        activity.startActivity(intent)
        true
      } catch (e: ActivityNotFoundException) {
        false
      } catch (e: SecurityException) {
        false
      }
    }
  }
}
