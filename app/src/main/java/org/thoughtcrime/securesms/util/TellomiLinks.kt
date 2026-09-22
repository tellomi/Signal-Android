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

  /**
   * 短域名。**一个域名承载全部链接类型，靠第一段路径区分**（owner 2026-09-22：
   * tell.cc 以后不只是用户名地址，群邀请 / 贴纸 / 通话链接 / 邀请链接都要走它）。
   *
   * 路径命名空间 —— **新增用途一律在这里登记**，不要随手占用：
   *
   * | 路径 | 用途 | 状态 |
   * | --- | --- | --- |
   * | `/u` | 找人：`#p/<E164>`、`#eu/<加密用户名链接>`、`#u/<明文用户名>` | 已用 |
   * | `/g` | 群邀请：`#<invite>` | 已用 |
   * | `/s` | 贴纸包：`#pack_id=…&pack_key=…` | 已用 |
   * | `/call` | 通话链接：`#key=…` | 已用（通话本身是阶段三） |
   * | `/i` | **邀请下载**（邀请朋友装 Tellomi） | **预留，未实现** |
   * | `/` | 不用 —— R2 自定义域对根路径只会 404（2026-09-22 上线时踩到） |
   *
   * 秘密一律放 `#` 后面，不进服务器日志 —— 沿用 Signal 的形状，别改。
   *
   * 每加一种，要同时动三处，缺一不可：
   * 1. 这张表 + 对应常量；
   * 2. `AndroidManifest.xml` 的 intent-filter（**每种单开一个 filter**，见文件末尾的说明）；
   * 3. 落地页 `deploy/tellcc/site/`（没装 App 的人要有地方落）。
   * 只加 1 不加 2，链接点了没反应；只加 2 不加 3，没装 App 的人看到 404。
   */
  const val HOST = "tell.cc"

  const val PATH_CONTACT = "/u"
  const val PATH_GROUP = "/g"
  const val PATH_STICKER = "/s"
  const val PATH_CALL = "/call"

  /** 预留：邀请朋友下载。还没有处理逻辑，所以 manifest 里**故意没有**对应的 intent-filter
   *  —— 声明了却不处理的话，用户点链接会打开 App 然后什么也不发生，比直接落网页更糟。 */
  const val PATH_INVITE_RESERVED = "/i"

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
   * 明文用户名链接里的用户名，没有就返回 null。**两种写法都认**：
   *
   * 1. `https://tell.cc/ceshi.57` —— **用户看到、会去分享的就是这一种**（t.me 式，
   *    owner 2026-09-22 定）。用户名带判别数字，所以一定含 `.`。
   * 2. `tellomi://tell.cc/u#u/ceshi.57` —— 落地页把 1 改写成的内部形状。
   *
   * 两种都要认：装了 App 的人点 1 应当**直接**进 App（不闪一下浏览器），
   * 没装的人才走落地页；而落地页唤起 App 时用的是 2。只认 2 的话，
   * 第一种链接永远要绕一次浏览器。
   *
   * 上游没有这种明文形状：Signal 的 `#eu/` 是加密块。
   *
   * 形状收得很紧（`^/[A-Za-z0-9_]+\.[0-9]+$`），原因有两条：
   * - 排除保留路径 `/u` `/g` `/s` `/call`（它们不含 `.`）；
   * - 排除 `/.well-known/assetlinks.json` —— 它含 `.`，但以 `.` 开头且带 `/`。
   */
  @JvmStatic
  fun parsePlainUsernameFromLink(link: String?): String? {
    if (link == null) return null
    PLAIN_USERNAME_FRAGMENT_REGEX.find(link)?.let { return it.groups[2]?.value }
    return PLAIN_USERNAME_PATH_REGEX.find(link)?.groups?.get(2)?.value
  }

  /** 内部形状：`tellomi://tell.cc/u#u/<username>`（落地页唤起 App 用的） */
  private val PLAIN_USERNAME_FRAGMENT_REGEX =
    """^(https://|tellomi://)tell\.cc/u/?#u/([A-Za-z0-9_]+\.[0-9]+)$""".toRegex()

  /** 用户看到的形状：`https://tell.cc/<username>` */
  private val PLAIN_USERNAME_PATH_REGEX =
    """^(https://|tellomi://)tell\.cc/([A-Za-z0-9_]+\.[0-9]+)/?$""".toRegex()

  // 这里**故意不提供** `isGroupHost(host)` 之类只看 host 的便利方法：
  // tell.cc 一个域名承载 /u /g /s /call 四种用途，只判 host 会把联系人链接
  // 也放进群邀请的解析里（然后 Base64 解不动，报「群链接无效」，与真实原因无关）。
  // 各调用点自己连路径一起判，见 GroupInviteLinkUrl.getGroupUrl。
}
