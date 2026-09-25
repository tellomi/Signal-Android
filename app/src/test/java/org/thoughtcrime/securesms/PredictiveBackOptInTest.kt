/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory

/**
 * 预测性返回（tellomi/tellomi 交互审计 A-22）：应用级打开，任何页面都不许悄悄退出。
 *
 * 上游在 `<application>` 上写了 `enableOnBackInvokedCallback="false"` 整体退出，只给 MainActivity、
 * MediaSendV3Activity 单独打开；其余页面的返回都看不到「先露出上一页」。合上游新版本时这一行很容易被
 * 原样带回来，所以这里盯住它。确实要让某个页面退出时，写进 [INTENTIONAL_OPT_OUTS] 并写清理由。
 */
class PredictiveBackOptInTest {

  @Test
  fun application_opts_in_to_predictive_back() {
    val application = parse(APP_MANIFEST).getElementsByTagName("application").item(0) as Element

    assertThat(application.getAttributeNS(ANDROID_NS, ATTRIBUTE)).isEqualTo("true")
  }

  /** 进 App 合并清单的模块（app、core、feature、lib）里，没有哪个 Activity 自己写 "false" 退出。 */
  @Test
  fun no_activity_in_merged_modules_opts_out() {
    val manifests = mergedModuleManifests()
    val optedOut = manifests.flatMap { manifest ->
      val activities = parse(manifest).getElementsByTagName("activity")
      (0 until activities.length)
        .map { activities.item(it) as Element }
        .filter { it.getAttributeNS(ANDROID_NS, ATTRIBUTE) == "false" }
        .map { "${it.getAttributeNS(ANDROID_NS, "name")}（$manifest）" }
        .filterNot { entry -> INTENTIONAL_OPT_OUTS.any { entry.startsWith("$it（") } }
    }

    assertThat(manifests.size).isGreaterThan(1)
    assertThat(optedOut).isEmpty()
  }

  private fun parse(path: Path): Document {
    return DocumentBuilderFactory.newInstance()
      .apply { isNamespaceAware = true }
      .newDocumentBuilder()
      .parse(path.inputStream())
  }

  /** 用例的工作目录是 app 模块；core / feature / lib 下各模块的 `src/main/AndroidManifest.xml`。 */
  private fun mergedModuleManifests(): List<Path> {
    val modules = listOf(Path(".")) + MODULE_GROUPS.flatMap { group ->
      val dir = Path("..", group)
      if (dir.isDirectory()) Files.list(dir).use { stream -> stream.filter { it.isDirectory() }.toList() } else emptyList()
    }
    return modules.map { it.resolve("src/main/AndroidManifest.xml") }.filter { it.exists() }
  }

  companion object {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val ATTRIBUTE = "enableOnBackInvokedCallback"
    private val APP_MANIFEST = Path("src/main/AndroidManifest.xml")
    private val MODULE_GROUPS = listOf("core", "feature", "lib")

    /** 有意退出预测性返回的 Activity，按清单里 `android:name` 的写法，每一项都要带理由。现在没有。 */
    private val INTENTIONAL_OPT_OUTS: Set<String> = emptySet()
  }
}
