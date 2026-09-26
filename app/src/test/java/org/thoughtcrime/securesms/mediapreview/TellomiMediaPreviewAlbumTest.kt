/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import android.app.Application
import android.net.Uri
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.database.AttachmentId
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule

/**
 * Tellomi（#1257）：查看器底部缩略条和「这张 / 全部 N 张」只看当前这一页所在的那条消息。
 * 从会话设置的媒体条打开时，查看器里是整段会话的媒体，不能把它们都当成「本组」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiMediaPreviewAlbumTest {

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  @Test
  fun `opened from the conversation settings media rail, the album is only the current message`() {
    val conversation = Conversation()
    val state = conversation.state(position = 0, allMediaInAlbumRail = true)

    assertThat(state.currentAlbum.map { it.uri }).isEqualTo(listOf(conversation.single.uri))
    assertThat(state.copy(position = 2).currentAlbum.map { it.uri }).isEqualTo(listOf(conversation.albumFirst.uri, conversation.albumSecond.uri))
  }

  @Test
  fun `opened from a message, the album is that message`() {
    val conversation = Conversation()
    val state = conversation.state(position = 1, allMediaInAlbumRail = false)

    assertThat(state.currentAlbum.map { it.uri }).isEqualTo(listOf(conversation.albumFirst.uri, conversation.albumSecond.uri))
    assertThat(state.copy(position = 0).currentAlbum.map { it.uri }).isEqualTo(listOf(conversation.single.uri))
  }

  @Test
  fun `a page past the end has no album`() {
    val state = Conversation().state(position = 5, allMediaInAlbumRail = true)

    assertThat(state.currentAlbum).isEmpty()
  }

  /** 一段会话里的媒体：一条单张的消息，一条两张的相册。 */
  private class Conversation {
    val single = record(messageId = 1, part = 11)
    val albumFirst = record(messageId = 2, part = 21)
    val albumSecond = record(messageId = 2, part = 22)

    fun state(position: Int, allMediaInAlbumRail: Boolean): MediaPreviewState {
      return MediaPreviewState(
        mediaRecords = listOf(single, albumFirst, albumSecond),
        position = position,
        allMediaInAlbumRail = allMediaInAlbumRail,
        albums = mapOf(
          1L to listOfNotNull(single.toMedia()),
          2L to listOfNotNull(albumFirst.toMedia(), albumSecond.toMedia())
        )
      )
    }

    private fun record(messageId: Long, part: Long): MediaTable.MediaRecord {
      return MediaTable.MediaRecord(
        attachment = FakeMessageRecords.buildDatabaseAttachment(attachmentId = AttachmentId(part), mmsId = messageId),
        recipientId = RecipientId.from(1),
        threadRecipientId = RecipientId.from(1),
        threadId = 1,
        messageId = messageId,
        date = messageId,
        isOutgoing = false
      )
    }
  }

  private val MediaTable.MediaRecord.uri: Uri get() = attachment!!.uri!!
}
