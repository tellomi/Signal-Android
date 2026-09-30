/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * card-visual §3.2 (audit B4): the image of a large-image card fills the card's width, and its box is between 1.91:1 (wide) and
 * 1:1 (square); a taller or wider picture is cropped at the centre, which is what the thumbnail already does inside its box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLargeImageBoundsTest {

  @Test
  fun `the highest the box gets is square, the lowest is 1_91 to 1`() {
    assertEquals(720, TellomiLinkVisual.largeImageMaxHeight(720))
    assertEquals("720 / 1.91 = 376.96, rounded up so the box is not wider than 1.91:1", 377, TellomiLinkVisual.largeImageMinHeight(720))

    assertEquals(240, TellomiLinkVisual.largeImageMaxHeight(240))
    assertEquals(126, TellomiLinkVisual.largeImageMinHeight(240))
    assertEquals(100, TellomiLinkVisual.largeImageMinHeight(191))
  }

  @Test
  fun `at any width the lowest box is no wider than 1_91 to 1, and one pixel lower would be`() {
    for (width in 1..4000) {
      val min = TellomiLinkVisual.largeImageMinHeight(width)
      assertTrue("$width: $min", width * 100 <= min * 191)
      assertTrue("$width: $min is not as low as it can be", min == 1 || width * 100 > (min - 1) * 191)
      assertTrue("$width: the box can be square", min <= TellomiLinkVisual.largeImageMaxHeight(width))
    }
  }
}
