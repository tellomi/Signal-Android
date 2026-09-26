/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R

/**
 * Tellomi（两端差异清单第 11 项）：未读分隔线只写「新消息」，不带条数，和 iOS 一致（Telegram 两端也不带）。
 * 分隔线的 ViewHolder 是 [ConversationItemDecorations] 的私有内部类，用反射取出来，读它真实显示的文字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiUnreadDividerTest {

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `the unread divider says 新消息 without a count`() {
    assertThat(dividerText(unreadCount = 5)).isEqualTo("新消息")
  }

  @Test
  fun `the unread divider says New messages without a count in English`() {
    assertThat(dividerText(unreadCount = 12)).isEqualTo("New messages")
  }

  private fun dividerText(unreadCount: Int): String {
    val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Signal_DayNight)
    val decorations = ConversationItemDecorations()
    decorations.setUnreadState(unreadCount = unreadCount, firstUnreadId = 42L)

    val getUnreadViewHolder = ConversationItemDecorations::class.java
      .getDeclaredMethod("getUnreadViewHolder", RecyclerView::class.java)
      .apply { isAccessible = true }
    val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
    val holder = getUnreadViewHolder.invoke(decorations, parent)!!
    val itemView = holder.javaClass.getDeclaredMethod("getItemView").apply { isAccessible = true }.invoke(holder) as View
    return itemView.findViewById<TextView>(R.id.text).text.toString()
  }
}
