/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.shared

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.R
import org.signal.registration.screens.phonenumber.PhoneNumberEntryScreenEvents
import org.signal.registration.screens.phonenumber.PhoneNumberEntryState
import org.signal.registration.screens.phonenumber.PhoneNumberScreen
import org.signal.registration.screens.welcome.WelcomeScreen
import org.signal.registration.screens.welcome.WelcomeScreenEvents
import org.signal.registration.screens.welcome.WelcomeScreenState
import org.signal.registration.test.TestTags

/**
 * Tellomi：注册同意（tellomi/tellomi#1211；ADR-0038 · ADR-0051 §E）。
 * 每条用例都从空的本机记录开始（Robolectric 每条用例一个新的 Application）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLegalConsentTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** 放开网络的回调是全局的（应用层启动时挂上）；用例里挂的测完摘掉，别串到下一条。 */
  @After
  fun tearDown() {
    TellomiCrossBorderConsent.onAgreed = null
  }

  /** 点了「下一步」、号码也合法：视图模型已经要出「号码是否正确」的确认框了。 */
  private val confirmingState = PhoneNumberEntryState(
    countryCode = "86",
    nationalNumber = "13800138000",
    formattedNumber = "138 0013 8000",
    isNumberPossible = true,
    dialogs = PhoneNumberEntryState.Dialogs(confirmNumber = true)
  )

  @Test
  fun `consent checkbox is unchecked by default`() {
    setPhoneNumberScreen(PhoneNumberEntryState(countryCode = "86"))

    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOff()
    assert(!TellomiLegalConsent.hasAgreedToTerms(context))
  }

  @Test
  fun `without consent, the consent dialog replaces the confirm number dialog`() {
    setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__dialog_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertDoesNotExist()
  }

  @Test
  fun `disagree cancels like edit number and records nothing`() {
    val events = setPhoneNumberScreen(confirmingState)

    // 点的必须是同意框的「不同意」——确认号码框的「修改号码」也发同一个事件
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__dialog_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_DISMISS_BUTTON).performClick()

    assert(events == listOf<PhoneNumberEntryScreenEvents>(PhoneNumberEntryScreenEvents.PhoneNumberCancelled)) { "Unexpected events: $events" }
    assert(!TellomiLegalConsent.hasAgreedToTerms(context))
    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOff()
  }

  @Test
  fun `agree checks the box, records consent, then shows the confirm number dialog`() {
    // 这条只测协议勾选；跨境告知另有用例
    TellomiCrossBorderConsent.recordAgreement(context)
    val events = setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_CONFIRM_BUTTON).performClick()

    // 先让用户看见勾打上：确认框要停一下才出来
    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOn()
    assert(TellomiLegalConsent.hasAgreedToTerms(context))
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertDoesNotExist()

    composeTestRule.mainClock.advanceTimeBy(400)

    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertIsDisplayed()
    assert(events.isEmpty()) { "Agreeing must not submit the number by itself, but got $events" }
  }

  @Test
  fun `with earlier consent, the confirm number dialog shows directly`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    TellomiCrossBorderConsent.recordAgreement(context)

    setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOn()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__dialog_title)).assertDoesNotExist()
  }

  @Test
  fun `after the terms, the cross-border notice comes before the confirm number dialog`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)

    setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertDoesNotExist()
    // Tellomi（tellomi/tellomi#1338，需求 6.6 判据 1）：两行小弹窗 + 「查看《个人信息出境告知》」；弹窗里没有 9 项全文、没有「隐私政策已更新」
    assertFullNoticeShown()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.FULL_NOTICE_LINK_TEST_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__item_where_body)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__item_procedure_body)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__third_party_link)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_policy_updated)).assertDoesNotExist()
  }

  /** Tellomi（tellomi/tellomi#1338，需求 6.6 判据 2）：弹窗里的链接打开全文（本地字符串），「返回」回到弹窗，什么都没记、没发。 */
  @Test
  fun `the full notice link opens the nine items on top, and Back returns to the dialog`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    val events = setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.FULL_NOTICE_LINK_TEST_TAG).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__full_notice_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__item_where_body)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__item_procedure_body)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__item_consent_body)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__linked_item_consent_body)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__privacy_link)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__third_party_link)).assertExists()

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.FULL_NOTICE_CLOSE_TEST_TAG).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__full_notice_title)).assertDoesNotExist()
    assertFullNoticeShown()
    assert(!TellomiCrossBorderConsent.hasAgreed(context))
    assert(events.isEmpty()) { "Reading the full notice must not send or cancel anything, but got $events" }
  }

  /** Tellomi（owner 2026-09-27 下午，需求 6.6）：弹窗正文缩成一句「传到境外、是否同意」；服务器在哪、存在哪都在全文里。 */
  @Test
  fun `the dialog body is one sentence asking about the transfer`() {
    val body = context.getString(R.string.TellomiCrossBorder__dialog_body)
    assert(body == "Some of your personal information will be transferred outside mainland China for processing. Do you agree?") { "Unexpected dialog body: $body" }
  }

  @Config(qualifiers = "zh-rCN")
  @Test
  fun `the dialog body is one sentence asking about the transfer in simplified chinese`() {
    val body = context.getString(R.string.TellomiCrossBorder__dialog_body)
    assert(body == "您的部分个人信息将传输到中国大陆境外处理，是否同意？") { "Unexpected dialog body: $body" }
  }

  /** Tellomi（tellomi/tellomi#1338，需求 6.6 判据 5）：被弹窗取代的 5 个 key 不再留在资源里。 */
  @Test
  fun `the keys replaced by the dialog are gone`() {
    val left = listOf("title", "intro", "linked_title", "linked_intro", "policy_updated")
      .map { "TellomiCrossBorder__$it" }
      .filter { context.resources.getIdentifier(it, "string", context.packageName) != 0 }
    assert(left.isEmpty()) { "Keys replaced by the dialog are still in the resources: $left" }
  }

  @Test
  fun `agreeing to the cross-border notice records it and shows the confirm number dialog`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    val events = setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.AGREE_TEST_TAG).performClick()

    assert(TellomiCrossBorderConsent.hasAgreed(context))
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_title)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertIsDisplayed()
    assert(events.isEmpty()) { "Agreeing must not submit the number by itself, but got $events" }
  }

  @Test
  fun `disagreeing keeps the cross-border notice open with a hint and records nothing`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    val events = setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.DISAGREE_TEST_TAG).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__disagree_hint)).assertIsDisplayed()
    assert(!TellomiCrossBorderConsent.hasAgreed(context))
    assert(events.isEmpty()) { "Disagreeing must not send or cancel anything, but got $events" }
  }

  @Test
  fun `a new cross-border notice version asks again`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    // 模拟「同意过的是旧版本」
    TellomiLegalConsent.prefs(context).edit().putString("cross_border.version", "0.0.9").commit()
    assert(!TellomiCrossBorderConsent.hasAgreed(context))

    setPhoneNumberScreen(confirmingState)

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_title)).assertIsDisplayed()
  }

  /** Tellomi（tellomi/tellomi#1338）：告知定稿成独立版本 cb-1（需求 6.1 ①）；同意过草稿 0.1.0-draft 的设备要再出这一页。 */
  @Test
  fun `consent to the draft notice is asked again, and agreeing now records cb-1`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    TellomiLegalConsent.prefs(context).edit().putString("cross_border.version", "0.1.0-draft").commit()
    assert(!TellomiCrossBorderConsent.hasAgreed(context)) { "Consent to 0.1.0-draft must not count for the final notice" }

    setPhoneNumberScreen(confirmingState)
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.AGREE_TEST_TAG).performClick()

    val recorded = TellomiLegalConsent.prefs(context).getString("cross_border.version", null)
    assert(recorded == "cb-1") { "Expected cb-1 to be recorded, but got $recorded" }
  }

  /** Tellomi（tellomi/tellomi#1338）：《用户服务协议》《隐私政策》升到 2.0.0；按 1.0.0 同意过的，首次启动提示和号码页勾选都要重来。 */
  @Test
  fun `agreement to the 1_0_0 documents is asked again under 2_0_0`() {
    TellomiLegalConsent.prefs(context).edit()
      .putString("terms_and_privacy.version", "1.0.0")
      .putString("first_launch_notice.version", "1.0.0")
      .commit()
    assert(!TellomiLegalConsent.hasAgreedToTerms(context)) { "Agreeing to the 1.0.0 terms must not count for 2.0.0" }
    assert(!TellomiLegalConsent.hasAcceptedFirstLaunchNotice(context)) { "The 1.0.0 first launch notice must not count for 2.0.0" }

    setWelcomeScreen()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_title)).assertIsDisplayed()

    TellomiLegalConsent.setAgreedToTerms(context, true)
    val recorded = TellomiLegalConsent.prefs(context).getString("terms_and_privacy.version", null)
    assert(recorded == "2.0.0") { "Expected 2.0.0 to be recorded, but got $recorded" }
  }

  /**
   * Tellomi（tellomi/tellomi#1338）：已注册设备升级后的盖页（需求 6.2 c；6.6 判据 3）：同一个弹窗，最上面是
   * 「《隐私政策》已更新至 2.0.0 版。」+ 链接，其余照完整同意；关不掉（cancelable = false，由网络闸传）。
   */
  @Test
  fun `the upgrade dialog shows the privacy policy update at the top of the full consent`() {
    var agreed = false
    composeTestRule.setContent {
      SignalTheme {
        TellomiCrossBorderNotice(onAgree = { agreed = true }, onCancel = {}, showPolicyUpdated = true, cancelable = false)
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_policy_updated)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__policy_updated_link)).assertIsDisplayed()
    assertFullNoticeShown()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.AGREE_TEST_TAG).performClick()
    assert(agreed)
  }

  /** Tellomi（tellomi/tellomi#1338）：关联设备的全文页第 9 项换成「同意在手机上取得」。 */
  @Test
  fun `the linked dialog's full notice uses the linked item 9 body`() {
    composeTestRule.setContent {
      SignalTheme {
        TellomiCrossBorderNotice(onAgree = {}, onCancel = {}, readOnly = true)
      }
    }

    assertReadOnlyNoticeShown()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.FULL_NOTICE_LINK_TEST_TAG).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__full_notice_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__linked_item_consent_body)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__item_consent_body)).assertDoesNotExist()
  }

  @Test
  fun `unchecking the box withdraws consent`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    setPhoneNumberScreen(PhoneNumberEntryState(countryCode = "86"))

    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).performClick()

    composeTestRule.onNodeWithTag(TellomiLegalConsent.CHECKBOX_TEST_TAG).assertIsOff()
    assert(!TellomiLegalConsent.hasAgreedToTerms(context))
  }

  @Test
  fun `first launch shows the privacy notice, and disagree keeps it open with a hint`() {
    setWelcomeScreen()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_DISMISS_BUTTON).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_disagree_hint), substring = true).assertIsDisplayed()
    assert(!TellomiLegalConsent.hasAcceptedFirstLaunchNotice(context))
  }

  @Test
  fun `agreeing to the first launch notice closes it and is remembered`() {
    setWelcomeScreen()

    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_CONFIRM_BUTTON).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_title)).assertDoesNotExist()
    assert(TellomiLegalConsent.hasAcceptedFirstLaunchNotice(context))
  }

  @Test
  fun `the first launch notice does not come back once accepted`() {
    TellomiLegalConsent.acceptFirstLaunchNotice(context)

    setWelcomeScreen()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiConsent__first_launch_title)).assertDoesNotExist()
  }

  @Test
  fun `restoring from the old phone asks for cross-border consent first`() {
    TellomiLegalConsent.acceptFirstLaunchNotice(context)
    val events = setWelcomeScreenCollecting()

    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_HAS_OLD_PHONE_BUTTON).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_title)).assertIsDisplayed()
    assert(events.isEmpty()) { "Nothing may go out before cross-border consent, but got $events" }
    // Tellomi（tellomi/tellomi#1338）：恢复 / 转移是主设备，保留完整同意（「不同意」「同意并继续」），不是关联设备的只读版
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.DISAGREE_TEST_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__linked_dialog_body)).assertDoesNotExist()

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.AGREE_TEST_TAG).performClick()

    assert(TellomiCrossBorderConsent.hasAgreed(context))
    assert(events == listOf<WelcomeScreenEvents>(WelcomeScreenEvents.HasOldPhone)) { "Unexpected events: $events" }
  }

  @Test
  fun `continuing to the phone number does not ask for cross-border consent yet`() {
    TellomiLegalConsent.acceptFirstLaunchNotice(context)
    val events = setWelcomeScreenCollecting()

    composeTestRule.onNodeWithTag(TestTags.WELCOME_GET_STARTED_BUTTON).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_title)).assertDoesNotExist()
    assert(events == listOf<WelcomeScreenEvents>(WelcomeScreenEvents.Continue)) { "Unexpected events: $events" }
  }

  /** Tellomi（tellomi/tellomi#1338）：关联设备的同意在手机上取得，这台只出只读告知，一个「知道了」（需求 6.1 ④）。平板上欢迎页的主按钮就是「关联」。 */
  @Config(qualifiers = "w1280dp-h800dp-xhdpi")
  @Test
  fun `linking this device from the welcome screen shows the read-only notice, and Got It records cb-1 and lifts the gate`() {
    TellomiLegalConsent.acceptFirstLaunchNotice(context)
    var networkReleased = false
    TellomiCrossBorderConsent.onAgreed = { networkReleased = true }
    val events = setWelcomeScreenCollecting(WelcomeScreenState(isLinkAndSyncAvailable = true))

    composeTestRule.onNodeWithTag(TestTags.WELCOME_LINK_DEVICE_BUTTON).performClick()

    assertReadOnlyNoticeShown()
    assert(events.isEmpty()) { "Linking must wait for the notice, but got $events" }

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.ACK_TEST_TAG).performClick()

    assertAcknowledgedAsCb1(networkReleased)
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__linked_dialog_body)).assertDoesNotExist()
    assert(events == listOf<WelcomeScreenEvents>(WelcomeScreenEvents.LinkDevice)) { "Got It should go on to linking, but got $events" }
  }

  private fun setWelcomeScreenCollecting(state: WelcomeScreenState = WelcomeScreenState()): List<WelcomeScreenEvents> {
    val events = mutableListOf<WelcomeScreenEvents>()
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = state, onEvent = { events += it })
      }
    }
    return events
  }

  /** 只读版（需求 6.6 判据 4）：同一个弹窗标题、关联设备的正文、「查看《个人信息出境告知》」、只有一个「知道了」，没有「同意」「不同意」。 */
  private fun assertReadOnlyNoticeShown() {
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__linked_dialog_body)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.FULL_NOTICE_LINK_TEST_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.ACK_TEST_TAG).assertIsDisplayed()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_body)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_policy_updated)).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.AGREE_TEST_TAG).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.DISAGREE_TEST_TAG).assertDoesNotExist()
  }

  /** 「知道了」和「同意」记的是同一份（本机 cb-1），也同样放开网络（回调由应用层的 TellomiCrossBorderNetworkGate 挂上）。 */
  private fun assertAcknowledgedAsCb1(networkReleased: Boolean) {
    assert(TellomiCrossBorderConsent.hasAgreed(context))
    val recorded = TellomiLegalConsent.prefs(context).getString("cross_border.version", null)
    assert(recorded == "cb-1") { "Expected cb-1 to be recorded, but got $recorded" }
    assert(networkReleased) { "Got It must lift the network gate like agreeing does" }
    assert(!TellomiCrossBorderConsent.hasGivenSeparateConsent(context)) { "Got It on the linked notice must not count as separate consent" }
  }

  /**
   * Tellomi（tellomi/tellomi#1338）：只读版的「知道了」只是关联设备的告知，不是单独同意。
   * 点过「知道了」又退回来改走号码注册 / 恢复（主设备）时，必须还出完整的「同意 / 不同意」；网络闸照旧只看 hasAgreed。
   */
  @Test
  fun `the linked acknowledgement lets the network through but is not separate consent`() {
    TellomiCrossBorderConsent.recordLinkedDeviceAcknowledgement(context)

    assert(TellomiCrossBorderConsent.hasAgreed(context)) { "Got It must still lift the network gate for the linked device" }
    assert(!TellomiCrossBorderConsent.hasGivenSeparateConsent(context)) { "Got It on the linked notice must not count as separate consent" }
  }

  @Test
  fun `a full agree is separate consent`() {
    TellomiCrossBorderConsent.recordAgreement(context)

    assert(TellomiCrossBorderConsent.hasAgreed(context))
    assert(TellomiCrossBorderConsent.hasGivenSeparateConsent(context)) { "Agree and Continue must count as separate consent" }
  }

  @Test
  fun `a full agree after the linked acknowledgement clears the linked-only marker`() {
    TellomiCrossBorderConsent.recordLinkedDeviceAcknowledgement(context)
    TellomiCrossBorderConsent.recordAgreement(context)

    assert(TellomiCrossBorderConsent.hasGivenSeparateConsent(context)) { "Agreeing after Got It must count as separate consent" }
    assert(!TellomiLegalConsent.prefs(context).getBoolean("cross_border.linked_ack_only", false)) { "Agreeing must clear the linked-only marker" }
  }

  @Test
  fun `after the linked acknowledgement, confirming a phone number still shows the full notice`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    TellomiCrossBorderConsent.recordLinkedDeviceAcknowledgement(context)

    val events = setPhoneNumberScreen(confirmingState)

    assertFullNoticeShown()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertDoesNotExist()
    assert(events.isEmpty()) { "Nothing may be sent before separate consent, but got $events" }
  }

  @Test
  fun `after the linked acknowledgement, restoring from the old phone still shows the full notice`() {
    TellomiLegalConsent.acceptFirstLaunchNotice(context)
    TellomiCrossBorderConsent.recordLinkedDeviceAcknowledgement(context)
    val events = setWelcomeScreenCollecting()

    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_OR_TRANSFER_BUTTON).performClick()
    composeTestRule.onNodeWithTag(TestTags.WELCOME_RESTORE_HAS_OLD_PHONE_BUTTON).performClick()

    assertFullNoticeShown()
    assert(events.isEmpty()) { "Restoring must wait for separate consent, but got $events" }
  }

  /** 协调方给的复现：号码页菜单「关联设备」→「知道了」→ 从二维码页退回来 →「下一步」。必须出完整同意，号码不能直接发出。 */
  @Test
  fun `linking from the phone number menu, backing out, then Next still asks for full consent`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    var state by mutableStateOf(PhoneNumberEntryState(isLinkAndSyncAvailable = true, countryCode = "86", nationalNumber = "13800138000", formattedNumber = "138 0013 8000", isNumberPossible = true))
    val events = mutableListOf<PhoneNumberEntryScreenEvents>()
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(state = state, onEvent = { events += it })
      }
    }

    composeTestRule.onNodeWithContentDescription(context.getString(R.string.RegistrationActivity_open_menu)).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_link_device)).performClick()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.ACK_TEST_TAG).performClick()
    assert(events == listOf(PhoneNumberEntryScreenEvents.LinkDevice)) { "Got It should go on to linking, but got $events" }

    // 从二维码页退回号码页，点「下一步」：视图模型要出「号码是否正确」的确认框了
    events.clear()
    state = state.copy(dialogs = PhoneNumberEntryState.Dialogs(confirmNumber = true))

    assertFullNoticeShown()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_is_the_phone_number)).assertDoesNotExist()
    assert(events.isEmpty()) { "Nothing may be sent before separate consent, but got $events" }
  }

  /** 完整版弹窗：标题、主设备的正文、「不同意」「同意并继续」，没有关联设备的正文和「知道了」。 */
  private fun assertFullNoticeShown() {
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_body)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.AGREE_TEST_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.DISAGREE_TEST_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__linked_dialog_body)).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.ACK_TEST_TAG).assertDoesNotExist()
  }

  /**
   * 号码页右上角菜单的「关联设备」也要连服务端（要二维码），告知之前网络是关着的：先出告知，点了再往下走（A3b 审查）。
   * Tellomi（tellomi/tellomi#1338）：关联设备出只读版，一个「知道了」（需求 6.1 ④）。
   */
  @Test
  fun `linking a device from the phone number menu shows the read-only notice first, and Got It records cb-1 and lifts the gate`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    var networkReleased = false
    TellomiCrossBorderConsent.onAgreed = { networkReleased = true }
    val events = setPhoneNumberScreen(PhoneNumberEntryState(isLinkAndSyncAvailable = true))

    composeTestRule.onNodeWithContentDescription(context.getString(R.string.RegistrationActivity_open_menu)).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_link_device)).performClick()

    assertReadOnlyNoticeShown()
    assert(events.isEmpty()) { "Linking must wait for the notice, but got $events" }

    composeTestRule.onNodeWithTag(TellomiCrossBorderConsent.ACK_TEST_TAG).performClick()

    assertAcknowledgedAsCb1(networkReleased)
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__linked_dialog_body)).assertDoesNotExist()
    assert(events == listOf(PhoneNumberEntryScreenEvents.LinkDevice)) { "Got It should go on to linking, but got $events" }
  }

  @Test
  fun `linking a device from the phone number menu goes straight on once cross-border is agreed`() {
    TellomiLegalConsent.setAgreedToTerms(context, true)
    TellomiCrossBorderConsent.recordAgreement(context)
    val events = setPhoneNumberScreen(PhoneNumberEntryState(isLinkAndSyncAvailable = true))

    composeTestRule.onNodeWithContentDescription(context.getString(R.string.RegistrationActivity_open_menu)).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.RegistrationActivity_link_device)).performClick()

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiCrossBorder__dialog_title)).assertDoesNotExist()
    assert(events == listOf(PhoneNumberEntryScreenEvents.LinkDevice)) { "Expected LinkDevice, but got $events" }
  }

  private fun setPhoneNumberScreen(state: PhoneNumberEntryState): List<PhoneNumberEntryScreenEvents> {
    val events = mutableListOf<PhoneNumberEntryScreenEvents>()
    composeTestRule.setContent {
      SignalTheme {
        PhoneNumberScreen(state = state, onEvent = { events += it })
      }
    }
    return events
  }

  private fun setWelcomeScreen() {
    composeTestRule.setContent {
      SignalTheme {
        WelcomeScreen(state = WelcomeScreenState(), onEvent = {})
      }
    }
  }
}
