/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.account.logout

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.appsettings.logout.LogoutAction
import org.signal.appsettings.logout.LogoutEvent
import org.signal.appsettings.logout.LogoutState.Dialog
import org.thoughtcrime.securesms.logout.TellomiLogout
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule

/**
 * Tellomi（ADR-0072，tellomi/tellomi#1414）：「退出登录」替代方案页的逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  @get:Rule
  val dispatcherRule = CoroutineDispatcherRule(testDispatcher)

  private val repository = mockk<LogoutRepository>(relaxUnitFun = true)

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    every { repository.isScreenLockEnabled() } returns false
    every { repository.hasLinkedDevices() } returns false
    coEvery { repository.logOut(any()) } returns TellomiLogout.Result.Success
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `the screen lock alternative is offered only while screen lock is off, and the switch only with linked devices`() = runTest(testDispatcher) {
    assertThat(LogoutViewModel(repository).state.value.showScreenLock).isTrue()
    assertThat(LogoutViewModel(repository).state.value.hasLinkedDevices).isFalse()

    every { repository.isScreenLockEnabled() } returns true
    every { repository.hasLinkedDevices() } returns true
    val viewModel = LogoutViewModel(repository)

    assertThat(viewModel.state.value.showScreenLock).isFalse()
    assertThat(viewModel.state.value.hasLinkedDevices).isTrue()
    assertThat(viewModel.state.value.unlinkDevices).isFalse()
  }

  @Test
  fun `the alternatives open their existing screens`() = runTest(testDispatcher) {
    val viewModel = LogoutViewModel(repository)
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(LogoutEvent.ScreenLockClicked)
    viewModel.onEvent(LogoutEvent.ManageStorageClicked)
    viewModel.onEvent(LogoutEvent.ChangePhoneNumberClicked)

    assertThat(actions).containsExactly(LogoutAction.NavigateToScreenLock, LogoutAction.NavigateToManageStorage, LogoutAction.NavigateToChangePhoneNumber)
  }

  @Test
  fun `logging out asks first`() = runTest(testDispatcher) {
    val viewModel = LogoutViewModel(repository)

    viewModel.onEvent(LogoutEvent.LogoutClicked)

    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.ConfirmLogout)
    coVerify(exactly = 0) { repository.logOut(any()) }
  }

  @Test
  fun `a confirmed logout goes to the welcome screen, passing the linked devices choice along`() = runTest(testDispatcher) {
    every { repository.hasLinkedDevices() } returns true
    val viewModel = LogoutViewModel(repository)
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(LogoutEvent.UnlinkDevicesToggled(true))
    viewModel.onEvent(LogoutEvent.LogoutClicked)
    viewModel.onEvent(LogoutEvent.LogoutConfirmed)

    coVerify(exactly = 1) { repository.logOut(unlinkDevices = true) }
    assertThat(actions).containsExactly(LogoutAction.NavigateToWelcome)
    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.None)
  }

  @Test
  fun `offline, logging out stays logged in and says it needs a connection`() = runTest(testDispatcher) {
    coEvery { repository.logOut(any()) } returns TellomiLogout.Result.NeedsNetwork
    val viewModel = LogoutViewModel(repository)
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(LogoutEvent.LogoutConfirmed)

    assertThat(actions).isEmpty()
    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.NeedsNetwork)
  }

  @Test
  fun `log out and delete asks a second time, then wipes even when offline`() = runTest(testDispatcher) {
    val viewModel = LogoutViewModel(repository)
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(LogoutEvent.LogoutAndDeleteClicked)
    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.ConfirmDeleteLocalData)
    assertThat(actions).isEmpty()

    viewModel.onEvent(LogoutEvent.DeleteLocalDataConfirmed)

    coVerify(exactly = 1) { repository.prepareForLocalDataDeletion(unlinkDevices = false) }
    coVerify(exactly = 0) { repository.logOut(any()) }
    assertThat(actions).containsExactly(LogoutAction.WipeAllData)
  }

  private fun TestScope.collectActions(actions: Flow<LogoutAction>): List<LogoutAction> {
    val collected = mutableListOf<LogoutAction>()
    backgroundScope.launch { actions.toList(collected) }
    return collected
  }
}
