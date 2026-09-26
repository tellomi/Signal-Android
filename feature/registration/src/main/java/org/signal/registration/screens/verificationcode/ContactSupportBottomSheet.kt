/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.verificationcode

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.signal.core.ui.compose.BottomSheets
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.dismissWithAnimation
import org.signal.core.util.LinkActions
import org.signal.registration.R
import org.signal.registration.TellomiRegistration
import org.signal.registration.test.TestTags

/**
 * Bottom sheet shown during registration when the user is having trouble entering their verification code. Offers
 * troubleshooting steps and a way to contact support, mirroring the old app-module ContactSupportBottomSheetFragment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSupportBottomSheet(
  e164: String,
  onChangeNumber: () -> Unit,
  onContactSupport: () -> Unit,
  onDismiss: () -> Unit
) {
  // Tellomi（tellomi/tellomi#1214）：内容比上游长，默认半展开时「改号码 / 联系客服」会落在折叠线下面，直接完全展开。
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val supportCenterUrl = stringResource(R.string.VerificationCodeScreen__support_center_url)

  BottomSheets.BottomSheet(
    onDismissRequest = { sheetState.dismissWithAnimation(scope, onComplete = onDismiss) },
    sheetState = sheetState
  ) {
    // Tellomi（tellomi/tellomi#1214）：上游是三条通用提示（信号、能不能接电话、号码对不对）+ 一句带两个链接的话。
    // Tellomi 的用户多在中国大陆：短信常被手机管家的骚扰拦截吞掉，每个注册会话又只有几条短信额度，所以换成能照做的几条，
    // 外加「改号码」「联系客服」两个按钮和支持页链接。
    TellomiDidntGetCodeContent(
      e164 = e164,
      onChangeNumber = {
        sheetState.dismissWithAnimation(scope) {
          onChangeNumber()
        }
      },
      onContactSupport = {
        sheetState.dismissWithAnimation(scope) {
          onDismiss()
          onContactSupport()
        }
      },
      onMoreHelp = {
        LinkActions.openUrl(context, supportCenterUrl) {
          Toast.makeText(context, R.string.LinkActions_error_no_browser_found, Toast.LENGTH_SHORT).show()
        }
      }
    )
  }
}

@Composable
private fun TellomiDidntGetCodeContent(
  e164: String,
  onChangeNumber: () -> Unit,
  onContactSupport: () -> Unit,
  onMoreHelp: () -> Unit
) {
  val bullets = buildList {
    add(stringResource(R.string.TellomiRegistration__help_check_number, e164))
    add(stringResource(R.string.TellomiRegistration__help_spam_filter))
    add(stringResource(R.string.TellomiRegistration__help_wait_with_quota, TellomiRegistration.SMS_VERIFICATION_CODES_PER_SESSION))
    if (TellomiRegistration.VOICE_VERIFICATION_AVAILABLE) {
      add(stringResource(R.string.TellomiRegistration__help_voice))
    }
    add(stringResource(R.string.TellomiRegistration__help_support))
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(16.dp)
  ) {
    Text(
      text = stringResource(R.string.TellomiRegistration__didnt_get_code),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier
        .align(Alignment.CenterHorizontally)
        .padding(8.dp)
    )
    bullets.forEach { bullet ->
      Text(
        text = "• $bullet",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
      )
    }
    Row(
      horizontalArrangement = Arrangement.SpaceEvenly,
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)
    ) {
      TextButton(
        onClick = onChangeNumber,
        modifier = Modifier.testTag(TestTags.VERIFICATION_CODE_HELP_CHANGE_NUMBER)
      ) {
        Text(stringResource(R.string.TellomiRegistration__help_change_number))
      }
      TextButton(
        onClick = onContactSupport,
        modifier = Modifier.testTag(TestTags.VERIFICATION_CODE_HELP_CONTACT_SUPPORT)
      ) {
        Text(stringResource(R.string.TellomiRegistration__help_contact_support))
      }
    }
    TextButton(
      onClick = onMoreHelp,
      modifier = Modifier.align(Alignment.CenterHorizontally)
    ) {
      Text(stringResource(R.string.TellomiRegistration__help_more))
    }
  }
}

@Composable
private fun ContactSupportBottomSheetContent(
  onTroubleshootingStepsClick: () -> Unit,
  onContactSupportClick: () -> Unit
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp)
  ) {
    Text(
      text = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
          append(stringResource(R.string.VerificationCodeScreen__support_bottom_sheet_title))
        }
      },
      modifier = Modifier.padding(8.dp)
    )
    Text(
      text = stringResource(R.string.VerificationCodeScreen__support_bottom_sheet_body_suggestions),
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(8.dp)
    )
    Text(
      text = buildCallToActionString(onTroubleshootingStepsClick, onContactSupportClick),
      modifier = Modifier.padding(8.dp)
    )
  }
}

@Composable
private fun buildCallToActionString(
  onTroubleshootingStepsClick: () -> Unit,
  onContactSupportClick: () -> Unit
) = buildAnnotatedString {
  val troubleshootingStepsString = stringResource(R.string.VerificationCodeScreen__support_bottom_sheet_cta_troubleshooting_steps_substring)
  val contactSupportString = stringResource(R.string.VerificationCodeScreen__support_bottom_sheet_cta_contact_support_substring)
  val completeString = stringResource(R.string.VerificationCodeScreen__support_bottom_sheet_body_call_to_action, troubleshootingStepsString, contactSupportString)

  val troubleshootingStartIndex = completeString.indexOf(troubleshootingStepsString)
  val troubleshootingEndIndex = troubleshootingStartIndex + troubleshootingStepsString.length
  val contactSupportStartIndex = completeString.indexOf(contactSupportString)
  val contactSupportEndIndex = contactSupportStartIndex + contactSupportString.length

  val bodyStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Normal)
  val linkStyles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold))

  withStyle(bodyStyle) {
    append(completeString.substring(0, troubleshootingStartIndex))
  }
  withLink(LinkAnnotation.Clickable(tag = "troubleshooting", styles = linkStyles) { onTroubleshootingStepsClick() }) {
    append(troubleshootingStepsString)
  }
  withStyle(bodyStyle) {
    append(completeString.substring(troubleshootingEndIndex, contactSupportStartIndex))
  }
  withLink(LinkAnnotation.Clickable(tag = "contact_support", styles = linkStyles) { onContactSupportClick() }) {
    append(contactSupportString)
  }
  withStyle(bodyStyle) {
    append(completeString.substring(contactSupportEndIndex))
  }
}

@Preview
@Composable
private fun TellomiDidntGetCodePreview() {
  Previews.BottomSheetPreview {
    TellomiDidntGetCodeContent(
      e164 = "+8613800138000",
      onChangeNumber = {},
      onContactSupport = {},
      onMoreHelp = {}
    )
  }
}

@Preview
@Composable
private fun ContactSupportBottomSheetPreview() {
  Previews.BottomSheetPreview {
    ContactSupportBottomSheetContent(
      onTroubleshootingStepsClick = {},
      onContactSupportClick = {}
    )
  }
}
