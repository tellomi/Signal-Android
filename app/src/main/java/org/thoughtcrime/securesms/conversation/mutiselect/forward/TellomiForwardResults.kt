/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.ShareCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.sharing.MultiShareArgs
import org.thoughtcrime.securesms.sharing.v2.ShareActivity

/**
 * 发出以后的提示（tellomi/tellomi#1259 F-8）：「已转发给 **小林**」/「已转发给 **小林** 和 **小王**」/「已转发给 **小林** 等 3 个聊天」/
 * 「已转发到 **我的收藏**」（只有这一种能直接打开「我的收藏」）。约 3 秒、不能撤销。
 */
object TellomiForwardedToast {

  const val DURATION_MS = 3000

  data class Result(
    val text: CharSequence,
    val boldNames: List<String>,
    val opensSavedMessages: Boolean
  )

  data class Recipient(val name: String, val isSavedMessages: Boolean)

  fun build(context: Context, targets: List<TellomiForwardTarget>): Result? {
    return buildForRecipients(context, targets.map { Recipient(it.fullName(context), it.isSavedMessages) })
  }

  fun buildForRecipients(context: Context, recipients: List<Recipient>): Result? {
    val first = recipients.firstOrNull() ?: return null
    val text: String
    val bold: List<String>
    var opensSavedMessages = false
    when {
      recipients.size == 1 && first.isSavedMessages -> {
        text = context.getString(R.string.TellomiForwardGrid__forwarded_to_saved_s, first.name)
        bold = listOf(first.name)
        opensSavedMessages = true
      }

      recipients.size == 1 -> {
        text = context.getString(R.string.TellomiForwardGrid__forwarded_to_s, first.name)
        bold = listOf(first.name)
      }

      recipients.size == 2 -> {
        val second = recipients[1]
        text = context.getString(R.string.TellomiForwardGrid__forwarded_to_s_and_s, first.name, second.name)
        bold = listOf(first.name, second.name)
      }

      else -> {
        text = context.getString(R.string.TellomiForwardGrid__forwarded_to_s_and_d_chats, first.name, recipients.size)
        bold = listOf(first.name)
      }
    }

    val spannable = SpannableString(text)
    var searchFrom = 0
    for (name in bold) {
      val start = text.indexOf(name, searchFrom)
      if (start >= 0 && name.isNotEmpty()) {
        spannable.setSpan(StyleSpan(Typeface.BOLD), start, start + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        searchFrom = start + name.length
      }
    }
    return Result(spannable, bold, opensSavedMessages)
  }
}

/**
 * 「分享到其他 App」（tellomi/tellomi#1259 F-10）：一期直接打开系统选择器。图片 / 视频 / 文件 / 语音按本机原文件（一组就是全部），
 * 文字消息按文字。统一分享面板（#1149）做好后改成打开它的第二排。
 */
object TellomiForwardShare {

  fun canShare(multiShareArgs: List<MultiShareArgs>): Boolean {
    return streams(multiShareArgs).isNotEmpty() || texts(multiShareArgs).isNotEmpty()
  }

  fun createChooser(context: Context, multiShareArgs: List<MultiShareArgs>): Intent? {
    val streams = streams(multiShareArgs)
    val texts = texts(multiShareArgs)
    val builder = ShareCompat.IntentBuilder(context)
    when {
      streams.isNotEmpty() -> {
        streams.forEach { (uri, _) -> builder.addStream(PartAuthority.getAttachmentPublicUri(uri)) }
        builder.setType(mimeTypeFor(streams.map { it.second }))
      }

      texts.isNotEmpty() -> {
        builder.setType("text/plain")
        builder.setText(texts.joinToString(separator = "\n\n"))
      }

      else -> return null
    }

    val chooser = builder.createChooserIntent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (Build.VERSION.SDK_INT < 34) {
      chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, ShareActivity::class.java)))
    }
    return chooser
  }

  /** 本机附件：相册里的每一张、单个媒体 / 文件 / 语音 */
  fun streams(multiShareArgs: List<MultiShareArgs>): List<Pair<Uri, String?>> {
    return multiShareArgs.flatMap { args ->
      when {
        args.media.isNotEmpty() -> args.media.map { it.uri to it.contentType }
        args.dataUri != null && args.stickerLocator == null -> listOf(args.dataUri to args.dataType)
        else -> emptyList()
      }
    }
  }

  /** 没有附件的消息：按文字 */
  fun texts(multiShareArgs: List<MultiShareArgs>): List<String> {
    return multiShareArgs
      .filter { it.media.isEmpty() && it.dataUri == null }
      .mapNotNull { it.draftText?.takeIf { text -> text.isNotBlank() } }
  }

  fun mimeTypeFor(contentTypes: List<String?>): String {
    val normalized = contentTypes.map { Intent.normalizeMimeType(it) ?: "*/*" }
    if (normalized.size == 1) {
      return normalized.first()
    }
    val families = normalized.map { it.substringBefore('/') }.toSet()
    return if (families.size == 1 && families.first() != "*") "${families.first()}/*" else "*/*"
  }
}
