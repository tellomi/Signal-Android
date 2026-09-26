/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.loaders.MediaLoader
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * Tellomi：「我的收藏」顶栏下方的一排分类（tellomi/tellomi#1174，需求 official-account-and-saved §3.2 第 1 条）。
 *
 * 全部 · 图片与视频 · 文件 · 语音 · 链接，只显示有内容的（Telegram 同）。「全部」就是会话本身；
 * 其它几项打开现有的「所有媒体」并停在对应的页，数据和「所有媒体」用同一套查询。一类都没有时整排不显示。
 */
object TellomiSavedCategories {

  enum class Category(val mediaType: MediaLoader.MediaType?, @StringRes val label: Int) {
    ALL(null, R.string.TellomiSavedCategories__all),
    MEDIA(MediaLoader.MediaType.GALLERY, R.string.TellomiSavedCategories__photos_and_videos),
    FILES(MediaLoader.MediaType.DOCUMENT, R.string.TellomiSavedCategories__files),
    VOICE(MediaLoader.MediaType.AUDIO, R.string.TellomiSavedCategories__voice),
    LINKS(MediaLoader.MediaType.LINK, R.string.TellomiSavedCategories__links)
  }

  /** 要显示的几项：有内容的按固定顺序排，前面加「全部」；一类都没有就什么都不显示。 */
  fun toShow(withContent: Set<Category>): List<Category> {
    val categories = Category.entries.filter { it != Category.ALL && it in withContent }
    return if (categories.isEmpty()) emptyList() else listOf(Category.ALL) + categories
  }

  /** 这个会话里哪几类有内容。 */
  @WorkerThread
  fun withContent(threadId: Long): Set<Category> {
    return Category.entries.filter { category -> category.mediaType?.let { hasAny(threadId, it) } ?: false }.toSet()
  }

  private fun hasAny(threadId: Long, mediaType: MediaLoader.MediaType): Boolean {
    val media = SignalDatabase.media
    val cursor = when (mediaType) {
      MediaLoader.MediaType.GALLERY -> media.getGalleryMediaForThread(threadId, MediaTable.Sorting.Newest, 1)
      MediaLoader.MediaType.DOCUMENT -> media.getDocumentMediaForThread(threadId, MediaTable.Sorting.Newest, 1)
      MediaLoader.MediaType.AUDIO -> media.getAudioMediaForThread(threadId, MediaTable.Sorting.Newest, 1)
      MediaLoader.MediaType.LINK -> media.getLinkMediaForThread(threadId, MediaTable.Sorting.Newest)
      MediaLoader.MediaType.ALL -> media.getAllMediaForThread(threadId, MediaTable.Sorting.Newest, 1)
    }
    return cursor.use { it.moveToFirst() }
  }

  /** 这个会话要显示的几项；消息或附件有变化就重算。 */
  fun observe(threadId: Long): Observable<List<Category>> {
    return Observable.create<Unit> { emitter ->
      val observer = DatabaseObserver.Observer { emitter.onNext(Unit) }
      AppDependencies.databaseObserver.registerConversationObserver(threadId, observer)
      AppDependencies.databaseObserver.registerAttachmentUpdatedObserver(observer)
      emitter.setCancellable { AppDependencies.databaseObserver.unregisterObserver(observer) }
      emitter.onNext(Unit)
    }
      .observeOn(Schedulers.io())
      .map { toShow(withContent(threadId)) }
      .distinctUntilChanged()
  }
}
