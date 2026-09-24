/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import assertk.assertThat
import assertk.assertions.each
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tellomi（tellomi/tellomi#1215 第二刀）：用户名的本地规则（TR-ID-01）与候选。
 */
class TellomiUsernameEntryTest {

  @Test
  fun `valid nicknames pass`() {
    assertThat(TellomiUsernameEntry.check("kaixin")).isNull()
    assertThat(TellomiUsernameEntry.check("Kai_xin2026")).isNull()
    assertThat(TellomiUsernameEntry.check("abc")).isNull()
    assertThat(TellomiUsernameEntry.check("a".repeat(20))).isNull()
  }

  @Test
  fun `must start with a letter, including no leading underscore`() {
    assertThat(TellomiUsernameEntry.check("_kaixin")).isEqualTo(TellomiUsernameEntry.Error.MUST_START_WITH_LETTER)
    assertThat(TellomiUsernameEntry.check("2kaixin")).isEqualTo(TellomiUsernameEntry.Error.MUST_START_WITH_LETTER)
  }

  @Test
  fun `length and characters follow the upstream check`() {
    assertThat(TellomiUsernameEntry.check("ab")).isEqualTo(TellomiUsernameEntry.Error.TOO_SHORT)
    assertThat(TellomiUsernameEntry.check("a".repeat(21))).isEqualTo(TellomiUsernameEntry.Error.TOO_LONG)
    assertThat(TellomiUsernameEntry.check("kai-xin")).isEqualTo(TellomiUsernameEntry.Error.INVALID_CHARACTERS)
    assertThat(TellomiUsernameEntry.check("kai.xin")).isEqualTo(TellomiUsernameEntry.Error.INVALID_CHARACTERS)
    assertThat(TellomiUsernameEntry.check("张三")).isEqualTo(TellomiUsernameEntry.Error.INVALID_CHARACTERS)
  }

  @Test
  fun `three distinct valid candidates that differ from the nickname`() {
    val candidates = TellomiUsernameEntry.candidates("Kaixin", Random(1))

    assertThat(candidates).hasSize(3)
    assertThat(candidates.toSet()).hasSize(3)
    assertThat(candidates).each { it.transform { candidate -> TellomiUsernameEntry.check(candidate) == null && !candidate.equals("kaixin", ignoreCase = true) && candidate.startsWith("kaixin") }.isTrue() }
  }

  @Test
  fun `candidates stay within 20 characters`() {
    val candidates = TellomiUsernameEntry.candidates("a".repeat(20), Random(2))

    assertThat(candidates).hasSize(3)
    assertThat(candidates).each { it.transform { candidate -> candidate.length <= 20 }.isTrue() }
  }

  @Test
  fun `no candidates when nothing usable is left of the nickname`() {
    assertThat(TellomiUsernameEntry.candidates("张三", Random(3))).isEmpty()
    assertThat(TellomiUsernameEntry.candidates("_2kaixin", Random(3))).isEmpty()
  }
}
