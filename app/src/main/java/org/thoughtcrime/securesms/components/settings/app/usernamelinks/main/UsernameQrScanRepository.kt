/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.usernamelinks.main

import android.content.Context
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.toOptional
import org.signal.qr.QrProcessor
import org.thoughtcrime.securesms.groups.v2.GroupInviteLinkUrl
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TellomiLinks

/**
 * A collection of functions to help with scanning QR codes for usernames.
 */
object UsernameQrScanRepository {

  /**
   * Given a URL, will attempt to lookup the username, coercing it to a standard set of [QrScanResult]s.
   *
   * Tellomi（tellomi/tellomi#947，需求 share-qr-and-invite.md §3.2「扫一扫统一」）：两个扫码入口（设置里的「扫描」、
   * 找人页）与「从相册识别」都走这里，一处认全：设备配对码 → 指路；群邀请 → 进加群；`tell.cc/<用户名>` 与
   * `tell.cc/u#u/<用户名>` → 按用户名查人（上游只认 `#eu/` 加密链接，裸用户名码一律「二维码已失效」）；
   * `#eu/` 链接（`tell.cc/u#eu` 与旧 `signal.me/#eu`）→ 照上游；其它内容 → 显示内容，不再一律「二维码无效」。
   */
  fun lookupUsernameUrl(url: String): Single<QrScanResult> {
    val scanned = url.trim()
    if (TellomiLinks.isDeviceLinkQr(scanned)) {
      return Single.just(QrScanResult.DeviceLinkCode)
    }
    if (GroupInviteLinkUrl.isGroupLink(scanned)) {
      return Single.just(QrScanResult.GroupInvite(scanned))
    }
    TellomiLinks.parsePlainUsernameFromLink(scanned)?.let { username ->
      return lookupPlainUsername(username)
    }
    if (UsernameRepository.parseLink(scanned) == null) {
      return Single.just(QrScanResult.OtherContent(scanned))
    }

    return UsernameRepository.fetchUsernameAndAciFromLink(scanned)
      .map { result ->
        when (result) {
          is UsernameRepository.UsernameLinkConversionResult.Success -> QrScanResult.Success(Recipient.externalUsername(result.aci, result.username.toString()))
          is UsernameRepository.UsernameLinkConversionResult.Invalid -> QrScanResult.InvalidData
          is UsernameRepository.UsernameLinkConversionResult.NotFound -> QrScanResult.NotFound(result.username?.toString())
          is UsernameRepository.UsernameLinkConversionResult.NetworkError -> QrScanResult.NetworkError
        }
      }
      .subscribeOn(Schedulers.io())
  }

  /** Tellomi（tellomi/tellomi#947）：裸用户名码——`username` 已是补全后的全名，与找人页同一个查法。 */
  private fun lookupPlainUsername(username: String): Single<QrScanResult> {
    return Single
      .fromCallable {
        when (val result = UsernameRepository.fetchAciForUsername(username)) {
          is UsernameRepository.UsernameAciFetchResult.Success -> QrScanResult.Success(Recipient.externalUsername(result.aci, username))
          is UsernameRepository.UsernameAciFetchResult.NotFound -> QrScanResult.NotFound(username)
          is UsernameRepository.UsernameAciFetchResult.NetworkError -> QrScanResult.NetworkError
        }
      }
      .subscribeOn(Schedulers.io())
  }

  /**
   * Given a URI pointing to an image that may contain a username QR code, this will attempt to lookup the username, coercing it to a standard set of [QrScanResult]s.
   */
  fun scanImageUriForQrCode(context: Context, uri: Uri): Single<QrScanResult> {
    val loadBitmap = Glide.with(context)
      .asBitmap()
      .format(DecodeFormat.PREFER_ARGB_8888)
      .load(uri)
      .submit()

    return Single.fromFuture(loadBitmap)
      .map { QrProcessor().getScannedData(it).toOptional() }
      .flatMap {
        if (it.isPresent) {
          lookupUsernameUrl(it.get())
        } else {
          Single.just(QrScanResult.QrNotFound)
        }
      }
      .subscribeOn(Schedulers.io())
  }
}
