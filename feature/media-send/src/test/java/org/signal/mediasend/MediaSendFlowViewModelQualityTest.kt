/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.app.Application
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tellomi（tellomi/tellomi#1261，需求 D9）：「以高清质量发送」与预览页的画质只管这一次发送，不写回「设置 → 发送媒体质量」；
 * 这次选的画质跟着流程状态走，进程被回收后也照样恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaSendFlowViewModelQualityTest {

  @get:Rule
  val dependencies = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  /**
   * [MediaSendDependencies.init] only takes the first provider it is given, so after the first test the rule's own
   * repository is not the one the state's defaults read from. The setting is therefore stubbed on the installed one,
   * and only that stub is touched (clearing it would also drop the brush widths the state parcels).
   */
  private fun givenSetting(quality: SentMediaQuality) {
    val installed = MediaSendDependencies.mediaSendRepository
    every { installed.sentMediaQuality } returns quality
  }

  /** Each test hands the view model a repository of its own, so what it writes cannot leak into another test. */
  private fun newRepository(): MediaSendRepository = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext()).mediaSendRepository

  private fun viewModel(repository: MediaSendRepository): MediaSendFlowViewModel {
    val savedStateHandle = SavedStateHandle(
      mapOf(
        MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(),
        MediaSendFlowViewModel.KEY_IDENTITY_CHANGES_SINCE to 0L
      )
    )
    return MediaSendFlowViewModel(
      savedStateHandle = savedStateHandle,
      repository = repository,
      preUploadController = mockk(relaxed = true),
      isMeteredFlow = flowOf(false)
    )
  }

  @Test
  fun `Given the setting is standard, when this send is switched to high, then only this send is high and the setting is untouched`() {
    givenSetting(SentMediaQuality.STANDARD)
    val repository = newRepository()
    val viewModel = viewModel(repository)
    assertThat(viewModel.state.value.sentMediaQuality).isEqualTo(SentMediaQuality.STANDARD)

    viewModel.setSentMediaQuality(SentMediaQuality.HIGH)

    assertThat(viewModel.state.value.sentMediaQuality).isEqualTo(SentMediaQuality.HIGH)
    verify(exactly = 0) { repository.sentMediaQuality = any() }
  }

  @Test
  fun `Given the setting is high, when this send is switched to standard, then the setting stays high`() {
    givenSetting(SentMediaQuality.HIGH)
    val repository = newRepository()
    val viewModel = viewModel(repository)
    assertThat(viewModel.state.value.sentMediaQuality).isEqualTo(SentMediaQuality.HIGH)

    viewModel.setSentMediaQuality(SentMediaQuality.STANDARD)

    assertThat(viewModel.state.value.sentMediaQuality).isEqualTo(SentMediaQuality.STANDARD)
    verify(exactly = 0) { repository.sentMediaQuality = any() }
  }

  @Test
  fun `Given high was chosen for this send, when the state is restored after process death, then it is still high`() {
    givenSetting(SentMediaQuality.STANDARD)
    val parcel = Parcel.obtain()
    try {
      parcel.writeParcelable(MediaSendFlowState(sentMediaQuality = SentMediaQuality.HIGH), 0)
      parcel.setDataPosition(0)

      @Suppress("DEPRECATION")
      val restored = parcel.readParcelable<MediaSendFlowState>(MediaSendFlowState::class.java.classLoader)!!

      assertThat(restored.sentMediaQuality).isEqualTo(SentMediaQuality.HIGH)
    } finally {
      parcel.recycle()
    }
  }
}
