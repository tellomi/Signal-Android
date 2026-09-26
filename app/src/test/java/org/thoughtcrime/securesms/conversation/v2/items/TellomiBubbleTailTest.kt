/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2.items

import android.app.Application
import android.graphics.Path
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.util.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationItemDisplayMode
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.MediaUtil

/**
 * Tellomi：气泡的小尾巴（tellomi/tellomi#1206；规范 #1204 第 1、2 节）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiBubbleTailTest {

  private val standard = ConversationItemDisplayMode.Standard

  @Test
  fun `only the last message of a group, or a message on its own, gets a tail`() {
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = false, hasBubble = true, displayMode = standard)).isTrue()
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = false, hasReactions = false, hasBubble = true, displayMode = standard)).isFalse()
  }

  @Test
  fun `no tail under a reaction, without a bubble, or outside the conversation itself`() {
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = true, hasBubble = true, displayMode = standard)).isFalse()
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = false, hasBubble = false, displayMode = standard)).isFalse()
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = false, hasBubble = true, displayMode = ConversationItemDisplayMode.EditHistory)).isFalse()
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = false, hasBubble = true, displayMode = ConversationItemDisplayMode.Detailed)).isFalse()
  }

  @Test
  fun `my tail sticks out 6 to the right of the bottom corner, 14 high`() {
    val bounds = outlineBounds(towardsRight = true)

    assertThat(bounds.top).isCloseTo(200f - 14f * DENSITY, 0.01f)
    assertThat(bounds.bottom).isCloseTo(200f, 0.01f)
    assertThat(bounds.left).isCloseTo(100f - 4f * DENSITY, 0.01f)
    // computeBounds 按控制点算（新版 Android 忽略 exact），尖端最远的控制点在 6.3；曲线本身到 6.08。
    assertThat(bounds.right - 100f).isCloseTo(6.3f * DENSITY, 0.01f)
  }

  @Test
  fun `their tail is the mirror image, to the left`() {
    val bounds = outlineBounds(towardsRight = false)

    assertThat(bounds.right).isCloseTo(100f + 4f * DENSITY, 0.01f)
    assertThat(100f - bounds.left).isCloseTo(6.3f * DENSITY, 0.01f)
    assertThat(bounds.top).isCloseTo(200f - 14f * DENSITY, 0.01f)
  }

  @Test
  fun `the corner under the tail becomes the small one, on the sender's side`() {
    val big = 18f.dp
    val small = 4f.dp

    val mine = V2ConversationItemShape(mockk(relaxed = true))
    mine.applyTellomiTail(isOutgoing = true)
    assertThat(mine.cornersLTR.bottomRight).isEqualTo(small)
    assertThat(mine.cornersLTR.bottomLeft).isEqualTo(big)
    assertThat(mine.cornersLTR.topRight).isEqualTo(big)
    assertThat(mine.cornersRTL.bottomLeft).isEqualTo(small)

    val theirs = V2ConversationItemShape(mockk(relaxed = true))
    theirs.applyTellomiTail(isOutgoing = false)
    assertThat(theirs.cornersLTR.bottomLeft).isEqualTo(small)
    assertThat(theirs.cornersLTR.bottomRight).isEqualTo(big)
  }

  @Test
  fun `groups are 12 apart in both renderers`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val density = context.resources.displayMetrics.density
    val legacy = context.resources.getDimensionPixelOffset(R.dimen.conversation_vertical_message_spacing_default)

    assertThat(legacy * 2f).isCloseTo(12f * density, 0.5f)
    assertThat(V2ConversationItemShape.MessageShape.SINGLE.topPadding + V2ConversationItemShape.MessageShape.SINGLE.bottomPadding).isCloseTo(12f.dp, 0.5f)
  }

  /** 「正在输入」的卡片四角都是 18：尾巴轮廓往气泡里多盖一块 18 的方块，扣掉气泡后正好补满圆角的缺口。 */
  @Test
  fun `a tail on a card with big corners also covers that corner`() {
    val bounds = outlineBounds(towardsRight = false, coverRadiusDp = 18f)

    // 尾巴照旧伸出 6.3、只有 14 高的部分在气泡外；往气泡里盖到 18 × 18
    assertThat(100f - bounds.left).isCloseTo(6.3f * DENSITY, 0.01f)
    assertThat(bounds.right).isCloseTo(100f + 18f * DENSITY, 0.01f)
    assertThat(bounds.top).isCloseTo(200f - 18f * DENSITY, 0.01f)
    assertThat(bounds.bottom).isCloseTo(200f, 0.01f)
  }

  @Test
  fun `the typing indicator reports its tail to the same decoration`() {
    assertThat(TellomiBubbleTail.Provider::class.java.isAssignableFrom(org.thoughtcrime.securesms.conversation.v2.ConversationTypingIndicatorAdapter.ViewHolder::class.java)).isTrue()
  }

  @Test
  fun `voice notes, files and view-once messages without text still have a bubble, captionless photos do not`() {
    val context = ApplicationProvider.getApplicationContext<Application>()

    // 都带一个附件：纯文字消息会走大号表情的判断，要装表情库（单测里没装，会一直等）。已删除 = 记着谁删的（deletedBy）
    fun mms(contentType: String, body: String = "", hasThumbnail: Boolean = false, voiceNote: Boolean = false, viewOnce: Boolean = false, deleted: Boolean = false): MmsMessageRecord {
      val attachment = FakeMessageRecords.buildDatabaseAttachment(contentType = contentType, hasThumbnail = hasThumbnail, voiceNote = voiceNote)
      return FakeMessageRecords.buildMediaMmsMessageRecord(
        body = body,
        viewOnce = viewOnce,
        deletedBy = if (deleted) RecipientId.from(1) else null,
        slideDeck = SlideDeck(attachment)
      )
    }

    // 旧版渲染里这几种都有底色：组尾要带尾巴
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms(MediaUtil.AUDIO_AAC, voiceNote = true), context), "voice note").isTrue()
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms("application/pdf"), context), "file").isTrue()
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms(MediaUtil.IMAGE_JPEG, hasThumbnail = true, viewOnce = true), context), "view-once photo").isTrue()
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms(MediaUtil.IMAGE_JPEG, body = "看这个", hasThumbnail = true), context), "photo with a caption").isTrue()

    // 图铺满了气泡、没有露出来的底色：不画
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms(MediaUtil.IMAGE_JPEG, hasThumbnail = true), context), "captionless photo").isFalse()
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms("application/pdf", deleted = true), context), "deleted").isFalse()
  }

  private fun outlineBounds(towardsRight: Boolean, coverRadiusDp: Float = TellomiBubbleTail.DEFAULT_COVER_RADIUS_DP): RectF {
    val path = Path()
    TellomiBubbleTail.addOutline(path, cornerX = 100f, bottomY = 200f, towardsRight = towardsRight, density = DENSITY, coverRadiusDp = coverRadiusDp)
    val bounds = RectF()
    @Suppress("DEPRECATION")
    path.computeBounds(bounds, true)
    return bounds
  }

  companion object {
    private const val DENSITY = 2f
  }
}
