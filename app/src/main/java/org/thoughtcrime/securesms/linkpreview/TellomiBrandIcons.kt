/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.io.InputStream

/**
 * ADR-0063 §5.1 / §九.6, card-visual §3.9: the icon of a brand shell comes from the package (`assets/links/icons/`), never
 * from the sender and never from the network. The name comes from the registry's `icon` field (through
 * [TellomiLinkCard.icon]); a name that is not a plain `<lowercase, digits, ->.png` file name is refused before anything
 * is opened, so a registry cannot point this at another path. A missing or undecodable file is "no icon": the shell
 * shows its name and domain, as it does for a brand without an official icon.
 */
class TellomiBrandIcons(private val openAsset: (String) -> InputStream) {

  private class Entry(val bitmap: Bitmap?)

  private val icons = LruCache<String, Entry>(CACHE_SIZE)

  /**
   * The decoded icon for [fileName], or null for "no icon". The bitmap is shared by every card that uses it: read it,
   * never recycle or change it. Decoded once per name, a missing file included.
   */
  @WorkerThread
  fun resolve(fileName: String?): Bitmap? {
    val name = fileName?.takeIf { isValidFileName(it) } ?: return null
    icons.get(name)?.let { return it.bitmap }

    val bitmap = try {
      openAsset("$ASSET_DIR/$name").use { BitmapFactory.decodeStream(it) }?.takeIf { it.width > 0 && it.height > 0 }
    } catch (e: Exception) {
      Log.w(TAG, "Could not read the bundled icon $name", e)
      null
    }
    icons.put(name, Entry(bitmap))
    return bitmap
  }

  companion object {
    private val TAG = Log.tag(TellomiBrandIcons::class.java)

    const val ASSET_DIR = "links/icons"

    private const val CACHE_SIZE = 32

    /** `^[a-z0-9-]+\.png$` (the shape `rust/links` also checks when it loads the registry); the whole name must match. */
    private val FILE_NAME = Regex("[a-z0-9-]+\\.png")

    /** The icons in this app's package. */
    @JvmStatic
    val bundled: TellomiBrandIcons by lazy { TellomiBrandIcons { path -> AppDependencies.application.assets.open(path) } }

    @JvmStatic
    fun isValidFileName(fileName: String?): Boolean = fileName != null && FILE_NAME.matches(fileName)
  }
}
