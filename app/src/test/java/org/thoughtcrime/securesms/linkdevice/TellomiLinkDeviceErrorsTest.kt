/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkdevice

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository.LinkDeviceResult
import java.io.IOException

/**
 * tellomi/tellomi#1219：关联设备的状态码映射与失败对话框的内容。
 */
class TellomiLinkDeviceErrorsTest {

  private val error = IOException("status code")

  @Test
  fun `404 means no device is waiting at the code, not a network error`() {
    assertThat(TellomiLinkDeviceErrors.resultForProvisioningStatus(404, error)).isEqualTo(LinkDeviceResult.ExpiredOrForeignCode)
  }

  @Test
  fun `other status codes map as upstream`() {
    assertThat(TellomiLinkDeviceErrors.resultForProvisioningStatus(403, error)).isEqualTo(LinkDeviceResult.NoDevice)
    assertThat(TellomiLinkDeviceErrors.resultForProvisioningStatus(409, error)).isEqualTo(LinkDeviceResult.NoDevice)
    assertThat(TellomiLinkDeviceErrors.resultForProvisioningStatus(411, error)).isEqualTo(LinkDeviceResult.LimitExceeded)

    for (code in listOf(400, 422, 429, 500, 503)) {
      val result = TellomiLinkDeviceErrors.resultForProvisioningStatus(code, error)
      assertThat(result).isInstanceOf(LinkDeviceResult.NetworkError::class)
      assertThat((result as LinkDeviceResult.NetworkError).error).isSameInstanceAs(error)
    }
  }

  @Test
  fun `expired or foreign code names the other app through the placeholder`() {
    val dialog = TellomiLinkDeviceErrors.failureDialogFor(LinkDeviceResult.ExpiredOrForeignCode)!!

    assertThat(dialog.body).isEqualTo(R.string.AddLinkDeviceFragment__tellomi_code_expired_or_foreign)
    assertThat(dialog.bodyArg).isEqualTo("Signal")
    assertThat(dialog.canScanAgain).isTrue()
  }

  @Test
  fun `network error gets the reworded text and a way to scan again`() {
    val dialog = TellomiLinkDeviceErrors.failureDialogFor(LinkDeviceResult.NetworkError(error))!!

    assertThat(dialog.body).isEqualTo(R.string.AddLinkDeviceFragment__tellomi_network_error)
    assertThat(dialog.canScanAgain).isTrue()
  }

  @Test
  fun `every other failure keeps the upstream text`() {
    assertThat(TellomiLinkDeviceErrors.failureDialogFor(LinkDeviceResult.NoDevice)!!.body).isEqualTo(R.string.DeviceProvisioningActivity_content_progress_no_device)
    assertThat(TellomiLinkDeviceErrors.failureDialogFor(LinkDeviceResult.KeyError)!!.body).isEqualTo(R.string.DeviceProvisioningActivity_content_progress_key_error)
    assertThat(TellomiLinkDeviceErrors.failureDialogFor(LinkDeviceResult.BadCode)!!.body).isEqualTo(R.string.DeviceActivity_sorry_this_is_not_a_valid_device_link_qr_code)
  }

  @Test
  fun `device limit offers no scan again`() {
    val dialog = TellomiLinkDeviceErrors.failureDialogFor(LinkDeviceResult.LimitExceeded)!!

    assertThat(dialog.body).isEqualTo(R.string.DeviceProvisioningActivity_sorry_you_have_too_many_devices_linked_already)
    assertThat(dialog.canScanAgain).isFalse()
  }

  @Test
  fun `nothing to show when linking has not failed`() {
    assertThat(TellomiLinkDeviceErrors.failureDialogFor(LinkDeviceResult.None)).isNull()
    assertThat(TellomiLinkDeviceErrors.failureDialogFor(LinkDeviceResult.Success("token"))).isNull()
  }
}
