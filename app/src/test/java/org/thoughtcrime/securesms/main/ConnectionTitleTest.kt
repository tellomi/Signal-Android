/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.main.ConnectionTitle.Companion.delayLeavingConnected
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState

/**
 * Tellomi（tellomi/tellomi#1218 F-04）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTitleTest {

  @Test
  fun `no network wins over a stale websocket state`() {
    for (state in WebSocketConnectionState.entries + null) {
      assertEquals("$state", ConnectionTitle.WAITING_FOR_NETWORK, ConnectionTitle.from(canConnect = true, networkAvailable = false, webSocketState = state, decryptionDrained = true))
    }
  }

  @Test
  fun `connected shows updating until the queue is drained`() {
    assertEquals(ConnectionTitle.UPDATING, ConnectionTitle.from(canConnect = true, networkAvailable = true, webSocketState = WebSocketConnectionState.CONNECTED, decryptionDrained = false))
    assertEquals(ConnectionTitle.NONE, ConnectionTitle.from(canConnect = true, networkAvailable = true, webSocketState = WebSocketConnectionState.CONNECTED, decryptionDrained = true))
  }

  @Test
  fun `every other websocket state with network is connecting`() {
    val connecting = listOf(
      WebSocketConnectionState.DISCONNECTED,
      WebSocketConnectionState.CONNECTING,
      WebSocketConnectionState.DISCONNECTING,
      WebSocketConnectionState.FAILED,
      null
    )

    for (state in connecting) {
      assertEquals("$state", ConnectionTitle.CONNECTING, ConnectionTitle.from(canConnect = true, networkAvailable = true, webSocketState = state, decryptionDrained = false))
    }
  }

  @Test
  fun `unlinked, deprecated or not registered never spins`() {
    assertEquals(ConnectionTitle.NONE, ConnectionTitle.from(canConnect = true, networkAvailable = true, webSocketState = WebSocketConnectionState.AUTHENTICATION_FAILED, decryptionDrained = false))
    assertEquals(ConnectionTitle.NONE, ConnectionTitle.from(canConnect = true, networkAvailable = true, webSocketState = WebSocketConnectionState.REMOTE_DEPRECATED, decryptionDrained = false))
    assertEquals(ConnectionTitle.NONE, ConnectionTitle.from(canConnect = false, networkAvailable = false, webSocketState = WebSocketConnectionState.DISCONNECTED, decryptionDrained = false))
  }

  @Test
  fun `leaving connected waits, a short blip shows nothing, everything else is immediate`() = runTest {
    val upstream = flow {
      emit(ConnectionTitle.NONE)
      delay(1_000)
      emit(ConnectionTitle.CONNECTING) // t=1000：离开「已连上」，要等 300ms
      delay(100)
      emit(ConnectionTitle.NONE) // t=1100：等待期间就回来了，什么都不显示
      delay(1_000)
      emit(ConnectionTitle.CONNECTING) // t=2100：这次没回来，t=2400 显示
      delay(1_000)
      emit(ConnectionTitle.UPDATING) // t=3100：不是从「已连上」离开，立刻显示
      delay(50)
      emit(ConnectionTitle.NONE) // t=3150：回到「已连上」立刻显示
    }

    val seen = mutableListOf<Pair<Long, ConnectionTitle>>()
    upstream.delayLeavingConnected().collect { seen += testScheduler.currentTime to it }

    assertEquals(
      listOf(
        0L to ConnectionTitle.NONE,
        2_400L to ConnectionTitle.CONNECTING,
        3_100L to ConnectionTitle.UPDATING,
        3_150L to ConnectionTitle.NONE
      ),
      seen
    )
  }

  @Test
  fun `first value is shown immediately even when not connected`() = runTest {
    val seen = mutableListOf<Pair<Long, ConnectionTitle>>()
    flow { emit(ConnectionTitle.WAITING_FOR_NETWORK) }.delayLeavingConnected().collect { seen += testScheduler.currentTime to it }

    assertEquals(listOf(0L to ConnectionTitle.WAITING_FOR_NETWORK), seen)
  }
}
