/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.signal.core.util.logging.Log
import org.signal.registration.RegistrationRepository
import org.signal.registration.StoredProfileData
import org.signal.registration.fakes.FakeNetworkController
import org.signal.registration.fakes.FakeStorageController
import org.signal.registration.fakes.SystemOutLogger
import kotlin.time.Duration.Companion.seconds

/**
 * Tellomi（tellomi/tellomi#1215）：页面上只有一个「名字」框。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateProfileViewModelTellomiTest {

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel(stored: StoredProfileData, network: FakeNetworkController = FakeNetworkController()): CreateProfileViewModel {
    val storage = FakeStorageController().apply { storedProfileData = stored }
    val repository = RegistrationRepository(
      context = mockk<Context>(relaxed = true),
      networkController = network,
      storageController = storage,
      isLinkAndSyncAvailable = false
    )
    return CreateProfileViewModel(repository) { }
  }

  @Test
  fun `stored given and family names are joined into the single field`() = runBlocking {
    val cjk = withTimeout(5.seconds) { viewModel(StoredProfileData(givenName = "三", familyName = "张")).state.first { !it.isLoading } }
    assertEquals("张三", cjk.givenName)
    assertEquals("", cjk.familyName)

    val latin = withTimeout(5.seconds) { viewModel(StoredProfileData(givenName = "Alice", familyName = "Anderson")).state.first { !it.isLoading } }
    assertEquals("Alice Anderson", latin.givenName)
    assertEquals("", latin.familyName)
  }

  @Test
  fun `auto submit keeps restored names untouched`() = runBlocking {
    val network = FakeNetworkController()
    viewModel(StoredProfileData(givenName = "Alice", familyName = "Anderson", avatar = byteArrayOf(1)), network)

    withTimeout(5.seconds) {
      while (network.lastSetProfileNames == null) {
        delay(10)
      }
    }
    assertEquals("Alice" to "Anderson", network.lastSetProfileNames)
  }
}
