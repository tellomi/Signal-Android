/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.usernamelinks.main

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.reactivex.rxjava3.core.Single
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Tellomi（tellomi/tellomi#947，需求 share-qr-and-invite.md §3.2「扫一扫统一」）：两个扫码入口与「从相册识别」
 * 共用的 [UsernameQrScanRepository.lookupUsernameUrl] 一处认全 Tellomi 的码，别的内容也给出反应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UsernameQrScanRepositoryTest {

  @After
  fun tearDown() {
    unmockkStatic(UsernameRepository::class)
    unmockkObject(Recipient)
  }

  private fun scan(text: String): QrScanResult = UsernameQrScanRepository.lookupUsernameUrl(text).blockingGet()

  @Test
  fun deviceLinkCodesInBothSchemesPointToTheRightPlace() {
    assertThat(scan("tellomi://linkdevice?uuid=a&pub_key=b")).isSameInstanceAs(QrScanResult.DeviceLinkCode)
    assertThat(scan("sgnl://linkdevice?uuid=a&pub_key=b")).isSameInstanceAs(QrScanResult.DeviceLinkCode)
  }

  @Test
  fun groupInvitesGoToTheJoinFlow() {
    val result = scan("https://tell.cc/g#abc")
    assertThat(result).isInstanceOf(QrScanResult.GroupInvite::class)
    assertThat((result as QrScanResult.GroupInvite).url).isEqualTo("https://tell.cc/g#abc")
    assertThat(scan("https://signal.group/#abc")).isInstanceOf(QrScanResult.GroupInvite::class)
  }

  @Test
  fun phoneNumberCardIsNotMistakenForAGroupInvite() {
    // Android #973 撞过：只看 host 会把 tell.cc/u#p/… 当群邀请去解码
    assertThat(scan("https://tell.cc/u#p/+16505550100")).isInstanceOf(QrScanResult.OtherContent::class)
  }

  @Test
  fun plainUsernameIsLookedUpByName() {
    val aci: ServiceId.ACI = mockk(relaxed = true)
    val recipient: Recipient = mockk(relaxed = true)
    mockkStatic(UsernameRepository::class)
    mockkObject(Recipient)
    every { UsernameRepository.fetchAciForUsername("kaixin.01") } returns UsernameRepository.UsernameAciFetchResult.Success(aci)
    every { UsernameRepository.fetchAciForUsername("ceshi.57") } returns UsernameRepository.UsernameAciFetchResult.NotFound
    every { Recipient.externalUsername(aci, "kaixin.01") } returns recipient

    // 裸 nickname 补 .01（#16）；带后缀的原样
    val found = scan("https://tell.cc/kaixin")
    assertThat(found).isInstanceOf(QrScanResult.Success::class)
    assertThat((found as QrScanResult.Success).recipient).isSameInstanceAs(recipient)

    val notFound = scan("tellomi://tell.cc/u#u/ceshi.57")
    assertThat(notFound).isInstanceOf(QrScanResult.NotFound::class)
    assertThat((notFound as QrScanResult.NotFound).username).isEqualTo("ceshi.57")

    verify(exactly = 0) { UsernameRepository.fetchUsernameAndAciFromLink(any()) }
  }

  @Test
  fun encryptedUsernameLinksStillGoThroughTheUpstreamPath() {
    mockkStatic(UsernameRepository::class)
    every { UsernameRepository.fetchUsernameAndAciFromLink(any()) } returns Single.just(UsernameRepository.UsernameLinkConversionResult.Invalid)

    // 48 字节的 #eu/ 负载（内容无所谓，查询由桩返回）
    val eu = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    assertThat(scan("https://tell.cc/u#eu/$eu")).isSameInstanceAs(QrScanResult.InvalidData)
    assertThat(scan("https://signal.me/#eu/$eu")).isSameInstanceAs(QrScanResult.InvalidData)
  }

  @Test
  fun anythingElseIsShownNotRejected() {
    val web = scan("https://example.com/a?b=c")
    assertThat(web).isInstanceOf(QrScanResult.OtherContent::class)
    assertThat((web as QrScanResult.OtherContent).text).isEqualTo("https://example.com/a?b=c")

    val text = scan("  你好 hello  ")
    assertThat((text as QrScanResult.OtherContent).text).isEqualTo("你好 hello")
  }
}
