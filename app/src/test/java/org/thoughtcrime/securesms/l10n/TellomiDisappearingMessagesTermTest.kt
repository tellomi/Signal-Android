/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.l10n

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import java.util.Locale

/**
 * Tellomi：「消息到时间自动消失」这个功能两端统一叫「限时消息」，繁体和粤语叫「限時訊息」（owner 2026-09-26，两端差异清单 N3）。
 *
 * 上游中文把它叫成「阅后即焚」「自動銷毀訊息」「過眼雲煙訊息」，而「阅后即焚」在 Signal 中文里还指「一次性查看」，两个功能撞名。
 * 这里只管点名这个功能的字符串：英文原文讲 disappearing message 的，四种中文里都不许再出现旧称。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiDisappearingMessagesTermTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the feature is called 限时消息 in every chinese variant`() {
    val names = listOf(
      R.string.ConversationSettingsFragment__disappearing_messages,
      R.string.PrivacySettingsFragment__disappearing_messages,
      R.string.conversation_expiring_off__disappearing_messages,
      R.string.PinnedMessage__disappearing_message_content_description
    )

    for ((tag, expected) in FEATURE_NAME) {
      val resources = resourcesFor(tag)
      for (id in names) {
        assertEquals("$tag ${resources.getResourceEntryName(id)}", expected, resources.getString(id))
      }
    }
  }

  @Test
  fun `no string about disappearing messages uses an old name`() {
    val english = resourcesFor("en")
    val strings = R.string::class.java.fields.map { it.getInt(null) }
      .filter { english.getString(it).contains("disappearing message", ignoreCase = true) }
    val plurals = R.plurals::class.java.fields.map { it.getInt(null) }
      .filter { english.getQuantityText(it, 2).contains("disappearing message", ignoreCase = true) }

    val offenders = FEATURE_NAME.keys.flatMap { tag ->
      val resources = resourcesFor(tag)
      val texts = strings.map { resources.getResourceEntryName(it) to resources.getString(it) } +
        plurals.map { resources.getResourceEntryName(it) to resources.getQuantityText(it, 2).toString() }
      texts.filter { (_, text) -> OLD_NAMES.any { text.contains(it) } }.map { (name, text) -> "$tag $name: $text" }
    }

    assertEquals(emptyList<String>(), offenders)
  }

  private fun resourcesFor(languageTag: String): Resources {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(languageTag))
    return context.createConfigurationContext(configuration).resources
  }

  companion object {
    private val FEATURE_NAME = mapOf(
      "zh-CN" to "限时消息",
      "zh-HK" to "限時訊息",
      "zh-TW" to "限時訊息",
      "yue" to "限時訊息"
    )

    private val OLD_NAMES = listOf("阅后即焚", "閱後即焚", "自動銷毀", "訊息銷毀", "銷毀的訊息", "過眼雲煙")
  }
}
