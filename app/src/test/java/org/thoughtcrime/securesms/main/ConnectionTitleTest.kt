/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.thoughtcrime.securesms.main.ConnectionTitle.Companion.delayLeavingConnected
import org.thoughtcrime.securesms.messages.IncomingMessageObserver
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState

/**
 * Tellomi（tellomi/tellomi#1218 F-04）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTitleTest {

  /**
   * 构建到期或服务端回 499 时 isClientDeprecated = true，websocket 自己的 canConnect 为 false，状态停在 DISCONNECTED；
   * 首屏上已经有上游的「此版本已过期」横幅，标题不能再一直「连接中…」转圈（A3b 审查）。
   */
  @Test
  fun `a deprecated build does not count as connecting`() {
    assertFalse(ConnectionTitle.canConnect(isRegistered = true, isUnauthorized = false, isClientDeprecated = true))
    val canConnect = ConnectionTitle.canConnect(isRegistered = true, isUnauthorized = false, isClientDeprecated = true)
    assertEquals(ConnectionTitle.NONE, ConnectionTitle.from(canConnect = canConnect, networkAvailable = true, webSocketState = WebSocketConnectionState.DISCONNECTED, decryptionDrained = false))
  }

  /**
   * AppDependencies.resetNetwork()（跨境同意、FCM 恢复、系统代理变化、PNI 变更……）会换一个新的 IncomingMessageObserver，
   * 新连接由新实例接收；「收完了」要挂到新实例上，否则标题一直停在「收取中…」（A3b 审查）。
   */
  @Test
  fun `the drained listener moves to the observer that resetNetwork puts in place`() {
    val first = mockk<IncomingMessageObserver>(relaxed = true)
    val second = mockk<IncomingMessageObserver>(relaxed = true)
    var current = first
    val listener = Runnable { }
    val tracker = DrainedListenerTracker({ current }, listener)

    assertSame(first, tracker.currentObserver())
    current = second
    assertSame(second, tracker.currentObserver())
    tracker.release()

    verify(exactly = 1) { first.addDecryptionDrainedListener(listener) }
    verify(exactly = 1) { first.removeDecryptionDrainedListener(listener) }
    verify(exactly = 1) { second.addDecryptionDrainedListener(listener) }
    verify(exactly = 1) { second.removeDecryptionDrainedListener(listener) }
  }

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
