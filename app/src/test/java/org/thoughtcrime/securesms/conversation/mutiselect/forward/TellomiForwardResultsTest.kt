/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.app.Application
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sharing.MultiShareArgs
import org.thoughtcrime.securesms.sharing.MultiShareSender
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tellomi（tellomi/tellomi#1259）：发出以后的提示（F-8）、「分享到其他 App」（F-10）、附言先发（F-7，判据「转发面板」4）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiForwardResultsTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  // PartAuthority 的静态初始化要 authority 和 AppDependencies.blobs；不设的话单独跑这个类时分享那条会 ExceptionInInitializerError
  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  @After
  fun tearDown() {
    unmockkStatic(MultiShareSender::class)
  }

  private fun boldRanges(text: CharSequence): List<String> {
    val spanned = text as Spanned
    return spanned.getSpans(0, text.length, StyleSpan::class.java)
      .filter { it.style == Typeface.BOLD }
      .sortedBy { spanned.getSpanStart(it) }
      .map { text.substring(spanned.getSpanStart(it), spanned.getSpanEnd(it)) }
  }

  /** F-8：一个 / 两个 / 三个以上 / 只转到「我的收藏」四种写法，名字加粗；只有转到我的收藏的能点开。 */
  @Test
  fun `the toast names the chats`() {
    val lin = TellomiForwardedToast.Recipient("小林", isSavedMessages = false)
    val wang = TellomiForwardedToast.Recipient("小王", isSavedMessages = false)
    val saved = TellomiForwardedToast.Recipient(context.getString(R.string.note_to_self), isSavedMessages = true)

    assertThat(TellomiForwardedToast.buildForRecipients(context, emptyList())).isNull()

    val one = TellomiForwardedToast.buildForRecipients(context, listOf(lin))!!
    assertThat(one.text.toString()).isEqualTo("Forwarded to 小林")
    assertThat(boldRanges(one.text)).containsExactly("小林")
    assertThat(one.opensSavedMessages).isFalse()

    val two = TellomiForwardedToast.buildForRecipients(context, listOf(lin, wang))!!
    assertThat(two.text.toString()).isEqualTo("Forwarded to 小林 and 小王")
    assertThat(boldRanges(two.text)).containsExactly("小林", "小王")

    val many = TellomiForwardedToast.buildForRecipients(context, listOf(lin, wang, saved))!!
    assertThat(many.text.toString()).isEqualTo("Forwarded to 3 chats, including 小林")
    assertThat(boldRanges(many.text)).containsExactly("小林")
    assertThat(many.opensSavedMessages).isFalse()

    val onlySaved = TellomiForwardedToast.buildForRecipients(context, listOf(saved))!!
    assertThat(onlySaved.text.toString()).isEqualTo("Forwarded to Saved Messages")
    assertThat(onlySaved.opensSavedMessages).isTrue()
    assertThat(TellomiForwardedToast.DURATION_MS).isEqualTo(3000)
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `the toast reads naturally in chinese`() {
    val lin = TellomiForwardedToast.Recipient("小林", isSavedMessages = false)
    val wang = TellomiForwardedToast.Recipient("小王", isSavedMessages = false)
    val zhao = TellomiForwardedToast.Recipient("老赵", isSavedMessages = false)
    val saved = TellomiForwardedToast.Recipient("我的收藏", isSavedMessages = true)

    assertThat(TellomiForwardedToast.buildForRecipients(context, listOf(lin))!!.text.toString()).isEqualTo("已转发给 小林")
    assertThat(TellomiForwardedToast.buildForRecipients(context, listOf(lin, wang))!!.text.toString()).isEqualTo("已转发给 小林 和 小王")
    assertThat(TellomiForwardedToast.buildForRecipients(context, listOf(lin, wang, zhao))!!.text.toString()).isEqualTo("已转发给 小林 等 3 个聊天")
    assertThat(TellomiForwardedToast.buildForRecipients(context, listOf(saved))!!.text.toString()).isEqualTo("已转发到 我的收藏")
    assertThat(context.getString(R.string.TellomiForwardGrid__select_up_to_d_chats, 5)).isEqualTo("最多选 5 个聊天")
    assertThat(context.getString(R.string.TellomiForwardGrid__name_separator)).isEqualTo("、")
  }

  @Test
  @Config(qualifiers = "yue")
  fun `cantonese has its own wording`() {
    assertThat(context.getString(R.string.TellomiForwardGrid__select_up_to_d_chats, 5)).isEqualTo("最多揀 5 個聊天")
    assertThat(context.getString(R.string.TellomiForwardGrid__forwarded_to_s, "小林")).isEqualTo("已經轉寄畀 小林")
  }

  /** F-10：图片一组 → 多个文件；单个文件 → 一个文件；文字消息 → 文字；贴纸这类没有可分享的 → 不给按钮。 */
  @Test
  fun `share hands files or text to the system chooser`() {
    val photo1 = media("content://org.thoughtcrime.securesms/part/1", "image/jpeg")
    val photo2 = media("content://org.thoughtcrime.securesms/part/2", "image/png")
    val album = MultiShareArgs.Builder(setOf()).withMedia(listOf(photo1, photo2)).build()
    val file = MultiShareArgs.Builder(setOf()).withDataUri(Uri.parse("content://org.thoughtcrime.securesms/part/3")).withDataType("application/pdf").build()
    val text = MultiShareArgs.Builder(setOf()).withDraftText("原文").build()

    val albumIntent = TellomiForwardShare.createChooser(context, listOf(album))!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
    assertThat(albumIntent.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
    assertThat(albumIntent.type).isEqualTo("image/*")
    assertThat(albumIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!.size).isEqualTo(2)

    val fileIntent = TellomiForwardShare.createChooser(context, listOf(file))!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
    assertThat(fileIntent.action).isEqualTo(Intent.ACTION_SEND)
    assertThat(fileIntent.type).isEqualTo("application/pdf")
    assertThat(fileIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isNotNull()

    val textIntent = TellomiForwardShare.createChooser(context, listOf(text))!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
    assertThat(textIntent.action).isEqualTo(Intent.ACTION_SEND)
    assertThat(textIntent.type).isEqualTo("text/plain")
    assertThat(textIntent.getCharSequenceExtra(Intent.EXTRA_TEXT).toString()).isEqualTo("原文")

    assertThat(TellomiForwardShare.canShare(listOf(album))).isTrue()
    assertThat(TellomiForwardShare.canShare(listOf(MultiShareArgs.Builder(setOf()).build()))).isFalse()
    assertThat(TellomiForwardShare.mimeTypeFor(listOf("image/jpeg", "video/mp4"))).isEqualTo("*/*")
  }

  /** F-7 / 判据 4：附言作为单独一条文字，先于转发内容发出（上游放在最后）。 */
  @Test
  fun `the added message is sent before the forwarded content`() {
    val sentTexts = mutableListOf<String?>()
    val success = mockk<MultiShareSender.MultiShareSendResultCollection> {
      every { containsFailures() } returns false
      every { containsOnlyFailures() } returns false
    }
    mockkStatic(MultiShareSender::class)
    every { MultiShareSender.sendSync(any()) } answers {
      synchronized(sentTexts) { sentTexts += firstArg<MultiShareArgs>().draftText }
      success
    }
    val done = CountDownLatch(1)
    val keys = setOf<ContactSearchKey>(ContactSearchKey.RecipientSearchKey(RecipientId.from(2), false))

    MultiselectForwardRepository.send(
      additionalMessage = "附言",
      multiShareArgs = listOf(MultiShareArgs.Builder(setOf()).withDraftText("原文").withTimestamp(1).build()),
      shareContacts = keys,
      resultHandlers = MultiselectForwardRepository.MultiselectForwardResultHandlers(
        onAllMessageSentSuccessfully = { done.countDown() },
        onSomeMessagesFailed = { done.countDown() },
        onAllMessagesFailed = { done.countDown() }
      )
    )

    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
    assertThat(sentTexts).containsExactly("附言", "原文")
  }

  private fun media(uri: String, contentType: String): Media {
    return Media(
      uri = Uri.parse(uri),
      contentType = contentType,
      date = 0,
      width = 100,
      height = 100,
      size = 10,
      duration = 0,
      isBorderless = false,
      isVideoGif = false,
      bucketId = null,
      caption = null,
      transformProperties = null,
      fileName = null
    )
  }
}
