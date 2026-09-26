/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import android.net.Uri
import androidx.compose.runtime.Immutable
import org.signal.mediasend.MediaSendFlowActivityContract

/**
 * Tellomi（tellomi/tellomi#1121，需求 `docs/product/specs/attachment-files-location.md` §二）：附件 Sheet「文件」页。
 *
 * @param files 最近发送的文件；null = 还在读（骨架）。
 * @param selection 多选里勾的顺序（F-8，附件行号）；null = 不在多选。
 * @param searchQuery 搜索框里的字（F-10）；null = 不在搜索。
 * @param searchedQuery 防抖（0.6 秒）之后真正搜过的字，[searchResults] 是它的结果。
 */
@Immutable
internal data class AttachmentFilesState(
  val files: List<RecentSentFile>? = null,
  val maxFileSize: Long = 0L,
  val dock: List<MediaSendFlowActivityContract.DockEntry> = emptyList(),
  val selection: List<Long>? = null,
  val caption: String = "",
  val searchQuery: String? = null,
  val searchedQuery: String = "",
  val searchResults: List<RecentSentFile> = emptyList(),
  val showsAllResults: Boolean = false
) {
  val isSelecting: Boolean get() = selection != null
  val isSearching: Boolean get() = searchQuery != null

  /** 🔍 只在最近文件 > 10 条或加载中时出现，多选、搜索中藏起（F-8、F-10，同 Telegram iOS）。 */
  val showsSearchButton: Boolean
    get() = !isSelecting && !isSearching && (files == null || files.size > SEARCH_BUTTON_THRESHOLD)

  /** 超过 4 条先显示 3 条 +「显示更多」（F-10）。 */
  val visibleSearchResults: List<RecentSentFile>
    get() = if (showsShowMore) searchResults.take(SEARCH_COLLAPSED_COUNT) else searchResults

  val showsShowMore: Boolean
    get() = !showsAllResults && searchResults.size > SEARCH_COLLAPSE_THRESHOLD

  /** 搜过了、有字、没结果：写「没有找到」（不照抄 Telegram 骨架一直闪的缺陷）。 */
  val showsNoResults: Boolean
    get() = searchedQuery.isNotBlank() && searchResults.isEmpty()

  fun selectionNumberOf(file: RecentSentFile): Int? {
    val selection = selection ?: return null
    return selection.indexOf(file.attachmentId) + 1
  }

  companion object {
    const val RECENT_LIMIT = 100
    const val SEARCH_BUTTON_THRESHOLD = 10
    const val SEARCH_COLLAPSE_THRESHOLD = 4
    const val SEARCH_COLLAPSED_COUNT = 3
    const val SEARCH_DEBOUNCE_MS = 600L
  }
}

internal sealed interface AttachmentFilesEvent {
  /** 顶栏 ✕：多选中先退出多选，否则关掉 Sheet。 */
  data object Close : AttachmentFilesEvent

  /** 返回键：搜索中退出搜索、多选中退出多选（都不在时由 Sheet 关闭，不走这里）。 */
  data object Back : AttachmentFilesEvent

  /** F-3 要等协议（#1263）：先只提示「即将支持」。 */
  data object SelectFromGalleryClicked : AttachmentFilesEvent

  /** F-4：系统文件选择器挑回来的文件（可多选）；空 = 取消了。 */
  data class FilesPicked(val uris: List<Uri>) : AttachmentFilesEvent

  data class FileClicked(val file: RecentSentFile) : AttachmentFilesEvent
  data class FileLongClicked(val file: RecentSentFile) : AttachmentFilesEvent
  data class CaptionChanged(val caption: String) : AttachmentFilesEvent
  data object SendSelection : AttachmentFilesEvent

  data object SearchOpened : AttachmentFilesEvent
  data class SearchQueryChanged(val query: String) : AttachmentFilesEvent
  data object SearchClosed : AttachmentFilesEvent
  data object ShowMoreResults : AttachmentFilesEvent

  data class DockEntryClicked(val entry: MediaSendFlowActivityContract.DockEntry) : AttachmentFilesEvent
}
