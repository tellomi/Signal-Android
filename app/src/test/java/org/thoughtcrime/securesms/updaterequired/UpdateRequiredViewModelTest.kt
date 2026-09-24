/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule
import org.thoughtcrime.securesms.updaterequired.UpdateDownloadSnapshot.Status
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredState.Download

/**
 * Tellomi（tellomi/tellomi#1138）：「必须更新」阻断页的状态机。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRequiredViewModelTest {

  companion object {
    private const val POLL_MS = 500L
    private const val DOWNLOAD_ID = 7L
  }

  private val testDispatcher = StandardTestDispatcher()

  @get:Rule
  val dispatcherRule = CoroutineDispatcherRule(testDispatcher)

  private val repository = FakeRepository()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `without the install permission it explains first, then opens settings`() = runTest(testDispatcher) {
    repository.canInstall = false
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.NeedsInstallPermission)
    assertThat(actions).isEmpty()
    assertThat(repository.checks).isEqualTo(0)

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(actions).containsExactly(UpdateRequiredScreenAction.OpenInstallPermissionSettings)
    assertThat(repository.checks).isEqualTo(0)
  }

  @Test
  fun `coming back from settings with the permission granted carries on with the update`() = runTest(testDispatcher) {
    repository.canInstall = false
    val viewModel = createViewModel()
    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    repository.canInstall = true
    repository.script(snapshot(Status.SUCCESSFUL, 100, 100))
    viewModel.onEvent(UpdateRequiredScreenEvent.ScreenResumed)
    runCurrent()

    assertThat(repository.checks).isEqualTo(1)
    assertThat(repository.installs).containsExactly(DOWNLOAD_ID)
  }

  @Test
  fun `download progress is drawn in the button and the installer opens once it finishes`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    repository.script(
      snapshot(Status.PENDING, 0, -1),
      snapshot(Status.RUNNING, 42, 100),
      snapshot(Status.SUCCESSFUL, 100, 100)
    )

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = null))

    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = null))

    advanceTimeBy(POLL_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = 42))
    assertThat(viewModel.state.value.totalBytes).isEqualTo(100L)

    advanceTimeBy(POLL_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.ReadyToInstall)
    assertThat(repository.installs).containsExactly(DOWNLOAD_ID)
  }

  @Test
  fun `tapping install again after cancelling the system installer does not download again`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    repository.script(snapshot(Status.SUCCESSFUL, 100, 100))
    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(repository.checks).isEqualTo(1)
    assertThat(repository.installs).containsExactly(DOWNLOAD_ID, DOWNLOAD_ID)
  }

  @Test
  fun `a failed check turns the button into retry`() = runTest(testDispatcher) {
    repository.checkResult = false
    val viewModel = createViewModel()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(repository.checks).isEqualTo(2)
  }

  @Test
  fun `a failed download turns the button into retry`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    repository.script(snapshot(Status.RUNNING, 10, 100), snapshot(Status.FAILED, 10, 100))

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    advanceTimeBy(POLL_MS)
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)
    assertThat(repository.installs).isEmpty()
  }

  @Test
  fun `a manifest with nothing newer says so instead of spinning`() = runTest(testDispatcher) {
    repository.availableVersion = null
    val viewModel = createViewModel()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.NoNewerVersion)
  }

  @Test
  fun `a download that never shows up ends in retry`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = null))

    advanceTimeBy(UpdateRequiredViewModel.NO_DOWNLOAD_TIMEOUT_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)
  }

  @Test
  fun `a package the background job already finished waits for the user before installing`() = runTest(testDispatcher) {
    repository.script(snapshot(Status.SUCCESSFUL, 100, 100))
    val viewModel = createViewModel()
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.ReadyToInstall)
    assertThat(repository.installs).isEmpty()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(repository.installs).containsExactly(DOWNLOAD_ID)
    assertThat(repository.checks).isEqualTo(0)
  }

  @Test
  fun `builds that do not update themselves send the user to the download page`() = runTest(testDispatcher) {
    repository.managesAppUpdates = false
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    viewModel.onEvent(UpdateRequiredScreenEvent.DownloadFromWebsiteClicked)
    runCurrent()

    assertThat(actions).containsExactly(UpdateRequiredScreenAction.OpenDownloadPage, UpdateRequiredScreenAction.OpenDownloadPage)
    assertThat(repository.checks).isEqualTo(0)
  }

  @Test
  fun `the offline hint follows the connection`() = runTest(testDispatcher) {
    repository.online = false
    val viewModel = createViewModel()
    assertThat(viewModel.state.value.isOffline).isEqualTo(true)

    repository.online = true
    viewModel.onEvent(UpdateRequiredScreenEvent.ScreenResumed)
    assertThat(viewModel.state.value.isOffline).isEqualTo(false)
  }

  private fun createViewModel(): UpdateRequiredViewModel {
    return UpdateRequiredViewModel(repository, pollIntervalMs = POLL_MS)
  }

  private fun TestScope.collectActions(viewModel: UpdateRequiredViewModel): List<UpdateRequiredScreenAction> {
    val actions = mutableListOf<UpdateRequiredScreenAction>()
    backgroundScope.launch { viewModel.actions.toList(actions) }
    runCurrent()
    return actions
  }

  private fun snapshot(status: Status, bytesSoFar: Long, totalBytes: Long): UpdateDownloadSnapshot {
    return UpdateDownloadSnapshot(DOWNLOAD_ID, status, bytesSoFar, totalBytes)
  }

  private class FakeRepository : UpdateRequiredRepository {
    override var managesAppUpdates: Boolean = true
    var canInstall = true
    var online = true
    var checkResult = true
    var availableVersion: String? = "0.1.3"
    var checks = 0
    val installs = mutableListOf<Long>()

    /** 每次 [currentDownload] 取下一个；取完之后一直返回最后一个。 */
    private val scripted = ArrayDeque<UpdateDownloadSnapshot>()
    private var last: UpdateDownloadSnapshot? = null

    fun script(vararg snapshots: UpdateDownloadSnapshot) {
      scripted.addAll(snapshots)
    }

    override fun reason(): UpdateRequiredState.Reason = UpdateRequiredState.Reason.SERVER_REJECTED
    override fun canRequestPackageInstalls(): Boolean = canInstall
    override fun isOnline(): Boolean = online

    override fun runRequiredUpdateCheck(): Boolean {
      checks++
      return checkResult
    }

    override fun availableVersionName(): String? = availableVersion

    override fun currentDownload(): UpdateDownloadSnapshot? {
      if (scripted.isNotEmpty()) {
        last = scripted.removeFirst()
      }
      return last
    }

    override fun install(downloadId: Long) {
      installs += downloadId
    }
  }
}
