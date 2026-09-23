/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.shared

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import org.signal.core.ui.compose.Dialogs
import org.signal.core.util.LinkActions
import org.signal.core.util.LinkActions.OpenUrlError
import org.signal.registration.R

/**
 * Tellomi：注册同意（tellomi/tellomi#1211；ADR-0038 · ADR-0051 §E）。iOS 的同一套在 Signal-iOS#13。
 *
 * - 号码页的协议行**默认不勾**；没勾就要发号码 → 二次确认「同意并继续 / 不同意」：同意 = 勾选框看得见地打勾后再继续，
 *   不同意 = 什么都不发生。**客户端绝不静默替用户打勾。**
 * - 第一次打开 App 先弹一次隐私提示（2019《App 违法违规收集使用个人信息行为认定方法》：首次运行要用弹窗等明显方式提示隐私政策）。
 * - 只记在本机（文档版本 + 时间）；服务端留存与跨境告知（tellomi/tellomi#1133）一起设计。
 */
object TellomiLegalConsent {
  const val TERMS_URL = "https://tellomi.app/legal/terms/"
  const val PRIVACY_URL = "https://tellomi.app/legal/privacy/"

  /** 《用户服务协议》《隐私政策》的版本（1.0.0，2026-09-11 生效）。文本改版时改这里：记下的版本对不上，就会重新要求同意。 */
  const val DOCUMENTS_VERSION = "1.0.0"

  const val CHECKBOX_TEST_TAG = "tellomi-consent-checkbox"

  private const val PREFS_NAME = "tellomi_legal_consent"
  private const val TERMS_VERSION_KEY = "terms_and_privacy.version"
  private const val TERMS_DATE_KEY = "terms_and_privacy.date"
  private const val NOTICE_VERSION_KEY = "first_launch_notice.version"

  fun hasAgreedToTerms(context: Context): Boolean {
    return prefs(context).getString(TERMS_VERSION_KEY, null) == DOCUMENTS_VERSION
  }

  /** 勾上 = 记下同意（文档版本 + 时间）；取消勾 = 撤回。 */
  fun setAgreedToTerms(context: Context, agreed: Boolean) {
    prefs(context).edit {
      if (agreed) {
        putString(TERMS_VERSION_KEY, DOCUMENTS_VERSION)
        putLong(TERMS_DATE_KEY, System.currentTimeMillis())
      } else {
        remove(TERMS_VERSION_KEY)
        remove(TERMS_DATE_KEY)
      }
    }
  }

  fun hasAcceptedFirstLaunchNotice(context: Context): Boolean {
    return prefs(context).getString(NOTICE_VERSION_KEY, null) == DOCUMENTS_VERSION
  }

  fun acceptFirstLaunchNotice(context: Context) {
    prefs(context).edit { putString(NOTICE_VERSION_KEY, DOCUMENTS_VERSION) }
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 「☐ 我已年满 18 周岁，已阅读并同意《用户服务协议》和《隐私政策》」。只有勾选框切换勾选；两个书名号是链接。
 */
@Composable
fun TellomiConsentRow(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier
) {
  val checkboxDescription = stringResource(R.string.TellomiConsent__checkbox_content_description)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
  ) {
    Checkbox(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier
        .testTag(TellomiLegalConsent.CHECKBOX_TEST_TAG)
        .semantics { contentDescription = checkboxDescription }
    )

    Text(
      text = linkedSentence(R.string.TellomiConsent__row, listOf(termsLink(), privacyLink())),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

/**
 * 没勾就要发号码时的二次确认（ADR-0038 §3.1 原句）。「不同意」、返回键、点外面都 = 什么都不发生。
 */
@Composable
fun TellomiTermsConsentDialog(
  onAgree: () -> Unit,
  onDisagree: () -> Unit
) {
  Dialogs.SimpleAlertDialog(
    title = AnnotatedString(stringResource(R.string.TellomiConsent__dialog_title)),
    body = linkedSentence(R.string.TellomiConsent__dialog_body, listOf(termsLink(), privacyLink())),
    confirm = AnnotatedString(stringResource(R.string.TellomiConsent__agree_and_continue)),
    dismiss = AnnotatedString(stringResource(R.string.TellomiConsent__disagree)),
    onConfirm = onAgree,
    onDeny = onDisagree,
    onDismissRequest = onDisagree
  )
}

/**
 * 第一次打开 App 时的隐私提示。「不同意」不关闭——说明后果、留在原地；返回键和点外面也关不掉。
 */
@Composable
fun TellomiFirstLaunchNotice() {
  val context = LocalContext.current
  var visible by remember { mutableStateOf(!TellomiLegalConsent.hasAcceptedFirstLaunchNotice(context)) }
  var showDisagreeHint by rememberSaveable { mutableStateOf(false) }

  if (visible) {
    val body = linkedSentence(R.string.TellomiConsent__first_launch_body, listOf(privacyLink()))
    val hint = stringResource(R.string.TellomiConsent__first_launch_disagree_hint)

    Dialogs.SimpleAlertDialog(
      title = AnnotatedString(stringResource(R.string.TellomiConsent__first_launch_title)),
      body = if (showDisagreeHint) body + AnnotatedString("\n\n$hint") else body,
      confirm = AnnotatedString(stringResource(R.string.TellomiConsent__first_launch_agree)),
      dismiss = AnnotatedString(stringResource(R.string.TellomiConsent__disagree)),
      onConfirm = {
        TellomiLegalConsent.acceptFirstLaunchNotice(context)
        visible = false
      },
      onDeny = { showDisagreeHint = true },
      onDismissRequest = {},
      properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    )
  }
}

private data class ConsentLink(val text: String, val url: String)

@Composable
private fun termsLink() = ConsentLink(stringResource(R.string.TellomiConsent__terms_link), TellomiLegalConsent.TERMS_URL)

@Composable
private fun privacyLink() = ConsentLink(stringResource(R.string.TellomiConsent__privacy_link), TellomiLegalConsent.PRIVACY_URL)

/**
 * 把格式串里的 `%1$s`、`%2$s` 依次换成可点的文档名。点了用浏览器打开官网上的全文（ADR-0038：App 只做确认入口，不内嵌全文）。
 */
@Composable
private fun linkedSentence(@StringRes format: Int, links: List<ConsentLink>): AnnotatedString {
  val context = LocalContext.current
  val sentence = stringResource(format, *links.map { it.text }.toTypedArray())
  val linkStyles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary))

  return buildAnnotatedString {
    append(sentence)
    links.forEach { link ->
      val start = sentence.indexOf(link.text)
      if (start >= 0) {
        addLink(LinkAnnotation.Clickable(tag = link.url, styles = linkStyles) { openUrl(context, link.url) }, start, start + link.text.length)
      }
    }
  }
}

private fun openUrl(context: Context, url: String) {
  LinkActions.openUrl(context, url) { error ->
    when (error) {
      OpenUrlError.NoBrowserFound -> Toast.makeText(context, R.string.LinkActions_error_no_browser_found, Toast.LENGTH_SHORT).show()
    }
  }
}
