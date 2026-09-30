/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.util.LruCache
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * The link registry that ships with the app (ADR-0063 §8.1 row 3: Android bundles it; hot updates
 * come after P1), and the receiver-side decision made with it (§5.1 rule 4: in the data layer,
 * before display, cached per message content and registry version — never in view binding).
 *
 * If the registry cannot be loaded, nothing is decided and previews show the way Signal shows them.
 */
object TellomiLinkRegistry {

  private val TAG = Log.tag(TellomiLinkRegistry::class.java)

  const val BUNDLED_ASSET = "links/links-2026092702.json"

  @Volatile
  private var registry: LinkRegistry? = null

  @Volatile
  private var loadFailed = false

  private val cards = LruCache<String, Optional>(500)

  private class Optional(val card: TellomiLinkCard?)

  @JvmStatic
  @Synchronized
  fun get(): LinkRegistry? {
    if (registry != null || loadFailed) {
      return registry
    }
    try {
      val bytes = AppDependencies.application.assets.open(BUNDLED_ASSET).use { it.readBytes() }
      registry = LinkRegistry.load(bytes)
      Log.i(TAG, "Loaded bundled registry ${registry?.version}")
    } catch (e: Exception) {
      loadFailed = true
      Log.w(TAG, "Failed to load the bundled registry", e)
    }
    return registry
  }

  @VisibleForTesting
  @JvmStatic
  @Synchronized
  fun setForTesting(value: LinkRegistry?) {
    registry = value
    loadFailed = value == null
    cards.evictAll()
  }

  /**
   * Level and contents of the card for one received preview, or null for "no decision" (show it
   * the way Signal does). Logs provider, route, level and reason only — never the URL (§6.5).
   */
  @JvmStatic
  @WorkerThread
  fun classify(linkPreview: LinkPreview, body: String, isStory: Boolean, attachmentContentTypes: List<String>): TellomiLinkCard? {
    val current = get() ?: return null
    val previewJson = TellomiLinkCard.previewInputJson(linkPreview)
    val messageJson = TellomiLinkCard.messageContextJson(isStory, attachmentContentTypes)
    val key = listOf(current.version.toString(), previewJson, body, messageJson).joinToString("\u0000")

    cards.get(key)?.let { return it.card }

    val card = try {
      TellomiLinkCard.parse(current.classify(previewJson, body, messageJson))
    } catch (e: Exception) {
      Log.w(TAG, "classify failed: ${e.javaClass.simpleName}")
      null
    }
    if (card?.reason != null) {
      Log.d(TAG, "card ${card.provider ?: "-"}/${card.route ?: "-"} ${card.level} (${card.reason})")
    }
    cards.put(key, Optional(card))
    return card
  }

  /**
   * rust/links' receive-time check for one received preview (ADR-0063 §7.4): whether to keep it, and whether to keep its
   * `rich`. Null for "no decision": no registry, or rust/links failed. Never logs the URL.
   */
  @JvmStatic
  @WorkerThread
  fun receiveCheck(linkPreview: LinkPreview, body: String, isStory: Boolean, attachmentContentTypes: List<String>): TellomiReceiveCheck? {
    val current = get() ?: return null
    return try {
      TellomiReceiveCheck.parse(
        current.receiveCheck(
          TellomiLinkCard.previewInputJson(linkPreview),
          body,
          TellomiLinkCard.messageContextJson(isStory, attachmentContentTypes)
        )
      )
    } catch (e: Exception) {
      Log.w(TAG, "receiveCheck failed: ${e.javaClass.simpleName}")
      null
    }
  }
}
