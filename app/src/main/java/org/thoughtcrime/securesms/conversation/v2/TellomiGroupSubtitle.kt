/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.content.res.Resources
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Tellomi（两端差异清单第 10 项）：群聊顶栏副标题写「N 个成员」，和 iOS 一致；Telegram 两端同样只写人数。
 * 上游写的是逗号分隔的成员名，而且只取了前 10 个（`ConversationViewModel.titleViewParticipants`）。
 * 人数按群的全部成员（[Recipient.participantIds]）算；措辞用现成的 `ConversationFragment_group_member_count`，和 iOS 设置页的成员数同一句。
 */
internal fun tellomiGroupMemberSubtitle(resources: Resources, group: Recipient): String {
  val count = group.participantIds.size
  return resources.getQuantityString(R.plurals.ConversationFragment_group_member_count, count, count)
}
