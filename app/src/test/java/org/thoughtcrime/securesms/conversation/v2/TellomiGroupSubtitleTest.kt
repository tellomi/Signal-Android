/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Tellomi（两端差异清单第 10 项）：群聊顶栏副标题写「N 个成员」，人数按群的全部成员算。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiGroupSubtitleTest {

  private val resources = ApplicationProvider.getApplicationContext<Application>().resources

  private fun group(memberCount: Int): Recipient {
    return mockk(relaxed = true) {
      every { participantIds } returns List(memberCount) { RecipientId.from(it + 1L) }
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `a group of 25 reads 25 个成员, counting everyone rather than the first 10`() {
    assertThat(tellomiGroupMemberSubtitle(resources, group(25))).isEqualTo("25 个成员")
  }

  @Test
  fun `in English it is the member count, singular and plural`() {
    assertThat(tellomiGroupMemberSubtitle(resources, group(1))).isEqualTo("1 Member")
    assertThat(tellomiGroupMemberSubtitle(resources, group(3))).isEqualTo("3 Members")
  }
}
