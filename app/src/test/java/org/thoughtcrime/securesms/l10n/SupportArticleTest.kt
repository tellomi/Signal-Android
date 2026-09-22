package org.thoughtcrime.securesms.l10n

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import org.junit.Test
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.inputStream

class SupportArticleTest {
  /**
   * Tests that support articles found in strings.xml:
   * <p>
   * - Do not have a locale mentioned in the URL.
   * - Only have an article number, i.e. no trailing text.
   * - Are https.
   * - Are marked as translatable="false".
   */
  @Test
  fun ensure_format_and_translatable_state_of_all_support_article_urls() {
    val errors = mutableListOf<String>()
    var seen = 0

    val strings = DocumentBuilderFactory.newInstance()
      .newDocumentBuilder()
      .parse(MAIN_STRINGS_PATH.inputStream())
      .getElementsByTagName("string")

    for (i in 0 until strings.length) {
      val stringNode = strings.item(i)
      val string = stringNode.textContent
      val stringName = stringName(stringNode)

      if (SUPPORT_ARTICLE.matches(string)) {
        seen++

        if (!CORRECT_SUPPORT_ARTICLE.matches(string)) {
          errors.add("Article URL format is not correct [$stringName] URL: $string")
        }
        if (isTranslatable(stringNode)) {
          errors.add("Article string is translatable [$stringName], add translatable=\"false\"")
        }
      }
    }

    assertThat(seen).isGreaterThan(0)
    assertThat(errors).isEmpty()
  }

  private fun isTranslatable(item: Node): Boolean {
    val translatableAttribute = item.attributes.getNamedItem("translatable")
    return translatableAttribute == null || translatableAttribute.textContent != "false"
  }

  private fun stringName(item: Node): String {
    return item.attributes.getNamedItem("name").textContent
  }

  companion object {
    private val MAIN_STRINGS_PATH = Path("src/main/res/values/strings.xml")

    // Tellomi：上游这两条盯的是 support.signal.org/hc/articles/<id>。品牌清理之后
    // strings.xml 里一条都没有了（全改成 tellomi.app/help/<id>，形状相同只是换了域名），
    // 于是 seen 恒为 0，`assertThat(seen).isGreaterThan(0)` 必红 —— 这条用例**在所有机器上**
    // 都是红的，不像 locale 那类只在某些机器上红。
    //
    // 换成盯我们自己的链接，四条保证（不带 locale 段 / 只有文章号没有尾巴 / https /
    // translatable="false"）原样保留。松的那条故意比严的宽：它要能抓住写错的形式
    // （http、带语言段、文章号后面拖文字），再交给严的那条报错。
    // 末尾的 `.+` 是有意的：https://tellomi.app/help/ 是帮助**首页**（实测 200），
    // 不是文章，不该拿文章格式去判它。AdvancedPrivacySettingsFragment__sealed_sender_link
    // 就是这么一条——上游的 signal.org/blog/sealed-sender 我们没有对应文章，
    // rename-strings.py 把它映射到帮助首页（脚本第 55 行）。
    private val SUPPORT_ARTICLE = Regex(".*://[a-z0-9.-]*tellomi\\.app/help/.+")
    private val CORRECT_SUPPORT_ARTICLE = Regex("https://tellomi\\.app/help/\\d+(#[a-z_]+)?")
  }
}
