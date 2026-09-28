/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.links

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Test
import org.signal.libsignal.links.LinkRegistry

/**
 * Smoke test for the libsignal build this app pins (tellomi/tellomi#1418, #1127): the published
 * artifact carries rust/links and its JNI bridge, loads a registry dist, and classifies one golden
 * sample exactly the way Rust does (the sample is copied from libsignal's bridge-golden.json).
 */
class LinkRegistrySmokeTest {

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  private val case = ObjectMapper().readTree(resource("classify-smoke.json"))

  private fun registry(): LinkRegistry = LinkRegistry.load(resource(case["registry"].asText()))

  @Test
  fun `loads the registry dist`() {
    assertThat(registry().version).isEqualTo(case["registry_version"].asLong())
  }

  @Test
  fun `classifies a golden sample byte for byte`() {
    val card = registry().classify(case["preview"].asText(), case["body"].asText(), case["message"].asText())

    assertThat(card).isEqualTo(case["card"].asText())
  }
}
