/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.signal.network.exceptions.PushNetworkException
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.profiles.AvatarHelper
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import java.io.ByteArrayInputStream

/**
 * Tellomi（tellomi/tellomi#1367）：对方先 PUT 资料、再上传头像文件。接收方如果正好在两步之间拉到资料，
 * 下载新路径会得到 404。上游在 404 时删掉本地头像、却照样把新路径记进库；而 [RetrieveProfileJob] 只在
 * 「远端路径 ≠ 库里的路径」时才派下载任务，于是这张头像再也不会被重下，直到对方下次换头像。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class RetrieveProfileAvatarJobTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private lateinit var alice: RecipientId

  @Before
  fun setUp() {
    alice = recipients.createRecipient("Alice")
    SignalDatabase.recipients.setProfileAvatar(alice, OLD_PATH)
    // 写头像文件要用附件加密密钥，测试环境没有；这里只关心库里记的路径。
    mockkStatic(AvatarHelper::class)
    every { AvatarHelper.setAvatar(any(), any(), any()) } just runs
  }

  @After
  fun tearDown() {
    unmockkStatic(AvatarHelper::class)
  }

  @Test
  fun `given the new avatar is not on the CDN yet, when I run, then I keep the old path so the next profile fetch retries`() {
    every { AppDependencies.signalServiceMessageReceiver.retrieveProfileAvatar(NEW_PATH, any(), any(), any()) } throws
      PushNetworkException(NonSuccessfulResponseCodeException(404))

    run(NEW_PATH)

    assertEquals(OLD_PATH, SignalDatabase.recipients.getRecord(alice).signalProfileAvatar)
  }

  @Test
  fun `given the new avatar downloads, when I run, then I record the new path`() {
    every { AppDependencies.signalServiceMessageReceiver.retrieveProfileAvatar(NEW_PATH, any(), any(), any()) } answers
      { ByteArrayInputStream(ByteArray(64) { it.toByte() }) }

    run(NEW_PATH)

    assertEquals(NEW_PATH, SignalDatabase.recipients.getRecord(alice).signalProfileAvatar)
  }

  private fun run(path: String) {
    RetrieveProfileAvatarJob(Recipient.resolved(alice), path).apply {
      setContext(ApplicationProvider.getApplicationContext())
    }.onRun()
  }

  companion object {
    private const val OLD_PATH = "profiles/old-avatar"
    private const val NEW_PATH = "profiles/new-avatar"
  }
}
