/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.database.model.MessageId

class ReactionsViewModelTest {

  private val testScheduler = TestScheduler()
  private val repository = mockk<ReactionsRepository>()

  @Before
  fun setUp() {
    // Tellomi：不装 RxJava 的 init handler，免得这里的 TestScheduler 永久留成同一测试进程里的默认调度器（见 RxPluginsRule 的说明）
    RxJavaPlugins.setIoSchedulerHandler { testScheduler }
  }

  @After
  fun tearDown() {
    RxJavaPlugins.reset()
  }

  @Test
  fun `Given a message Id, when I removeReactionEmoji, then I expect Success`() {
    // GIVEN
    val messageId = MessageId(0)
    val testSubject = ReactionsViewModel(
      repository,
      messageId
    )
    every { repository.sendReactionRemoval(any()) } just runs

    // WHEN
    testSubject.removeReactionEmoji()

    // THEN
    verify(exactly = 1) { repository.sendReactionRemoval(any()) }
  }
}
