/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.recipients.ui.RecipientPicker
import org.thoughtcrime.securesms.recipients.ui.RecipientPickerCallbacks

/**
 * Tellomi（tellomi/tellomi#1108）：联系人一级 Tab。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiContactsTabTest {

  private val context: Application
    get() = ApplicationProvider.getApplicationContext()

  @Test
  fun `the tabs are calls, chats, contacts, stories, and the app still opens on chats`() {
    assertThat(MainNavigationBarState.ALL_DESTINATIONS).containsExactly(MainListRoute.Calls, MainListRoute.Chats, MainListRoute.Contacts, MainListRoute.Stories)
    assertThat(MainNavigationBarState().destinations).containsExactly(MainListRoute.Calls, MainListRoute.Chats, MainListRoute.Contacts, MainListRoute.Stories)
    assertThat(MainNavigationBarState().currentListLocation).isEqualTo(MainListRoute.Chats)
    assertThat(MainListRoute.Contacts.tab).isEqualTo(MainListRoute.Contacts)
    assertThat(MainListRoute.Contacts.isChatsTab).isFalse()
  }

  @Test
  fun `the list only has people, and never asks for the contacts permission`() {
    assertThat(TellomiContactsTab.DISPLAY_MODES).isEqualTo(setOf(RecipientPicker.DisplayMode.PUSH))

    val callbacks = TellomiContactsTab.callbacks(RecipientPickerCallbacks.ListActions.Empty)
    assertThat(callbacks.newConversation).isNull()
    assertThat(callbacks.findByUsername).isNull()
    assertThat(callbacks.findByPhoneNumber).isNull()
    assertThat(callbacks.refresh).isNull()
    assertThat(callbacks.newCall).isNull()
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `the tab is called 联系人`() {
    assertThat(context.getString(MainListRoute.Contacts.label)).isEqualTo("联系人")
  }

  @Test
  fun `the tab is called Contacts in english`() {
    assertThat(context.getString(MainListRoute.Contacts.label)).isEqualTo("Contacts")
  }

  @Test
  fun `the icon animates like the other tabs, from an outline to a filled person`() {
    val contacts = raw(MainListRoute.Contacts.icon)
    val calls = raw(MainListRoute.Calls.icon)
    for (key in listOf("w", "h", "fr", "op")) {
      assertThat(contacts.get(key)).isEqualTo(calls.get(key))
    }
    val layers = contacts.getJSONArray("layers")
    assertThat((0 until layers.length()).map { layers.getJSONObject(it).getString("nm") }).containsExactly("person-fill", "person")
    assertThat(layers.getJSONObject(1).getInt("op")).isEqualTo(1)
    assertThat(layers.getJSONObject(0).getInt("ip")).isEqualTo(1)
  }

  private fun raw(id: Int): JSONObject {
    return context.resources.openRawResource(id).bufferedReader().use { JSONObject(it.readText()) }
  }
}
