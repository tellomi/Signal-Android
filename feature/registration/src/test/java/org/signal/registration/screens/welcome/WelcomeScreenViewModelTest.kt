/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.welcome

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.signal.registration.PreExistingRegistrationData
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationFlowState
import org.signal.registration.RegistrationRepository
import org.signal.registration.RegistrationRoute

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeScreenViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  private lateinit var mockRepository: RegistrationRepository
  private lateinit var emittedParentEvents: MutableList<RegistrationFlowEvent>
  private lateinit var parentEventEmitter: (RegistrationFlowEvent) -> Unit
  private lateinit var emittedStates: MutableList<WelcomeScreenState>
  private lateinit var stateEmitter: (WelcomeScreenState) -> Unit

  @Before
  fun setup() {
    Dispatchers.setMain(testDispatcher)
    mockRepository = mockk(relaxed = true)
    every { mockRepository.isLinkAndSyncAvailable } returns false
    emittedParentEvents = mutableListOf()
    parentEventEmitter = { event -> emittedParentEvents.add(event) }
    emittedStates = mutableListOf()
    stateEmitter = { state -> emittedStates.add(state) }
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `while the parent flow is still loading the restore or transfer option stays hidden`() {
    val viewModel = createViewModel()

    viewModel.applyEvent(WelcomeScreenState(showRestoreOrTransfer = false), WelcomeScreenEvents.ParentStateChanged(RegistrationFlowState(isRestoringNavigationState = true)), parentEventEmitter, stateEmitter)

    assertThat(emittedStates.last().showRestoreOrTransfer).isFalse()
  }

  @Test
  fun `once loaded with no pre-existing data the restore or transfer option is shown`() {
    val viewModel = createViewModel()

    viewModel.applyEvent(WelcomeScreenState(showRestoreOrTransfer = false), WelcomeScreenEvents.ParentStateChanged(RegistrationFlowState(isRestoringNavigationState = false, preExistingRegistrationData = null)), parentEventEmitter, stateEmitter)

    assertThat(emittedStates.last().showRestoreOrTransfer).isTrue()
  }

  @Test
  fun `once loaded with pre-existing data the restore or transfer option stays hidden`() {
    val viewModel = createViewModel()

    viewModel.applyEvent(WelcomeScreenState(showRestoreOrTransfer = false), WelcomeScreenEvents.ParentStateChanged(RegistrationFlowState(isRestoringNavigationState = false, preExistingRegistrationData = mockk<PreExistingRegistrationData>(relaxed = true))), parentEventEmitter, stateEmitter)

    assertThat(emittedStates.last().showRestoreOrTransfer).isFalse()
  }

  @Test
  fun `Continue navigates straight to phone number entry`() {
    val viewModel = createViewModel()

    viewModel.applyEvent(WelcomeScreenState(), WelcomeScreenEvents.Continue, parentEventEmitter, stateEmitter)

    assertThat(emittedParentEvents.last())
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isEqualTo(RegistrationRoute.PhoneNumberEntry)
  }

  /**
   * Tellomi（tellomi/tellomi#1112）：注册流程里一个权限都不要。上游在没有权限时会先导航到 Permissions /
   * AllowNotifications；这里把欢迎页的四个出口都走一遍，任何一个再插回权限页都会红。
   */
  @Test
  fun `no welcome action routes through a permissions screen`() {
    val viewModel = createViewModel()

    listOf(
      WelcomeScreenEvents.Continue,
      WelcomeScreenEvents.HasOldPhone,
      WelcomeScreenEvents.DoesNotHaveOldPhone,
      WelcomeScreenEvents.LinkDevice
    ).forEach { viewModel.applyEvent(WelcomeScreenState(), it, parentEventEmitter, stateEmitter) }

    val routes = emittedParentEvents.filterIsInstance<RegistrationFlowEvent.NavigateToScreen>().map { it.route }
    assertThat(routes.size).isEqualTo(4)
    assertThat(routes.none { it is RegistrationRoute.Permissions || it is RegistrationRoute.AllowNotifications }).isTrue()
  }

  @Test
  fun `HasOldPhone navigates straight to the quick restore scan`() {
    val viewModel = createViewModel()

    viewModel.applyEvent(WelcomeScreenState(), WelcomeScreenEvents.HasOldPhone, parentEventEmitter, stateEmitter)

    assertThat(emittedParentEvents.last())
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isEqualTo(RegistrationRoute.QuickRestoreQrScan)
  }

  @Test
  fun `DoesNotHaveOldPhone navigates straight to the manual restore selection`() {
    val viewModel = createViewModel()

    viewModel.applyEvent(WelcomeScreenState(), WelcomeScreenEvents.DoesNotHaveOldPhone, parentEventEmitter, stateEmitter)

    assertThat(emittedParentEvents.last())
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isEqualTo(RegistrationRoute.ArchiveRestoreSelection.forManualRestore())
  }

  @Test
  fun `LinkDevice navigates straight to link account`() {
    val viewModel = createViewModel()

    viewModel.applyEvent(WelcomeScreenState(), WelcomeScreenEvents.LinkDevice, parentEventEmitter, stateEmitter)

    assertThat(emittedParentEvents.last())
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isInstanceOf<RegistrationRoute.LinkAccount>()
  }

  @Test
  fun `ViewTermsAndPrivacy emits an action to open the terms and privacy page`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)

    viewModel.applyEvent(WelcomeScreenState(), WelcomeScreenEvents.ViewTermsAndPrivacy, parentEventEmitter, stateEmitter)

    assertThat(actions).containsExactly(WelcomeScreenActions.ViewTermsAndPrivacy)
  }

  private fun TestScope.collectActions(viewModel: WelcomeScreenViewModel): List<WelcomeScreenActions> {
    val actions = mutableListOf<WelcomeScreenActions>()
    backgroundScope.launch(testDispatcher) { viewModel.actions.collect { actions.add(it) } }
    return actions
  }

  private fun createViewModel(
    parentState: RegistrationFlowState = RegistrationFlowState()
  ): WelcomeScreenViewModel {
    return WelcomeScreenViewModel(mockRepository, MutableStateFlow(parentState), parentEventEmitter)
  }
}
