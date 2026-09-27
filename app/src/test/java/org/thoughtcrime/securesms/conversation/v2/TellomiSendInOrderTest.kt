/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import assertk.assertThat
import assertk.assertions.containsExactly
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.CompletableSubject
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Tellomi（tellomi/tellomi#1261 P-5「单独发送」、#1121「文件」页）：[TellomiSendInOrder] 把几条消息排成一串依次发。
 * 每条用一个 [CompletableSubject] 代替真的 `sendMessage`：被订阅 = 开始发（`sentTimeMillis` 在这时取），complete = 写进了本地库。
 */
class TellomiSendInOrderTest {

  private val scheduler = TestScheduler()
  private val sends = List(3) { CompletableSubject.create() }
  private val subscribed = mutableListOf<Int>()
  private val parts: List<Completable> = sends.mapIndexed { index, send -> send.doOnSubscribe { subscribed += index } }

  /** 同一会话 (date_sent, from_recipient_id, thread_id) 唯一：前一条写完立刻订阅下一条，两条可能同一毫秒，后一条插不进库。 */
  @Test
  fun `the next part starts only after the previous one completes and the gap has passed`() {
    val observer = TellomiSendInOrder.inOrder(parts, scheduler).test()
    assertThat(subscribed).containsExactly(0)

    sends[0].onComplete()
    assertThat(subscribed).containsExactly(0)

    scheduler.advanceTimeBy(TellomiSendInOrder.GAP_MILLIS - 1, TimeUnit.MILLISECONDS)
    assertThat(subscribed).containsExactly(0)

    scheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
    assertThat(subscribed).containsExactly(0, 1)
    observer.assertNotComplete()
  }

  @Test
  fun `parts go out in the given order and the whole completes right after the last one`() {
    val observer = TellomiSendInOrder.inOrder(parts, scheduler).test()

    sends[0].onComplete()
    scheduler.advanceTimeBy(TellomiSendInOrder.GAP_MILLIS, TimeUnit.MILLISECONDS)
    sends[1].onComplete()
    scheduler.advanceTimeBy(TellomiSendInOrder.GAP_MILLIS, TimeUnit.MILLISECONDS)
    observer.assertNotComplete()

    // 最后一条后面不等
    sends[2].onComplete()
    observer.assertComplete()
    assertThat(subscribed).containsExactly(0, 1, 2)
  }

  @Test
  fun `an error stops the chain and the parts after it are never sent`() {
    val observer = TellomiSendInOrder.inOrder(parts, scheduler).test()
    val error = IllegalStateException("Failed to insert message")

    sends[0].onComplete()
    scheduler.advanceTimeBy(TellomiSendInOrder.GAP_MILLIS, TimeUnit.MILLISECONDS)
    sends[1].onError(error)
    scheduler.advanceTimeBy(1, TimeUnit.SECONDS)

    observer.assertError(error)
    assertThat(subscribed).containsExactly(0, 1)
  }
}
