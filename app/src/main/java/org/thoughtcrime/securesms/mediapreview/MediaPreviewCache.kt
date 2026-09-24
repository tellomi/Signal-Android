package org.thoughtcrime.securesms.mediapreview

import android.graphics.drawable.Drawable
import android.net.Uri

/**
 * Stores the bitmap for a thumbnail we are animating from via a shared
 * element transition. This prevents us from having to load anything on the
 * receiving end.
 */
object MediaPreviewCache {
  var drawable: Drawable? = null

  /**
   * Tellomi（#1257 C-9）：查看器关闭时停在同一个相册里的另一张（不是点开的那张）时，记下它的 uri；
   * 会话页映射回程的共享元素时读它，先把横滑相册滚到这一张，缩回动画就落在它上面。读完即清。
   */
  var returnMediaUri: Uri? = null
}
