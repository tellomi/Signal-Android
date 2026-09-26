/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Activity
import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.contentValuesOf
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import io.reactivex.rxjava3.core.Observable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.v2.TellomiSavedCategories.Category
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.mediaoverview.MediaOverviewActivity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.testutil.RxPluginsRule

/**
 * Tellomi（tellomi/tellomi#1174，需求 official-account-and-saved §3.2 第 1 条）：「我的收藏」顶栏下方的分类，只显示有内容的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiSavedCategoriesTest {

  @get:Rule
  val recipients = RecipientTestRule()

  @get:Rule
  val rx = RxPluginsRule()

  private fun savedMessagesThread(): Long = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipients.self))

  /** 往「我的收藏」里存一条消息，带一个已下载的附件，或者带链接预览。 */
  private fun save(contentType: String? = null, linkPreview: Boolean = false) {
    val messageId = recipients.insertOutgoingMessage(recipients.self)
    if (contentType != null) {
      SignalDatabase.writableDatabase.insert(
        AttachmentTable.TABLE_NAME,
        null,
        contentValuesOf(
          AttachmentTable.MESSAGE_ID to messageId,
          AttachmentTable.CONTENT_TYPE to contentType,
          AttachmentTable.TRANSFER_STATE to AttachmentTable.TRANSFER_PROGRESS_DONE,
          AttachmentTable.DATA_FILE to "/tellomi-test/$messageId"
        )
      )
    }
    if (linkPreview) {
      SignalDatabase.writableDatabase.update(
        MessageTable.TABLE_NAME,
        contentValuesOf(MessageTable.LINK_PREVIEWS to """[{"url":"https://tellomi.app","title":"Tellomi","description":"","date":0}]"""),
        "${MessageTable.ID} = ?",
        arrayOf(messageId.toString())
      )
    }
  }

  @Test
  fun `text only shows nothing, and only the kinds that have content are listed, in a fixed order`() {
    val thread = savedMessagesThread()
    save()
    assertThat(TellomiSavedCategories.toShow(TellomiSavedCategories.withContent(thread))).isEmpty()

    save(linkPreview = true)
    save(contentType = "image/jpeg")
    assertThat(TellomiSavedCategories.toShow(TellomiSavedCategories.withContent(thread))).containsExactly(Category.ALL, Category.MEDIA, Category.LINKS)
  }

  @Test
  fun `files and voice are told apart from photos and videos`() {
    val thread = savedMessagesThread()
    save(contentType = "application/pdf")
    assertThat(TellomiSavedCategories.withContent(thread)).isEqualTo(setOf(Category.FILES))

    save(contentType = "audio/aac")
    assertThat(TellomiSavedCategories.withContent(thread)).isEqualTo(setOf(Category.FILES, Category.VOICE))

    save(contentType = "video/mp4")
    assertThat(TellomiSavedCategories.toShow(TellomiSavedCategories.withContent(thread)))
      .containsExactly(Category.ALL, Category.MEDIA, Category.FILES, Category.VOICE)
  }

  @Test
  fun `the bar shows the categories only in saved messages`() {
    val thread = savedMessagesThread()
    save(contentType = "image/jpeg")
    val buddy = recipients.createRecipient("Buddy")

    val other = bar()
    other.bind(Observable.just(Recipient.resolved(buddy)), thread)
    rx.defaultScheduler.triggerActions()
    assertThat(other.categories).isEmpty()
    assertThat(other.visibility).isEqualTo(View.GONE)

    val saved = bar()
    saved.bind(Observable.just(Recipient.resolved(recipients.self)), thread)
    rx.defaultScheduler.triggerActions()
    assertThat(saved.categories).containsExactly(Category.ALL, Category.MEDIA)
    assertThat(saved.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `tapping a category opens all media on that page, all is the chat itself`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val bar = TellomiSavedCategoriesBar(ContextThemeWrapper(activity, R.style.Signal_DayNight))
    bar.setCategories(listOf(Category.ALL, Category.MEDIA, Category.LINKS), threadId = 7)

    val row = bar.getChildAt(0) as LinearLayout
    val names = (0 until row.childCount).map { row.getChildAt(it).findViewById<TextView>(R.id.name).text.toString() }
    assertThat(names).containsExactly("全部", "图片与视频", "链接")
    assertThat(row.getChildAt(0).isSelected).isTrue()
    assertThat(row.getChildAt(2).isSelected).isFalse()

    row.getChildAt(0).performClick()
    assertThat(shadowOf(activity).nextStartedActivity == null).isTrue()

    row.getChildAt(2).performClick()
    val intent = shadowOf(activity).nextStartedActivity
    assertThat(intent).isNotNull()
    assertThat(intent.component?.className).isEqualTo(MediaOverviewActivity::class.java.name)
    assertThat(intent.getLongExtra("thread_id", -1)).isEqualTo(7L)
    assertThat(intent.getStringExtra("tellomi_initial_media_type")).isEqualTo("LINK")
  }

  private fun bar(): TellomiSavedCategoriesBar {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    return TellomiSavedCategoriesBar(ContextThemeWrapper(activity, R.style.Signal_DayNight))
  }
}
