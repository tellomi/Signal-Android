/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.recipients

import android.content.Context
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.mockk
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#1106 第三刀，ADR-0066 §九）：没有任何名字、只能拿用户名兜底时，
 * `.01` 结尾的去掉后缀显示，别的后缀完整显示——`kaixin.57` 必须显示成 `kaixin.57`，不能冒充 `kaixin`。
 */
class RecipientTellomiDisplayUsernameTest {

  private val context: Context = mockk(relaxed = true)

  private fun withUsername(username: String) = Recipient(id = RecipientId.from(42), isResolving = false, usernameValue = username)

  /** BidiUtil 会给名字包上方向隔离符，比较前去掉。 */
  private fun String.withoutBidi(): String = filterNot { it in "⁦⁧⁨⁩‪‫‬‭‮‎‏" }

  @Test
  fun displayNameHidesTheFixedDiscriminator() {
    assertThat(withUsername("kaixin.01").getDisplayName(context).withoutBidi()).isEqualTo("kaixin")
    assertThat(withUsername("kaixin.01").getShortDisplayName(context).withoutBidi()).isEqualTo("kaixin")
  }

  @Test
  fun otherDiscriminatorsAreShownInFull() {
    assertThat(withUsername("kaixin.57").getDisplayName(context).withoutBidi()).isEqualTo("kaixin.57")
    assertThat(withUsername("kaixin.57").getShortDisplayName(context).withoutBidi()).isEqualTo("kaixin.57")
  }

  @Test
  fun theStoredUsernameStaysTheProtocolForm() {
    // 只改显示；查找、链接、hash 仍用完整用户名
    assertThat(withUsername("kaixin.01").username.get()).isEqualTo("kaixin.01")
  }
}
