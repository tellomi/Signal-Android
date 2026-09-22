/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

/**
 * Tellomi 的链接 scheme / 域名，以及「同时接受新旧两种形状」的判定。
 *
 * 决定与完整映射见超级仓库 `docs/signal/LINKS_AND_SCHEMES.md`：
 *   `sgnl://` → `tellomi://`，`signalcaptcha://` → `tellomicaptcha://`，
 *   `signal.me|.group|.art|.link` → `tell.cc/u|g|s|call`。
 *
 * **分两阶段，现在是第一阶段**（owner / taishi 2026-09-22 05:00 改的策略）：
 * 1. **接受**：三端各自独立上线「新旧都认」，顺序无所谓 —— 就是这个文件。
 * 2. **发出**：等三端都到 1 之后，再把「生成链接」翻到新形状。
 *    所以本文件只提供判定与解析，**生成**那一侧暂时仍用上游的旧形状，
 *    翻的时候改各自的 `*_PREFIX` / `BASE_URL` 即可。
 *
 * 为什么集中在一处：这些字面量原本散在十几个文件里（SignalMeUtil / GroupInviteLinkUrl /
 * StickerUrl / CallLinks / LinkDeviceRepository / CommunicationActions / …），
 * 每处各写一遍 `||` 很容易漏掉一处，而漏掉的那处的症状是「某一类链接打不开」，
 * 与根因毫无关联 —— 和我们在常量漂移上踩过的两次是同一形状。
 */
object TellomiLinks {
  const val SCHEME = "tellomi"
  const val LEGACY_SCHEME = "sgnl"

  const val CAPTCHA_SCHEME = "tellomicaptcha://"
  const val LEGACY_CAPTCHA_SCHEME = "signalcaptcha://"

  /** 短域名。按路径区分用途：`/u` 找人、`/g` 群邀请、`/s` 贴纸、`/call` 通话链接。 */
  const val HOST = "tell.cc"

  const val LEGACY_HOST_CONTACT = "signal.me"
  const val LEGACY_HOST_GROUP = "signal.group"
  const val LEGACY_HOST_STICKER = "signal.art"
  const val LEGACY_HOST_CALL = "signal.link"

  /** 自定义 scheme：新旧都认。 */
  @JvmStatic
  fun isAppScheme(scheme: String?): Boolean =
    SCHEME.equals(scheme, ignoreCase = true) || LEGACY_SCHEME.equals(scheme, ignoreCase = true)

  /** captcha 回跳：新旧都认。 */
  @JvmStatic
  fun isCaptchaUrl(url: String?): Boolean =
    url != null && (url.startsWith(CAPTCHA_SCHEME) || url.startsWith(LEGACY_CAPTCHA_SCHEME))

  /**
   * 去掉 captcha 回跳的 scheme 前缀，拿到 token。
   * 两种前缀长度不同（`tellomicaptcha://` 17 / `signalcaptcha://` 15），
   * 所以必须按实际匹配到的那个前缀截。上游用的是 `substring(常量.length)`，
   * 本来就没写死数字；但那只对**单一**前缀成立，接受两种之后就得在这里分支。
   */
  @JvmStatic
  fun stripCaptchaScheme(url: String): String = when {
    url.startsWith(CAPTCHA_SCHEME) -> url.substring(CAPTCHA_SCHEME.length)
    url.startsWith(LEGACY_CAPTCHA_SCHEME) -> url.substring(LEGACY_CAPTCHA_SCHEME.length)
    else -> url
  }

  /**
   * `tell.cc/u#u/<username>` 里的明文用户名，没有就返回 null。
   *
   * 这是 Tellomi 新增的一种（t.me 式），上游没有：Signal 的 `#eu/` 是加密块。
   * 落地页把 `tell.cc/<username>` 改写成这个形状，App 自己拿用户名去问服务端要 ACI。
   * 只允许 `a-z 0-9 _ .`（用户名字符集 + 判别数字前的那个点），避免把别的片段误当用户名。
   */
  @JvmStatic
  fun parsePlainUsernameFromLink(link: String?): String? {
    if (link == null) return null
    return PLAIN_USERNAME_REGEX.find(link)?.groups?.get(2)?.value
  }

  private val PLAIN_USERNAME_REGEX =
    """(https://|tellomi://)?tell\.cc/u/?#u/([a-zA-Z0-9_.]+)$""".toRegex()

  // 这里**故意不提供** `isGroupHost(host)` 之类只看 host 的便利方法：
  // tell.cc 一个域名承载 /u /g /s /call 四种用途，只判 host 会把联系人链接
  // 也放进群邀请的解析里（然后 Base64 解不动，报「群链接无效」，与真实原因无关）。
  // 各调用点自己连路径一起判，见 GroupInviteLinkUrl.getGroupUrl。
}
