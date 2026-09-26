/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import org.signal.core.util.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.v2.TellomiSavedCategories.Category
import org.thoughtcrime.securesms.mediaoverview.MediaOverviewActivity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.visible
import org.signal.core.ui.R as CoreUiR

/**
 * Tellomi：「我的收藏」顶栏下方那一排分类（tellomi/tellomi#1174）。样子照聊天列表顶部的文件夹（`chat_folder_item` 的尺寸与配色），
 * 「全部」是选中的样子（就是会话本身），点其它几项打开「所有媒体」对应的页。只在自己的会话里、有内容时显示。
 */
class TellomiSavedCategoriesBar @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

  private val row = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    setPaddingRelative(16.dp, 0, 0, 0)
  }

  /** 现在显示的几项（空 = 整排不显示）。 */
  var categories: List<Category> = emptyList()
    private set

  private var threadId: Long = -1

  init {
    isHorizontalScrollBarEnabled = false
    addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    visible = false
  }

  /** 会话是自己的（「我的收藏」）时跟着内容显示分类，别的会话一直不显示。 */
  fun bind(recipient: Observable<Recipient>, threadId: Long): Disposable {
    return recipient
      .map { it.isSelf && threadId > 0 }
      .distinctUntilChanged()
      .switchMap { isSavedMessages -> if (isSavedMessages) TellomiSavedCategories.observe(threadId) else Observable.just(emptyList()) }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { setCategories(it, threadId) }
  }

  fun setCategories(categories: List<Category>, threadId: Long) {
    this.categories = categories
    this.threadId = threadId
    row.removeAllViews()
    categories.forEach { category -> row.addView(chipFor(category)) }
    visible = categories.isNotEmpty()
  }

  private fun chipFor(category: Category): View {
    val chip = LayoutInflater.from(context).inflate(R.layout.tellomi_saved_category_item, row, false) as TextView
    chip.setText(category.label)

    val selected = category == Category.ALL
    chip.isSelected = selected
    chip.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, if (selected) CoreUiR.color.signal_colorSurfaceVariant else R.color.transparent))
    chip.setTextColor(ContextCompat.getColor(context, if (selected) CoreUiR.color.signal_colorOnSurface else CoreUiR.color.signal_colorOnSurfaceVariant))

    val mediaType = category.mediaType
    if (mediaType != null) {
      chip.setOnClickListener { context.startActivity(MediaOverviewActivity.forThread(context, threadId, mediaType)) }
    }
    return chip
  }
}
