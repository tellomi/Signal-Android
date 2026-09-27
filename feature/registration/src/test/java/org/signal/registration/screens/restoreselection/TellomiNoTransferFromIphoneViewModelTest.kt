/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.restoreselection

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.signal.network.api.RegistrationApiV2.RestoreMethod
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationFlowState
import org.signal.registration.RegistrationRepository
import org.signal.registration.RegistrationRoute
import org.signal.registration.TellomiRegistration

/**
 * Tellomi（tellomi/tellomi#1216 跟进，owner 2026-09-24 定）：扫码扫到的旧手机是 iPhone、又没有备份服务时，
 * 恢复方式页换成「传不过来」的说明页，给「直接注册 / 返回」。界面的用例在 [TellomiNoTransferFromIphoneScreenTest]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TellomiNoTransferFromIphoneViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val repository: RegistrationRepository = mockk(relaxed = true)
  private val parentEvents = mutableListOf<RegistrationFlowEvent>()

  @Before
  fun setup() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    TellomiRegistration.remoteBackupsAvailableForTesting = null
    Dispatchers.resetMain()
  }

  private fun createViewModel(
    route: RegistrationRoute.ArchiveRestoreSelection = RegistrationRoute.ArchiveRestoreSelection.forOldIphoneWithoutBackup(),
    parentState: RegistrationFlowState = RegistrationFlowState(restoreMethodToken = "restore-method-token")
  ): ArchiveRestoreSelectionViewModel {
    return ArchiveRestoreSelectionViewModel(
      restoreOptions = route.restoreOptions,
      registeredState = route.registeredState,
      repository = repository,
      parentState = MutableStateFlow(parentState),
      parentEventEmitter = { parentEvents += it },
      knownAep = route.aep,
      oldPhoneIsIphone = route.oldPhoneIsIphone
    )
  }

  @Test
  fun `an old iPhone without a backup service gets the no-transfer page`() {
    val viewModel = createViewModel()

    assertThat(viewModel.state.value.showsNoTransferFromIphone).isTrue()
  }

  @Test
  fun `with a backup service the old iPhone gets the upstream restore options`() {
    TellomiRegistration.remoteBackupsAvailableForTesting = true

    val viewModel = createViewModel()

    assertThat(viewModel.state.value.showsNoTransferFromIphone).isFalse()
  }

  @Test
  fun `a manual restore that is not about an iPhone keeps the restore options`() {
    val viewModel = createViewModel(route = RegistrationRoute.ArchiveRestoreSelection.forManualRestore())

    assertThat(viewModel.state.value.showsNoTransferFromIphone).isFalse()
  }

  @Test
  fun `register directly tells the old phone and goes on to phone number entry without a skip dialog`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(ArchiveRestoreSelectionScreenEvents.TellomiRegisterDirectly)

    // 和 iOS 选「直接注册」一样告诉旧手机「不恢复」（RegistrationCoordinatorImpl.sendRestoreMethodIfNecessary 的 .decline）
    coVerify(exactly = 1) { repository.setRestoreMethod("restore-method-token", RestoreMethod.DECLINE) }
    assertThat(parentEvents).containsExactly(RegistrationFlowEvent.NavigateToScreen(RegistrationRoute.PhoneNumberEntry))
    assertThat(viewModel.state.value.showSkipWarningDialog).isFalse()
  }

  @Test
  fun `back returns to the root page instead of the QR code that was already scanned`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(ArchiveRestoreSelectionScreenEvents.TellomiBack)

    assertThat(parentEvents).containsExactly(RegistrationFlowEvent.NavigateBackToScreen(RegistrationRoute.Welcome))
    coVerify(exactly = 0) { repository.setRestoreMethod(any(), any()) }
  }
}
