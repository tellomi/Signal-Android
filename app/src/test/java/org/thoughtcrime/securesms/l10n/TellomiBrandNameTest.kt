/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.l10n

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import org.junit.Test
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries

/**
 * Tellomi：上游有些语言把品牌写成「音译 + 拉丁字母 Signal」两半（泰米尔语「சிக்னல் Signal」、旁遮普语「Signal ਸਿਗਨਲ」），
 * 品牌替换（超级仓库 scripts/brand/rename-strings.py）两半都换成 Tellomi 后就叠成「Tellomi Tellomi」。
 * 上游自己也有叠着写的（卡纳达语注册锁那句本来就是「Signal Signal」）。
 * 任何语言的文案（含复数、数组里的条目）里品牌名都不该连写两遍，中间隔着空白 / 不换行空格 / 零宽字符也算。
 * 扫 app 和 feature 各模块每个 values 目录下的 strings.xml（用例的工作目录是 app 模块）。
 */
class TellomiBrandNameTest {

  @Test
  fun `no string repeats the brand name back to back`() {
    val errors = mutableListOf<String>()
    var files = 0

    val resRoots = listOf(Path("src/main/res")) + Path("../feature").listDirectoryEntries().map { it.resolve("src/main/res") }.filter { it.exists() }
    for (dir in resRoots.flatMap { it.listDirectoryEntries("values*") }.sortedBy { it.toString() }) {
      val file = dir.resolve("strings.xml")
      if (!file.exists()) continue
      files++

      val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.inputStream())
      for (tag in listOf("string", "item")) {
        val nodes = document.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
          val node = nodes.item(i)
          if (DOUBLED_BRAND.containsMatchIn(node.textContent)) {
            errors += "$dir ${nameOf(node)}: ${node.textContent}"
          }
        }
      }
    }

    assertThat(files).isGreaterThan(1)
    assertThat(errors).isEmpty()
  }

  private fun nameOf(node: Node): String {
    return node.attributes.getNamedItem("name")?.textContent ?: node.parentNode?.attributes?.getNamedItem("name")?.textContent ?: "?"
  }

  companion object {
    private val DOUBLED_BRAND = Regex("Tellomi[\\s\\u00A0\\u200B-\\u200D]*Tellomi")
  }
}
