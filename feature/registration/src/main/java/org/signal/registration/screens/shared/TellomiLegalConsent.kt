/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.shared

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
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
  const val FULL_NOTICE_LINK_TEST_TAG = "tellomi-cross-border-full-notice-link"
  const val FULL_NOTICE_CLOSE_TEST_TAG = "tellomi-cross-border-full-notice-close"

  private const val VERSION_KEY = "cross_border.version"
  private const val DATE_KEY = "cross_border.date"

  /** Tellomi（tellomi/tellomi#1338）：这份记录只来自关联设备的「知道了」，不是单独同意（iOS 同一个标记在 Signal-iOS#118）。 */
  private const val LINKED_ACK_ONLY_KEY = "cross_border.linked_ack_only"

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
   * Tellomi（tellomi/tellomi#1338）：这台设备的用户是否亲自点过「同意并继续」。
   * [hasAgreed] 只管网络闸（关联设备点「知道了」也算）；要当主设备用——号码注册、恢复 / 转移——必须看这个：
   * 只点过关联设备的「知道了」、又退回来改走注册的，还要出完整的「同意 / 不同意」。
   */
  fun hasGivenSeparateConsent(context: Context): Boolean {
    return hasAgreed(context) && !TellomiLegalConsent.prefs(context).getBoolean(LINKED_ACK_ONLY_KEY, false)
  }

  /**
   * 本机记一份（版本 + 时间）。服务端的最小记录点由 taishi 设计（tellomi/tellomi#1133）。
   * Tellomi（tellomi/tellomi#1338）：这是完整的「同意并继续」，会清掉关联设备「知道了」留下的标记（见 [recordLinkedDeviceAcknowledgement]）。
   */
  fun recordAgreement(context: Context) {
    TellomiLegalConsent.prefs(context).edit(commit = true) {
      putString(VERSION_KEY, NOTICE_VERSION)
      putLong(DATE_KEY, System.currentTimeMillis())
      remove(LINKED_ACK_ONLY_KEY)
    }
    onAgreed?.invoke()
  }

  /**
   * Tellomi（tellomi/tellomi#1338）：关联设备在只读告知上点「知道了」。和同意一样记下版本、放开网络（需求 6.1 ④），
   * 另记「只是知道了」——它不能顶替主设备的单独同意（[hasGivenSeparateConsent]）。已经完整同意过的不降级。
   */
  fun recordLinkedDeviceAcknowledgement(context: Context) {
    val alreadyConsented = hasGivenSeparateConsent(context)
    TellomiLegalConsent.prefs(context).edit(commit = true) {
      putString(VERSION_KEY, NOTICE_VERSION)
      putLong(DATE_KEY, System.currentTimeMillis())
      if (!alreadyConsented) {
        putBoolean(LINKED_ACK_ONLY_KEY, true)
      }
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
 * 跨境单独告知（tellomi/tellomi#1133）。不预选、不倒计时、不默认聚焦「同意」；两个按钮同样醒目（需求 2.2）。
 *
 * Tellomi（tellomi/tellomi#1338；需求 6.6，owner 2026-09-27 下午改版）：整页长文改成**两行小弹窗**——
 * 标题、一句话正文、「查看《个人信息出境告知》」、「不同意」「同意并继续」。点「不同意」在正文下面说明后果，弹窗不关。
 * 9 项全文在点链接后盖在弹窗上面的全文页里（本地字符串渲染：同意之前不联网，不能去官网取），「返回」回到弹窗。
 * - [readOnly]：关联设备（需求 6.1 ④）：正文换成「同意在手机上取得」，只有一个「知道了」；全文页第 9 项正文也换掉。
 *   点它走 [onAgree]——调用方用 [TellomiCrossBorderConsent.recordLinkedDeviceAcknowledgement] 记下 cb-1、放开网络，但不算单独同意。
 * - [showPolicyUpdated]：已注册设备升级后的盖页，最上面加「《隐私政策》已更新至 2.0.0 版。」和链接（需求 6.2 c）。
 * - [cancelable]：点外面 / 返回键 = [onCancel]（回到号码页，什么都没发出去）；升级盖页传 false，关不掉。
 */
@Composable
fun TellomiCrossBorderNotice(
  onAgree: () -> Unit,
  onCancel: () -> Unit,
  readOnly: Boolean = false,
  showPolicyUpdated: Boolean = false,
  cancelable: Boolean = true
) {
  var showDisagreeHint by rememberSaveable { mutableStateOf(false) }
  var showFullNotice by rememberSaveable { mutableStateOf(false) }

  Dialogs.BaseAlertDialog(
    onDismissRequest = { if (cancelable) onCancel() },
    title = { Text(text = stringResource(R.string.TellomiCrossBorder__dialog_title)) },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (showPolicyUpdated) {
          Text(text = stringResource(R.string.TellomiCrossBorder__dialog_policy_updated), color = MaterialTheme.colorScheme.onSurface)
          NoticeLink(text = stringResource(R.string.TellomiCrossBorder__policy_updated_link), url = TellomiLegalConsent.PRIVACY_URL)
          Spacer(modifier = Modifier.height(16.dp))
        }

        Text(text = stringResource(if (readOnly) R.string.TellomiCrossBorder__linked_dialog_body else R.string.TellomiCrossBorder__dialog_body))

        if (showDisagreeHint && !readOnly) {
          Spacer(modifier = Modifier.height(12.dp))
          // 说明后果，不是报错：用正文颜色，不用红字催人同意。
          Text(text = stringResource(R.string.TellomiCrossBorder__disagree_hint), color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.TellomiCrossBorder__dialog_full_notice_link),
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .clickable(role = Role.Button) { showFullNotice = true }
            .testTag(TellomiCrossBorderConsent.FULL_NOTICE_LINK_TEST_TAG)
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onAgree,
        modifier = Modifier.testTag(if (readOnly) TellomiCrossBorderConsent.ACK_TEST_TAG else TellomiCrossBorderConsent.AGREE_TEST_TAG)
      ) {
        Text(stringResource(if (readOnly) R.string.TellomiCrossBorder__linked_ack else R.string.TellomiConsent__agree_and_continue))
      }
    },
    dismissButton = if (readOnly) {
      null
    } else {
      {
        TextButton(
          onClick = { showDisagreeHint = true },
          modifier = Modifier.testTag(TellomiCrossBorderConsent.DISAGREE_TEST_TAG)
        ) {
          Text(stringResource(R.string.TellomiConsent__disagree))
        }
      }
    },
    modifier = Modifier,
    properties = DialogProperties(dismissOnBackPress = cancelable, dismissOnClickOutside = cancelable)
  )

  if (showFullNotice) {
    TellomiCrossBorderFullNotice(readOnly = readOnly, onClose = { showFullNotice = false })
  }
}

/** 需求 6.6 的全文页：盖在弹窗上面，9 项 + 两条链接 + 「返回」。返回键也是回到弹窗。 */
@Composable
private fun TellomiCrossBorderFullNotice(readOnly: Boolean, onClose: () -> Unit) {
  Dialog(
    onDismissRequest = onClose,
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
            text = stringResource(R.string.TellomiCrossBorder__full_notice_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
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

        Buttons.LargeTonal(
          onClick = onClose,
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag(TellomiCrossBorderConsent.FULL_NOTICE_CLOSE_TEST_TAG)
        ) {
          Text(stringResource(R.string.TellomiCrossBorder__full_notice_close))
        }
      }
    }
  }
}

/** 告知里的一条链接：用户点了才用系统浏览器打开官网上的全文。 */
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

/** 需求 2.3 的 9 项，顺序与隐私政策第二十一节一致。关联设备那一版第 9 项正文换成「同意在手机上取得」（tellomi/tellomi#1338）。 */
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
