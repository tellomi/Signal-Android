/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Tellomi（ADR-0072，tellomi/tellomi#1414）：主设备「退出登录」之后用同一个号码重新登录。
 *
 * 退出登录只是本机的一个状态（[PreExistingRegistrationData.loggedOut]），服务端照旧认为这台主设备注册着，别人发来的消息在服务器上排队。
 * 重新登录 = 同一个号码的验证会话拿到 `verified=true`（开了注册锁的再在本机核对 PIN），然后本机解锁。
 * **这条路上绝不调 `POST /v1/registration`**：服务端的 reclaimAccount 会清空排队的消息、删掉资料的历史版本。
 * 输入的是另一个号码时，先确认、清空本机，再按正常注册走。
 */
object TellomiRelogin {

  /** 本机核对注册锁 PIN 最多连续输错几次，与 SVR 默认的猜测次数一致。 */
  const val MAX_PIN_ATTEMPTS = 10

  /** 输满 [MAX_PIN_ATTEMPTS] 次以后锁多久，与上游注册锁「账号已锁定」页的 7 天一致。 */
  val PIN_LOCKOUT: Duration = 7.days

  private const val MASK = "****"

  /**
   * 「上次登录」和换号确认里显示的打码号码，例如 `+86 138****5678`：国家码 + 国内号码的前 3 位和后 4 位。
   * 国内号码不到 8 位时只留最后 2 位，免得打了码还几乎是原号；解析不了国家码时只显示打码后的数字。
   */
  @JvmStatic
  fun maskE164(e164: String): String {
    val util = PhoneNumberUtil.getInstance()
    val parsed = try {
      util.parse(e164, null)
    } catch (e: NumberParseException) {
      null
    }

    val national = parsed?.let { util.getNationalSignificantNumber(it) } ?: e164.filter { it.isDigit() }
    val masked = if (national.length >= 8) {
      national.take(3) + MASK + national.takeLast(4)
    } else {
      MASK + national.takeLast(2)
    }

    return if (parsed != null) "+${parsed.countryCode} $masked" else masked
  }
}
