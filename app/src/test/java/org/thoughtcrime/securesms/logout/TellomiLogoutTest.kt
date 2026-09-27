/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.logout

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.network.NetworkResult
import org.signal.registration.screens.shared.TellomiCrossBorderConsent
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.DeviceTransferBlockingInterceptor
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.SignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.account.AccountApi
import java.io.IOException

/**
 * Tellomi（ADR-0072，tellomi/tellomi#1414）：主设备「退出登录」。
 *
 * - 先 `DELETE /v1/accounts/gcm`，成功了才写「已退出」标记；没网就不退出（判据 2）。
 * - 标记在的时候，服务端请求只放行验证会话（重新登录要用），别的一律拦下——包括 `POST /v1/registration`。
 * - 重新登录去掉标记、重新登记推送。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiLogoutTest {

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  @get:Rule
  val signalStore = SignalStoreRule()

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val accountApi = mockk<AccountApi>()

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    val signalNetwork = mockk<SignalNetwork>()
    every { signalNetwork.accountApi } returns accountApi
    SignalNetwork.init(signalNetwork)
  }

  @After
  fun tearDown() {
    SignalNetwork.init(SignalNetwork())
  }

  @Test
  fun `logging out clears the push token first, then locks this device`() = runTest {
    every { accountApi.deleteGcmRegistrationId() } returns NetworkResult.Success(Unit)

    val result = TellomiLogout.logOut(context, unlinkDevices = false)

    assertThat(result).isEqualTo(TellomiLogout.Result.Success)
    verify(exactly = 1) { accountApi.deleteGcmRegistrationId() }
    assertThat(SignalStore.account.tellomiLoggedOut).isTrue()
    assertThat(TellomiLogout.isLoggedOut()).isTrue()
  }

  @Test
  fun `offline, logging out fails and this device stays logged in`() = runTest {
    every { accountApi.deleteGcmRegistrationId() } returns NetworkResult.NetworkError(IOException("offline"))

    val result = TellomiLogout.logOut(context, unlinkDevices = false)

    assertThat(result).isEqualTo(TellomiLogout.Result.NeedsNetwork)
    assertThat(SignalStore.account.tellomiLoggedOut).isFalse()
    assertThat(TellomiLogout.isLoggedOut()).isFalse()
  }

  @Test
  fun `log out and delete carries on when the push token can't be cleared`() = runTest {
    every { accountApi.deleteGcmRegistrationId() } returns NetworkResult.NetworkError(IOException("offline"))

    TellomiLogout.prepareForLocalDataDeletion(unlinkDevices = false)

    verify(exactly = 1) { accountApi.deleteGcmRegistrationId() }
  }

  @Test
  fun `while logged out only the verification session gets through`() {
    assertThat(TellomiLogout.isAllowedWhileLoggedOut("/v1/verification/session")).isTrue()
    assertThat(TellomiLogout.isAllowedWhileLoggedOut("/v1/verification/session/abc123")).isTrue()
    assertThat(TellomiLogout.isAllowedWhileLoggedOut("/v1/verification/session/abc123/code")).isTrue()

    assertThat(TellomiLogout.isAllowedWhileLoggedOut("/v1/registration")).isFalse()
    assertThat(TellomiLogout.isAllowedWhileLoggedOut("/v1/accounts/gcm/")).isFalse()
    assertThat(TellomiLogout.isAllowedWhileLoggedOut("/v1/messages")).isFalse()
    assertThat(TellomiLogout.isAllowedWhileLoggedOut("/v1/verification/sessionx")).isFalse()
  }

  @Test
  fun `while logged out the network layer never lets POST v1 registration out`() {
    SignalStore.account.setTellomiLoggedOut(true)
    // 跨境同意那道闸在同一个拦截器里、排在前面；这里只看退出登录这一道。闸读的是 AppDependencies 里的 Application。
    TellomiCrossBorderConsent.recordAgreement(AppDependencies.application)

    // 最后一个拦截器代替服务器：走到它这里的请求才算「发出去了」。
    val sent = mutableListOf<String>()
    val client = OkHttpClient.Builder()
      .addInterceptor(DeviceTransferBlockingInterceptor())
      .addInterceptor(
        Interceptor { chain ->
          sent += chain.request().url.encodedPath
          Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody(null))
            .build()
        }
      )
      .build()

    val registration = client.newCall(Request.Builder().url("https://chat.example/v1/registration").post("{}".toRequestBody(null)).build()).execute()
    val session = client.newCall(Request.Builder().url("https://chat.example/v1/verification/session").post("{}".toRequestBody(null)).build()).execute()

    assertThat(registration.code).isEqualTo(555)
    assertThat(session.code).isEqualTo(200)
    assertThat(sent).containsExactly("/v1/verification/session")
  }

  @Test
  fun `re-login unlocks this device and re-registers the push token`() {
    SignalStore.account.setTellomiLoggedOut(true)
    SignalStore.account.tellomiReloginPinFailed = 4

    TellomiLogout.completeRelogin(context)

    assertThat(SignalStore.account.tellomiLoggedOut).isFalse()
    assertThat(SignalStore.account.tellomiReloginPinFailed).isEqualTo(0)
    verify { AppDependencies.jobManager.add(any<RefreshAttributesJob>()) }
  }
}
