/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * Tellomi（tellomi/tellomi#1261 P-5「单独发送」、#1121「文件」页）：一次发好几条消息时排成一串——前一条完成（写进本地库）
 * 才订阅下一条，所以对方看到的顺序就是给的顺序；任何一条出错就停，后面的不再发，错误交给订阅方。
 *
 * 两条之间隔 [GAP_MILLIS]：`OutgoingMessage.sentTimeMillis` 在订阅那一刻取当前时间，而同一会话里
 * `(date_sent, from_recipient_id, thread_id)` 是唯一索引；前一条写完立刻订阅下一条，两条可能落在同一毫秒，后一条插不进库、整串断掉。
 * 上游连发几条（`MediaSelectionRepository`）也是每条之间 `ThreadUtil.sleep(5)`。最后一条后面不用等。
 *
 * 调用方不要把这一串的订阅挂在 view 的生命周期上：离开会话、弹窗会话发完第一条就 finish，剩下的也要发完。
 */
object TellomiSendInOrder {

  const val GAP_MILLIS = 5L

  // 先红：旧行为——前一条完成立刻订阅下一条，中间不隔。
  @Suppress("UNUSED_PARAMETER")
  fun inOrder(parts: List<Completable>, scheduler: Scheduler = Schedulers.computation()): Completable {
    return Completable.concat(parts)
  }
}
