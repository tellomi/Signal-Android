/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tellomi（#1078）：`global.gif.provider` 与上游 `global.gifSearch` 合成一个结论。
 *
 * 这条不只是「功能开关」——大陆发行的包靠 `provider = none` 把 GIF 整条关掉，关掉之后
 * 包里才不会再有指向 `contentproxy.tellomi.app`（境外、**未备案**域）的连接。
 * 所以判错一次就是 ADR-0065 欠账 1 说的「漏一条就是漏报」，不是少一个表情面板。
 *
 * 测的是 [RemoteConfig.isGifAvailable] 这个纯函数而不是 `gifAvailable` 属性：
 * 读 delegate 会触发 `RemoteConfig.init()`，需要 app context，纯 JVM 单测里直接 NPE。
 * 判据钉的是**决定**（两个值怎么合成一个结论），delegate 的读取机制是上游的事。
 */
class RemoteConfigGifTest {

  @Test
  fun `no provider delivered falls back to available`() {
    assertTrue(RemoteConfig.isGifAvailable(upstreamGifSearch = true, provider = null))
  }

  @Test
  fun `provider giphy keeps gif available`() {
    assertTrue(RemoteConfig.isGifAvailable(upstreamGifSearch = true, provider = "giphy"))
  }

  @Test
  fun `provider none disables gif`() {
    assertFalse(RemoteConfig.isGifAvailable(upstreamGifSearch = true, provider = "none"))
  }

  @Test
  fun `provider none is case insensitive`() {
    // 服务端那份 YAML 是人写的，别让一个大写把大陆包的 GIF 又打开。
    assertFalse(RemoteConfig.isGifAvailable(upstreamGifSearch = true, provider = "NONE"))
    assertFalse(RemoteConfig.isGifAvailable(upstreamGifSearch = true, provider = "None"))
  }

  @Test
  fun `upstream switch off disables gif even when provider says giphy`() {
    // 两个条件是**与**：上游整体关掉时，我们下发 provider=giphy 也不该把它打开。
    assertFalse(RemoteConfig.isGifAvailable(upstreamGifSearch = false, provider = "giphy"))
  }

  @Test
  fun `provider none wins over upstream switch on`() {
    // 反向对照，也是大陆包实际走的那条：上游开着、我们说 none，结果必须是关。
    assertFalse(RemoteConfig.isGifAvailable(upstreamGifSearch = true, provider = "none"))
  }

  @Test
  fun `an unknown provider is treated as available, not as off`() {
    // 以后接了别的源（provider = "xxx"）时，老客户端不认识它——此时该按「有源」处理，
    // 而不是悄悄关掉。只有明确的 none 才是关。
    assertTrue(RemoteConfig.isGifAvailable(upstreamGifSearch = true, provider = "sogou"))
  }

  @Test
  fun `empty string is not none`() {
    // 服务端把值写成空串是配置错误，不该被当成「关掉」——那会变成一次查不出根因的
    // 「GIF 标签不见了」。空串按「没下发」处理。
    assertTrue(RemoteConfig.isGifAvailable(upstreamGifSearch = true, provider = ""))
  }
}
