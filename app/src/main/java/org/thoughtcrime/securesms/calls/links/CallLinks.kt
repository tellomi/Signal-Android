/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.links

import androidx.annotation.VisibleForTesting
import io.reactivex.rxjava3.core.Observable
import org.signal.core.util.logging.Log
import org.signal.ringrtc.CallException
import org.signal.ringrtc.CallLinkRootKey
import org.thoughtcrime.securesms.database.CallLinkTable
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkRoomId
import java.io.UnsupportedEncodingException
import java.net.URLDecoder

/**
 * Utility object for call links to try to keep some common logic in one place.
 */
object CallLinks {
  private const val ROOT_KEY = "key"
  private const val LEGACY_HTTPS_LINK_PREFIX = "https://signal.link/call#key="
  private const val LEGACY_SGNL_LINK_PREFIX = "sgnl://signal.link/call#key="
  private const val HTTPS_LINK_PREFIX = "https://signal.link/call/#key="
  private const val SNGL_LINK_PREFIX = "sgnl://signal.link/call/#key="

  // Tellomi：新形状 https|tellomi://tell.cc/call#key=…（见 docs/signal/LINKS_AND_SCHEMES.md）。
  // tellomi/tellomi#1113：`url()` 改发这个形状（不带结尾斜杠，与那张表一致）；解析另外认带斜杠的
  // `tell.cc/call/#key=…`——Desktop 发的是这个（`signalRoutes.std.ts`），原来 Android 在 App 内认不出。
  private const val TELLOMI_HTTPS_LINK_PREFIX = "https://tell.cc/call#key="
  private const val TELLOMI_SCHEME_LINK_PREFIX = "tellomi://tell.cc/call#key="
  private const val TELLOMI_HTTPS_SLASH_LINK_PREFIX = "https://tell.cc/call/#key="
  private const val TELLOMI_SCHEME_SLASH_LINK_PREFIX = "tellomi://tell.cc/call/#key="

  private val TAG = Log.tag(CallLinks::class.java)

  fun url(rootKeyBytes: ByteArray): String = urlForFormattedKey(CallLinkRootKey(rootKeyBytes).toString())

  /** 单拆出来只为能单测：CallLinkRootKey 的格式化走 RingRTC 原生库，JVM 单测里加载不了。 */
  @VisibleForTesting
  fun urlForFormattedKey(formattedKey: String): String = "$TELLOMI_HTTPS_LINK_PREFIX$formattedKey"

  fun watchCallLink(roomId: CallLinkRoomId): Observable<CallLinkTable.CallLink> {
    return Observable.create { emitter ->

      fun refresh() {
        val callLink = SignalDatabase.callLinks.getCallLinkByRoomId(roomId)
        if (callLink != null) {
          emitter.onNext(callLink)
        }
      }

      val observer = DatabaseObserver.Observer {
        refresh()
      }

      AppDependencies.databaseObserver.registerCallLinkObserver(roomId, observer)
      emitter.setCancellable {
        AppDependencies.databaseObserver.unregisterObserver(observer)
      }

      refresh()
    }
  }

  private fun isPrefixedCallLink(url: String): Boolean {
    return url.startsWith(HTTPS_LINK_PREFIX) ||
      url.startsWith(SNGL_LINK_PREFIX) ||
      url.startsWith(LEGACY_HTTPS_LINK_PREFIX) ||
      url.startsWith(LEGACY_SGNL_LINK_PREFIX) ||
      url.startsWith(TELLOMI_HTTPS_LINK_PREFIX) ||
      url.startsWith(TELLOMI_SCHEME_LINK_PREFIX) ||
      url.startsWith(TELLOMI_HTTPS_SLASH_LINK_PREFIX) ||
      url.startsWith(TELLOMI_SCHEME_SLASH_LINK_PREFIX)
  }

  @JvmStatic
  fun isCallLink(url: String): Boolean {
    if (!isPrefixedCallLink(url)) {
      return false
    }

    return url.split("#").last().startsWith("key=")
  }

  @JvmStatic
  fun parseUrl(url: String): CallLinkRootKey? {
    if (!isPrefixedCallLink(url)) {
      Log.w(TAG, "Invalid url prefix.")
      return null
    }

    val parts = url.split("#")
    if (parts.size != 2) {
      Log.w(TAG, "Invalid fragment delimiter count in url.")
      return null
    }

    val fragmentQuery = mutableMapOf<String, String?>()

    try {
      for (part in parts[1].split("&")) {
        val kv = part.split("=")
        // Make sure we don't have an empty key (i.e. handle the case
        // of "a=0&&b=0", for example)
        if (kv[0].isEmpty()) {
          Log.w(TAG, "Invalid url: $url (empty key)")
          return null
        }
        val key = URLDecoder.decode(kv[0], "utf8")
        val value = when (kv.size) {
          1 -> null
          2 -> URLDecoder.decode(kv[1], "utf8")
          else -> {
            // Cannot have more than one value per key (i.e. handle the case
            // of "a=0&b=0=1=2", for example.
            Log.w(TAG, "Invalid url: $url (multiple values)")
            return null
          }
        }
        fragmentQuery += key to value
      }
    } catch (_: UnsupportedEncodingException) {
      Log.w(TAG, "Invalid url: $url")
      return null
    } catch (_: IllegalArgumentException) {
      Log.w(TAG, "Invalid url: $url")
      return null
    }

    val key = fragmentQuery[ROOT_KEY]
    if (key == null) {
      Log.w(TAG, "Root key not found in fragment query string.")
      return null
    }

    return try {
      return CallLinkRootKey(key)
    } catch (e: CallException) {
      Log.w(TAG, "Invalid root key found in fragment query string.")
      null
    }
  }
}
