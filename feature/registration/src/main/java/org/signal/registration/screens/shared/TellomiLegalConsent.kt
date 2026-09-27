/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.shared

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import org.signal.core.ui.compose.Buttons
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
  // Tellomi（tellomi/tellomi#1338）：官网在 www 上，不带 www 的会 301 过去；需求 6.3 写的就是 www 的地址。
  const val TERMS_URL = "https://www.tellomi.app/legal/terms/"
  const val PRIVACY_URL = "https://www.tellomi.app/legal/privacy/"
  const val THIRD_PARTY_URL = "https://www.tellomi.app/legal/third-party/"

  /**
   * 《用户服务协议》《隐私政策》的版本。文本改版时改这里：记下的版本对不上，就会重新要求同意（首次启动提示、号码页的勾选）。
   * Tellomi（tellomi/tellomi#1338）：1.0.0 → 2.0.0（需求 6.1 ①）。已注册的设备不走这两处，它们在升级盖页上看到「隐私政策已更新」（需求 6.2 c）。
   */
  const val DOCUMENTS_VERSION = "2.0.0"

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

  internal fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Tellomi：跨境单独告知与同意（tellomi/tellomi#1133；需求 `docs/product/specs/privacy-compliance-hk-cross-border.md` 第二节）。
 * 服务端还在香港的这段时间，手机号、推送令牌、网络信息等会出境，这一页就是出境前的单独告知与单独同意。iOS 同一套在 Signal-iOS#15。
 * Tellomi（tellomi/tellomi#1338）：文字 2026-09-27 定稿（需求第六节 6.3，对齐隐私政策 2.0.0 第 21.2–21.6 节）。
 */
object TellomiCrossBorderConsent {
  /**
   * 告知文本的版本。文本有实质变化就改这里，已同意的人会被重新询问。
   * Tellomi（tellomi/tellomi#1338）：定稿为独立编号 `cb-1`，不跟隐私政策的版本走（隐私政策 2.0.0 第 21.7 节：只有实质变化才重新征得同意）；
   * `docs/legal/manifest.json` 的 `notices` 里登记 `cross-border` = `cb-1` ↔ 隐私政策 `2.0.0`，改版时两边一起改。
   * 同意过草稿 `0.1.0-draft` 的设备升级后会再出这一页（需求 6.1 ①）。
   */
  const val NOTICE_VERSION = "cb-1"

  const val AGREE_TEST_TAG = "tellomi-cross-border-agree"
  const val DISAGREE_TEST_TAG = "tellomi-cross-border-disagree"
  const val ACK_TEST_TAG = "tellomi-cross-border-ack"

  private const val VERSION_KEY = "cross_border.version"
  private const val DATE_KEY = "cross_border.date"

  /**
   * 同意之后要重新放开网络（应用层的 TellomiCrossBorderNetworkGate 负责：重建连接、唤醒等网络的任务）。
   * 注册模块碰不到应用层的依赖，所以由应用在启动时挂上这个回调。
   */
  @Volatile
  var onAgreed: (() -> Unit)? = null

  fun hasAgreed(context: Context): Boolean {
    return TellomiLegalConsent.prefs(context).getString(VERSION_KEY, null) == NOTICE_VERSION
  }

  /**
   * 本机记一份（版本 + 时间）。服务端的最小记录点由 taishi 设计（tellomi/tellomi#1133）。
   * Tellomi（tellomi/tellomi#1338）：关联设备在只读告知上点「知道了」也走这里——同一份记录、同样放开网络（需求 6.1 ④）。
   */
  fun recordAgreement(context: Context) {
    TellomiLegalConsent.prefs(context).edit(commit = true) {
      putString(VERSION_KEY, NOTICE_VERSION)
      putLong(DATE_KEY, System.currentTimeMillis())
    }
    onAgreed?.invoke()
  }
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

/**
 * 跨境单独告知（tellomi/tellomi#1133）：全屏独立一页，9 项 + 隐私政策链接 + 两个同样醒目的按钮。
 * 不预选、不倒计时、不默认聚焦「同意」。「不同意」留在这一页、说明后果；返回键 = 关掉这一页，号码不发出。
 *
 * Tellomi（tellomi/tellomi#1338；需求 6.3 的三种用法）：
 * - 底部多一条《第三方信息共享及 SDK 清单》链接。
 * - [readOnly]：关联设备用的只读版（需求 6.1 ④）。单独同意在手机上取得，这台不另行收集：换标题、导语和第 9 项正文，
 *   只有一个「知道了」。点它走 [onAgree]——调用方照同意一样在本机记下 cb-1、放开网络。
 * - [showPolicyUpdated]：已注册设备升级后的盖页，在导语上方加「隐私政策已更新至 2.0.0」和链接（需求 6.2 c）。
 */
@Composable
fun TellomiCrossBorderNotice(
  onAgree: () -> Unit,
  onCancel: () -> Unit,
  readOnly: Boolean = false,
  showPolicyUpdated: Boolean = false
) {
  var showDisagreeHint by rememberSaveable { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
  ) {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
          Text(
            text = stringResource(if (readOnly) R.string.TellomiCrossBorder__linked_title else R.string.TellomiCrossBorder__title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
          )

          if (showPolicyUpdated) {
            // 隐私政策第三十二节的「以显著方式提示」：放在导语上方，底色和正文区分开（需求 6.2 c）。
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
              color = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.TellomiCrossBorder__policy_updated), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                NoticeLink(text = stringResource(R.string.TellomiCrossBorder__policy_updated_link), url = TellomiLegalConsent.PRIVACY_URL)
              }
            }
            Spacer(modifier = Modifier.height(4.dp))
          }

          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(if (readOnly) R.string.TellomiCrossBorder__linked_intro else R.string.TellomiCrossBorder__intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          crossBorderItems(readOnly).forEach { (title, body) ->
            Spacer(modifier = Modifier.height(20.dp))
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }

          Spacer(modifier = Modifier.height(20.dp))
          NoticeLink(text = stringResource(R.string.TellomiCrossBorder__privacy_link), url = TellomiLegalConsent.PRIVACY_URL)
          Spacer(modifier = Modifier.height(12.dp))
          NoticeLink(text = stringResource(R.string.TellomiCrossBorder__third_party_link), url = TellomiLegalConsent.THIRD_PARTY_URL)
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
          if (showDisagreeHint) {
            Text(
              text = stringResource(R.string.TellomiCrossBorder__disagree_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
          }

          // 两个按钮同样的样式、同样宽，谁也不比谁醒目（需求 2.2）。只读版只有一个「知道了」（tellomi/tellomi#1338）。
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!readOnly) {
              Buttons.LargeTonal(
                onClick = { showDisagreeHint = true },
                modifier = Modifier
                  .weight(1f)
                  .testTag(TellomiCrossBorderConsent.DISAGREE_TEST_TAG)
              ) {
                Text(stringResource(R.string.TellomiConsent__disagree))
              }
            }
            Buttons.LargeTonal(
              onClick = onAgree,
              modifier = Modifier
                .weight(1f)
                .testTag(if (readOnly) TellomiCrossBorderConsent.ACK_TEST_TAG else TellomiCrossBorderConsent.AGREE_TEST_TAG)
            ) {
              Text(stringResource(if (readOnly) R.string.TellomiCrossBorder__linked_ack else R.string.TellomiConsent__agree_and_continue))
            }
          }
        }
      }
    }
  }
}

/** 告知页里的一条链接，点了用浏览器打开官网上的全文。 */
@Composable
private fun NoticeLink(text: String, url: String) {
  val context = LocalContext.current
  val linkStyles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary))

  Text(
    text = buildAnnotatedString {
      withLink(LinkAnnotation.Clickable(tag = url, styles = linkStyles) { openUrl(context, url) }) {
        append(text)
      }
    },
    style = MaterialTheme.typography.bodyMedium
  )
}

/** 需求 2.3 的 9 项，顺序与隐私政策第二十一节一致。只读版（关联设备）第 9 项正文换成「同意在手机上取得」（tellomi/tellomi#1338）。 */
@Composable
private fun crossBorderItems(readOnly: Boolean): List<Pair<String, String>> = listOf(
  stringResource(R.string.TellomiCrossBorder__item_where_title) to stringResource(R.string.TellomiCrossBorder__item_where_body),
  stringResource(R.string.TellomiCrossBorder__item_recipients_title) to stringResource(R.string.TellomiCrossBorder__item_recipients_body),
  stringResource(R.string.TellomiCrossBorder__item_contact_title) to stringResource(R.string.TellomiCrossBorder__item_contact_body),
  stringResource(R.string.TellomiCrossBorder__item_purpose_title) to stringResource(R.string.TellomiCrossBorder__item_purpose_body),
  stringResource(R.string.TellomiCrossBorder__item_method_title) to stringResource(R.string.TellomiCrossBorder__item_method_body),
  stringResource(R.string.TellomiCrossBorder__item_kinds_title) to stringResource(R.string.TellomiCrossBorder__item_kinds_body),
  stringResource(R.string.TellomiCrossBorder__item_rights_title) to stringResource(R.string.TellomiCrossBorder__item_rights_body),
  stringResource(R.string.TellomiCrossBorder__item_procedure_title) to stringResource(R.string.TellomiCrossBorder__item_procedure_body),
  stringResource(R.string.TellomiCrossBorder__item_consent_title) to
    stringResource(if (readOnly) R.string.TellomiCrossBorder__linked_item_consent_body else R.string.TellomiCrossBorder__item_consent_body)
)

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
