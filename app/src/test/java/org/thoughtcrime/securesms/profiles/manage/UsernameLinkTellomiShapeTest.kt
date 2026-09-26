/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.profiles.manage

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import org.junit.Test
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository.toLink
import org.whispersystems.signalservice.api.push.UsernameLinkComponents
import java.util.UUID

/**
 * Tellomi（tellomi/tellomi#1113）：用户名链接 / 二维码发出的是 tell.cc 形状。
 * 单独一个文件，不往上游的 UsernameRepositoryTest 里加——那个文件 #1106 的改名冷却也在加用例，放一起会互相冲突。
 */
class UsernameLinkTellomiShapeTest {
  @Test
  fun toLink_generates_tell_cc_shape_and_round_trips() {
    val components = UsernameLinkComponents(
      entropy = ByteArray(32) { it.toByte() },
      serverId = UUID.fromString("7b0f5d0e-3c1a-4a55-9c1e-2f4d6b8a9e01")
    )

    val link = components.toLink()

    assertThat(link).startsWith("https://tell.cc/u#eu/")
    assertThat(UsernameRepository.parseLink(link)).isEqualTo(components)
    // 同一段编码换成旧的 signal.me 形状仍能解析
    assertThat(UsernameRepository.parseLink(link.replace("https://tell.cc/u#eu/", "https://signal.me/#eu/"))).isEqualTo(components)
  }
}
