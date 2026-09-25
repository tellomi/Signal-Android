/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.avatar.fallback

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Tellomi（tellomi/tellomi#1174，需求 official-account-and-saved §3.2）：「备忘录」改名「我的收藏」、书签头像、固定颜色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiSavedMessagesAvatarTest {

  @Test
  fun `saved messages uses the bookmark icon at every size`() {
    val avatar = FallbackAvatar.Resource.NoteToSelf(FallbackAvatar.Resource.NoteToSelf.TELLOMI_SAVED_MESSAGES_COLOR)
    assertThat(avatar.getIconBySize(FallbackAvatar.Size.SMALL)).isEqualTo(R.drawable.tellomi_symbol_bookmark_compact_16)
    assertThat(avatar.getIconBySize(FallbackAvatar.Size.MEDIUM)).isEqualTo(R.drawable.tellomi_symbol_bookmark_24)
    assertThat(avatar.getIconBySize(FallbackAvatar.Size.LARGE)).isEqualTo(R.drawable.tellomi_symbol_bookmark_display_bold_40)
  }

  @Test
  fun `the self recipient falls back to saved messages in the fixed color, not its own avatar color`() {
    val self = Recipient(id = RecipientId.from(1), isResolving = false, isSelf = true, avatarColor = AvatarColor.A170)
    assertThat(self.getFallbackAvatar()).isEqualTo(FallbackAvatar.Resource.NoteToSelf(AvatarColor.A110))
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `it is called 我的收藏 in simplified chinese`() {
    assertThat(ApplicationProvider.getApplicationContext<Application>().getString(R.string.note_to_self)).isEqualTo("我的收藏")
  }

  @Test
  @Config(qualifiers = "zh-rTW")
  fun `it is called 我的收藏 in traditional chinese too`() {
    assertThat(ApplicationProvider.getApplicationContext<Application>().getString(R.string.note_to_self)).isEqualTo("我的收藏")
  }

  @Test
  fun `it is called Saved Messages in english`() {
    assertThat(ApplicationProvider.getApplicationContext<Application>().getString(R.string.note_to_self)).isEqualTo("Saved Messages")
  }
}
