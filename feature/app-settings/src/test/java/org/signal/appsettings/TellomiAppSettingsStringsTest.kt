/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.inputStream

/**
 * Tellomi：设置模块里 Tellomi 自己加的文案（键名以 Tellomi 开头，例如「退出登录」ADR-0072）四种语言都要齐——en、简体、香港繁体、台湾繁体。
 * 缺了会回落英文，港台用户会在退出确认这种要逐字读懂的地方看到英文。与注册模块的 TellomiRegistrationStringsTest 同一条规矩。
 */
class TellomiAppSettingsStringsTest {

  @Test
  fun `every Tellomi string exists in simplified, Hong Kong and Taiwan Chinese`() {
    val english = tellomiKeys("values")
    assertThat(english).isNotEmpty()

    val missing = CHINESE.flatMap { locale -> (english - tellomiKeys(locale)).map { "$locale/$it" } }.sorted()
    assertThat(missing).isEmpty()
  }

  private fun tellomiKeys(dir: String): Set<String> {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path("src/main/res/$dir/strings.xml").inputStream())
    val nodes = document.getElementsByTagName("string")
    return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").textContent }.filter { it.startsWith("Tellomi") }.toSet()
  }

  companion object {
    private val CHINESE = listOf("values-zh-rCN", "values-zh-rHK", "values-zh-rTW")
  }
}
