/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.help

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.navigation.safeNavigate

class HelpSettingsFragment : ComposeFragment() {

  @Composable
  override fun FragmentContent() {
    val navController: NavController = remember { findNavController() }

    val context = LocalContext.current

    Scaffolds.Settings(
      title = stringResource(R.string.preferences__help),
      onNavigationClick = { navController.popBackStack() },
      navigationIcon = SignalIcons.ArrowStart.imageVector,
      navigationContentDescription = stringResource(id = R.string.Material3SearchToolbar__close)
    ) { contentPadding ->
      LazyColumn(
        modifier = Modifier.padding(contentPadding)
      ) {
        item {
          Rows.LinkRow(
            text = stringResource(R.string.HelpSettingsFragment__support_center),
            icon = ImageVector.vectorResource(R.drawable.symbol_open_20),
            onClick = {
              CommunicationActions.openBrowserLink(context, getString(R.string.support_center_url))
            }
          )
        }

        item {
          Rows.TextRow(
            text = stringResource(id = R.string.HelpSettingsFragment__contact_us),
            onClick = {
              navController.safeNavigate(R.id.action_helpSettingsFragment_to_helpFragment)
            }
          )
        }

        item {
          Dividers.Default()
        }

        item {
          Rows.TextRow(
            text = stringResource(id = R.string.HelpSettingsFragment__debug_log),
            onClick = {
              navController.safeNavigate(R.id.action_helpSettingsFragment_to_submitDebugLogActivity)
            }
          )
        }

        // Tellomi（tellomi/tellomi#1165）：版本、许可证、条款与隐私政策、页脚三行署名都搬到「关于 Tellomi」（与「帮助」平级），
        // 这里只留支持中心、联系我们、调试日志。
      }
    }
  }
}
