/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.main.UsernameQrScannerActivity
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.ui.RecipientPicker
import org.thoughtcrime.securesms.recipients.ui.RecipientPickerCallbacks
import org.thoughtcrime.securesms.recipients.ui.RecipientSelection
import org.thoughtcrime.securesms.recipients.ui.findby.FindByActivity
import org.thoughtcrime.securesms.recipients.ui.findby.FindByMode
import org.signal.core.ui.R as CoreUiR

/**
 * Tellomi：联系人一级 Tab 的列表（tellomi/tellomi#1108）。
 *
 * 列表 = 已经建立联系的人（Signal connections：已注册，且是系统联系人或已互相共享资料），复用「新建聊天」的选人列表，只列个人、不列群；
 * 顶部三行：按用户名查找 · 扫描二维码 · 邀请好友；点一个人打开和他的会话。
 *
 * 不申请通讯录权限：选人列表只在给了「新建聊天」或「找人」回调时才出「查找联系人」横幅（点了才申请权限），这里一个都不传；
 * 通讯录匹配要 CDSI（#1111），不在这一刀。
 */
@Composable
fun TellomiContactsListPane(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var searchQuery by rememberSaveable { mutableStateOf("") }

  val openConversation: (RecipientId) -> Unit = remember(context) {
    { recipientId -> coroutineScope.launch { TellomiContactsTab.openConversation(context, recipientId) } }
  }

  val findByUsernameLauncher = rememberLauncherForActivityResult(FindByActivity.Contract()) { recipientId ->
    recipientId?.let(openConversation)
  }
  val qrScanLauncher = rememberLauncherForActivityResult(UsernameQrScannerActivity.Contract()) { recipientId ->
    recipientId?.let(openConversation)
  }

  val listActions = remember(openConversation) {
    object : RecipientPickerCallbacks.ListActions {
      override fun onSearchQueryChanged(query: String) {
        searchQuery = query
      }

      override suspend fun shouldAllowSelection(selection: RecipientSelection): Boolean = true

      override fun onRecipientSelected(selection: RecipientSelection) {
        (selection as? RecipientSelection.HasId)?.let { openConversation(it.id) }
      }
    }
  }

  RecipientPicker(
    searchQuery = searchQuery,
    displayModes = TellomiContactsTab.DISPLAY_MODES,
    includeRecents = false,
    isRefreshing = false,
    focusAndShowKeyboard = false,
    callbacks = remember(listActions) { TellomiContactsTab.callbacks(listActions) },
    tellomiHeader = if (searchQuery.isBlank()) {
      {
        ContactsHeaderRows(
          onFindByUsername = { findByUsernameLauncher.launch(FindByMode.USERNAME) },
          onScanQrCode = { qrScanLauncher.launch(Unit) },
          onInviteFriends = { context.startActivity(AppSettingsActivity.invite(context)) }
        )
      }
    } else {
      null
    },
    modifier = modifier
  )
}

@Composable
private fun ContactsHeaderRows(
  onFindByUsername: () -> Unit,
  onScanQrCode: () -> Unit,
  onInviteFriends: () -> Unit
) {
  Column {
    Rows.TextRow(
      text = stringResource(R.string.ContactSelectionListFragment__find_by_username),
      icon = painterResource(CoreUiR.drawable.symbol_at_24),
      onClick = onFindByUsername
    )
    Rows.TextRow(
      text = stringResource(R.string.FindByActivity__qr_scan_button),
      icon = SignalIcons.QrCode.painter,
      onClick = onScanQrCode
    )
    Rows.TextRow(
      text = stringResource(R.string.AppSettingsFragment__invite_your_friends),
      icon = painterResource(R.drawable.symbol_invite_24),
      onClick = onInviteFriends
    )
  }
}

object TellomiContactsTab {

  /** 只列个人（已注册的联系人），不列群、不列自己。 */
  val DISPLAY_MODES: Set<RecipientPicker.DisplayMode> = setOf(RecipientPicker.DisplayMode.PUSH)

  /**
   * 只给列表操作的回调。没有「新建聊天 / 找人 / 刷新 / 通话」回调，选人列表就不会出「查找联系人」横幅和「刷新联系人」行，
   * 也就不会申请通讯录权限（#1108 判据：没有 CDSI 的构建里不出现通讯录权限弹窗）。按用户名找人、扫码在顶部自己的行里。
   */
  fun callbacks(listActions: RecipientPickerCallbacks.ListActions): RecipientPickerCallbacks = RecipientPickerCallbacks(listActions = listActions)

  suspend fun openConversation(context: Context, recipientId: RecipientId) {
    val intent = ConversationIntents.createBuilder(context, recipientId, -1L).await().build()
    context.startActivity(intent)
  }
}
