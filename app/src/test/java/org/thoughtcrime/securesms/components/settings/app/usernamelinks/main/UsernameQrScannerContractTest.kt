/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.usernamelinks.main

import android.app.Activity
import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Tellomi（tellomi/tellomi#947，需求 share-qr-and-invite.md §3.2）：找人页的扫码入口扫到群邀请码，
 * 扫码页一关，开在它上面的加群弹层就跟着没了。所以链接要带回给找人页，由它来开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UsernameQrScannerContractTest {

  private val contract = UsernameQrScannerActivity.Contract()

  @Test
  fun `a scanned group invite comes back to the caller`() {
    val url = "https://tell.cc/g#CjQKIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEhAAAAAAAAAAAAAAAAAAAAAA"

    val result = contract.parseResult(Activity.RESULT_OK, UsernameQrScannerActivity.groupInviteResultIntent(url))

    assertThat(result).isEqualTo(UsernameQrScannerActivity.Result(recipientId = null, groupInviteUrl = url))
  }

  @Test
  fun `a found recipient still comes back as before`() {
    val id = RecipientId.from(7)

    val result = contract.parseResult(Activity.RESULT_OK, UsernameQrScannerActivity.recipientResultIntent(id))

    assertThat(result).isEqualTo(UsernameQrScannerActivity.Result(recipientId = id, groupInviteUrl = null))
  }

  @Test
  fun `backing out brings nothing back`() {
    assertThat(contract.parseResult(Activity.RESULT_CANCELED, null)).isNull()
  }
}
