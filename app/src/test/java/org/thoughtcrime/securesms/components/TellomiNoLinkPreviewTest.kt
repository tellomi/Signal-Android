/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.linkpreview.LinkPreviewRepository

/**
 * ADR-0063 §5.1 铁律 2：取不到预览就不显示预览区（输入框和分享页都一样），不出「No link preview available」。
 * 唯一的例外是第一方群邀请确定已失效：照上游提示发送者「This group link is not active」。
 */
class TellomiNoLinkPreviewTest {

  @Test
  fun `no preview area when the preview is simply unavailable`() {
    assertFalse(LinkPreviewView.isShownWithoutPreview(LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE))
    assertFalse(LinkPreviewView.isShownWithoutPreview(null))
  }

  @Test
  fun `an inactive group link still tells the sender`() {
    assertTrue(LinkPreviewView.isShownWithoutPreview(LinkPreviewRepository.Error.GROUP_LINK_INACTIVE))
  }
}
