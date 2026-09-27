/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.restoreselection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Buttons
import org.signal.registration.R
import org.signal.registration.screens.OnePaneRegistrationScaffold
import org.signal.registration.screens.RegistrationScaffold
import org.signal.registration.screens.TwoPaneRegistrationScaffold
import org.signal.registration.screens.attachDebugLogHelper

object TellomiNoTransferFromIphoneTestTags {
  const val SCREEN = "tellomi_no_transfer_from_iphone_screen"
  const val REGISTER_DIRECTLY = "tellomi_no_transfer_from_iphone_register_directly"
  const val BACK = "tellomi_no_transfer_from_iphone_back"
}

/**
 * Tellomi（tellomi/tellomi#1216 跟进，owner 2026-09-24 定）：扫码扫到的旧手机是 iPhone、又没有备份服务时的说明页。
 *
 * 上游这时进的是恢复方式页，列「恢复本地备份 / 跳过」，iPhone 的聊天记录两样都用不上。和 iOS「旧手机是 Android」那页
 * （`RegistrationChooseRestoreMethodViewController.addTellomiNoTransferFromAndroidViews`）对齐：说清传不过来和后果，
 * 给「直接注册 / 返回」。按钮放在底部，和注册其它页一样整宽。
 */
@Composable
internal fun TellomiNoTransferFromIphone(
  onEvent: (ArchiveRestoreSelectionScreenEvents) -> Unit,
  modifier: Modifier = Modifier
) {
  // 系统返回键和「返回」一样回注册根页：退回扫码页没有用，码已经扫过了。
  BackHandler { onEvent(ArchiveRestoreSelectionScreenEvents.TellomiBack) }

  val registerDirectly: @Composable (Modifier) -> Unit = { buttonModifier ->
    Buttons.LargeTonal(
      onClick = { onEvent(ArchiveRestoreSelectionScreenEvents.TellomiRegisterDirectly) },
      modifier = buttonModifier.testTag(TellomiNoTransferFromIphoneTestTags.REGISTER_DIRECTLY)
    ) {
      Text(text = stringResource(R.string.TellomiRegistration__register_directly))
    }
  }
  val back: @Composable (Modifier) -> Unit = { buttonModifier ->
    TextButton(
      onClick = { onEvent(ArchiveRestoreSelectionScreenEvents.TellomiBack) },
      modifier = buttonModifier.testTag(TellomiNoTransferFromIphoneTestTags.BACK)
    ) {
      Text(text = stringResource(R.string.RegistrationScreen__back))
    }
  }

  when (val params = RegistrationScaffold.rememberLayoutParams()) {
    is RegistrationScaffold.Params.OnePane -> {
      val scrollState = rememberScrollState()
      OnePaneRegistrationScaffold(
        modifier = modifier
          .fillMaxSize()
          .testTag(TellomiNoTransferFromIphoneTestTags.SCREEN),
        params = params,
        content = { paddingValues ->
          Column(
            modifier = Modifier
              .fillMaxSize()
              .verticalScroll(scrollState)
              .padding(paddingValues)
          ) {
            Explanation(twoPane = false)
          }
        },
        footer = {
          RegistrationScaffold.FooterSurface(isElevated = scrollState.canScrollForward) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(params.footerPadding),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              registerDirectly(Modifier.fillMaxWidth())
              Spacer(modifier = Modifier.height(8.dp))
              back(Modifier.fillMaxWidth())
            }
          }
        }
      )
    }

    is RegistrationScaffold.Params.TwoPane -> {
      val scrollState = rememberScrollState()
      TwoPaneRegistrationScaffold(
        modifier = modifier
          .fillMaxSize()
          .testTag(TellomiNoTransferFromIphoneTestTags.SCREEN),
        params = params,
        firstPane = { paddingValues ->
          Column(
            modifier = Modifier
              .weight(1f)
              .verticalScroll(scrollState)
              .padding(paddingValues)
          ) {
            Explanation(twoPane = true)
          }
        },
        secondPane = {},
        footer = {
          RegistrationScaffold.FooterSurface(isElevated = scrollState.canScrollForward) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(params.footerPadding),
              horizontalArrangement = Arrangement.End,
              verticalAlignment = Alignment.CenterVertically
            ) {
              back(Modifier)
              Spacer(modifier = Modifier.width(16.dp))
              registerDirectly(Modifier)
            }
          }
        }
      )
    }
  }
}

@Composable
private fun Explanation(twoPane: Boolean) {
  Text(
    text = stringResource(R.string.TellomiRegistration__no_transfer_from_iphone_title),
    style = if (twoPane) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
    modifier = Modifier
      .fillMaxWidth()
      .attachDebugLogHelper()
  )

  Text(
    text = stringResource(R.string.TellomiRegistration__no_transfer_from_iphone_body),
    style = if (twoPane) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal) else MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 16.dp)
  )
}
