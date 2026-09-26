/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.screens.shared.TellomiCrossBorderConsent
import org.signal.registration.screens.shared.TellomiLegalConsent
import org.signal.registration.screens.util.MockMultiplePermissionsState
import org.signal.registration.screens.util.MockPermissionsState
import org.signal.registration.test.TestTags

/**
 * Tests for registration navigation flow using Navigation 3.
 * Tests navigation by verifying UI state changes rather than using NavController.
 */
@OptIn(ExperimentalPermissionsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RegistrationNavigationTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private lateinit var viewModel: RegistrationViewModel
  private lateinit var mockRepository: RegistrationRepository

  @Before
  fun setup() {
    // Tellomi：首次启动提示和跨境告知另有用例（TellomiLegalConsentTest）；这里测导航，先当作都同意过（tellomi/tellomi#1211、#1133）。
    val context = ApplicationProvider.getApplicationContext<Application>()
    TellomiLegalConsent.acceptFirstLaunchNotice(context)
    TellomiCrossBorderConsent.recordAgreement(context)

    mockRepository = mockk<RegistrationRepository>(relaxed = true)
    coEvery { mockRepository.restoreFlowState() } returns null
    coEvery { mockRepository.getPreExistingRegistrationData() } returns null
    viewModel = RegistrationViewModel(mockRepository, SavedStateHandle())
    // Allow the init coroutine to complete so isRestoring becomes false.
    Shadows.shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun `navigation starts at Welcome screen`() {
    // Given
    val permissionsState = createMockPermissionsState()

    // Verify the ViewModel state is correctly initialized
    val state = viewModel.state.value
    assert(!state.isRestoringNavigationState) { "isRestoring should be false after init, was: ${state.isRestoringNavigationState}" }
    assert(state.backStack == listOf(RegistrationRoute.Welcome)) { "backStack should be [Welcome], was: ${state.backStack}" }

    composeTestRule.setContent {
      SignalTheme(incognitoKeyboardEnabled = false) {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // Then - verify Welcome screen is displayed
    composeTestRule.onNodeWithTag(TestTags.WELCOME_SCREEN).assertIsDisplayed()
  }

  /**
   * Tellomi（tellomi/tellomi#1112）：注册流程里一个权限都不要。Robolectric 默认什么权限都没授，
   * 上游在这里会进 Permissions；Tellomi 必须直达输手机号。
   */
  @Test
  fun `clicking Get Started goes straight to PhoneNumber without a permissions screen`() {
    // Given
    val permissionsState = createMockPermissionsState()

    composeTestRule.setContent {
      SignalTheme {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).performClick()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    // Then - no Permissions screen; PhoneNumber is displayed
    composeTestRule.onNodeWithTag(TestTags.PERMISSIONS_SCREEN).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_SCREEN).assertIsDisplayed()
  }

  @Test
  fun `clicking Get Started skips Permissions when all are already granted`() {
    // Given
    val context = ApplicationProvider.getApplicationContext<Application>()
    Shadows.shadowOf(context).grantPermissions(*RegistrationPermissions.getRequiredPermissions(context).toTypedArray())
    val permissionsState = createMockPermissionsState()

    composeTestRule.setContent {
      SignalTheme {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).performClick()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    // Then - the Permissions screen is skipped and PhoneNumber is displayed
    composeTestRule.onNodeWithTag(TestTags.PHONE_NUMBER_SCREEN).assertIsDisplayed()
  }

  // Tellomi（#1112）：上游这里还有两条用例点 Permissions 页的「下一步 / 以后再说」；那一页已经没有入口，用例随之删掉。

  // Note: Back navigation testing in Navigation 3 requires testing through
  // actual back button presses at the Activity level, which is better suited
  // for instrumentation tests. The back stack is managed internally by Nav3
  // and not directly accessible in unit tests.

  @Test
  fun `clicking I have my old phone goes straight to the quick restore scan`() {
    // Given
    val permissionsState = createMockPermissionsState()

    composeTestRule.setContent {
      SignalTheme {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_HAS_OLD_PHONE_BUTTON).performClick()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    // Then - Tellomi（#1112）：不经过 Permissions，直接到扫码恢复（相机权限由扫码页在用的时候自己要）
    composeTestRule.onNodeWithTag(TestTags.PERMISSIONS_SCREEN).assertDoesNotExist()
    assert(viewModel.state.value.backStack.last() == RegistrationRoute.QuickRestoreQrScan) { "backStack should end at QuickRestoreQrScan, was: ${viewModel.state.value.backStack}" }
  }

  @Test
  @Config(qualifiers = "w1280dp-h800dp-xhdpi")
  fun `when isLinkAndSyncAvailable is true, Welcome shows the link device button`() {
    // Given
    every { mockRepository.isLinkAndSyncAvailable } returns true
    val permissionsState = createMockPermissionsState()

    composeTestRule.setContent {
      SignalTheme {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.WELCOME_LINK_DEVICE_BUTTON).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).assertDoesNotExist()
  }

  @Test
  fun `when isLinkAndSyncAvailable is true on compact display, link device button is not shown`() {
    // Given
    every { mockRepository.isLinkAndSyncAvailable } returns true
    val permissionsState = createMockPermissionsState()

    composeTestRule.setContent {
      SignalTheme {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // Then - compact layout always uses the primary buttons regardless of the flag
    composeTestRule.onNodeWithTag(TestTags.WELCOME_LINK_DEVICE_BUTTON).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w800dp-h800dp-xhdpi")
  fun `when isLinkAndSyncAvailable is true on medium display, link device button is not shown`() {
    // Given
    every { mockRepository.isLinkAndSyncAvailable } returns true
    val permissionsState = createMockPermissionsState()

    composeTestRule.setContent {
      SignalTheme {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // Then - medium layout always uses the primary buttons regardless of the flag
    composeTestRule.onNodeWithTag(TestTags.WELCOME_LINK_DEVICE_BUTTON).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w1280dp-h800dp-xhdpi")
  fun `when isLinkAndSyncAvailable is false, Welcome does not show the link device button`() {
    // Given - relaxed mock returns false for isLinkAndSyncAvailable by default
    val permissionsState = createMockPermissionsState()

    composeTestRule.setContent {
      SignalTheme {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // Then
    composeTestRule.onNodeWithTag(TestTags.WELCOME_LINK_DEVICE_BUTTON).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w1280dp-h800dp-xhdpi")
  fun `clicking Link Device on Welcome goes straight to LinkAccount without asking for notifications`() {
    // Given
    every { mockRepository.isLinkAndSyncAvailable } returns true
    val permissionsState = createMockPermissionsState()

    composeTestRule.setContent {
      SignalTheme {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_LINK_DEVICE_BUTTON).performClick()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    // Then - Tellomi（tellomi/tellomi#1112）：默认 Robolectric SDK（>= 33）上没授通知，上游会先进 AllowNotifications；
    // Tellomi 注册 / 链接流程里不要权限，通知改到第一次进首屏时说明（#1218 F-01）
    composeTestRule.onNodeWithTag(TestTags.ALLOW_NOTIFICATIONS_SCREEN).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_SCREEN).assertIsDisplayed()
  }

  @Test
  fun `clicking I don't have my old phone navigates to Restore`() {
    // Given
    val permissionsState = createMockPermissionsState()

    composeTestRule.setContent {
      SignalTheme {
        RegistrationNavHost(
          registrationRepository = mockRepository,
          registrationViewModel = viewModel,
          permissionsState = permissionsState
        )
      }
    }

    // When
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_NO_OLD_PHONE_BUTTON).performClick()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    // Then - verify Restore screen is displayed (or its expected content)
    // Note: Update this assertion based on actual Restore screen content when implemented
  }

  /**
   * Creates a mock permissions state for testing.
   * Since we're in JUnit tests, we can't use the real rememberMultiplePermissionsState.
   */
  private fun createMockPermissionsState(allPermissionsGranted: Boolean = false): MockMultiplePermissionsState {
    return MockMultiplePermissionsState(
      allPermissionsGranted = allPermissionsGranted,
      permissions = RegistrationPermissions.getRequiredPermissions(isModernBackupDirectorySelectionRequired = false).map { MockPermissionsState(it) }
    )
  }
}
