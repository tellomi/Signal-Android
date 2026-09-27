/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.phonenumber

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import kotlinx.coroutines.delay
import org.signal.core.ui.compose.AllDevicePreviews
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.DropdownMenus
import org.signal.core.ui.compose.IconButtons.IconButton
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.TextFields
import org.signal.registration.R
import org.signal.registration.RegistrationDependencies
import org.signal.registration.TellomiRegistration
import org.signal.registration.screens.OnePaneRegistrationScaffold
import org.signal.registration.screens.RegistrationScaffold
import org.signal.registration.screens.TwoPaneRegistrationScaffold
import org.signal.registration.screens.attachDebugLogHelper
import org.signal.registration.screens.shared.AccountIdErrorText
import org.signal.registration.screens.shared.AccountIdFormat
import org.signal.registration.screens.shared.AccountIdVisualTransformation
import org.signal.registration.screens.shared.TellomiConsentRow
import org.signal.registration.screens.shared.TellomiCrossBorderConsent
import org.signal.registration.screens.shared.TellomiCrossBorderNotice
import org.signal.registration.screens.shared.TellomiLegalConsent
import org.signal.registration.screens.shared.TellomiTermsConsentDialog
import org.signal.registration.screens.shared.accountIdTextStyle
import org.signal.registration.test.TestTags
import java.util.Locale
import org.signal.core.ui.R as CoreR

/**
 * Phone number entry screen
 */
@Composable
fun PhoneNumberScreen(
  state: PhoneNumberEntryState,
  onEvent: (PhoneNumberEntryScreenEvents) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  // Tellomi（tellomi/tellomi#1338）：去掉上游进页即弹的 Google Play 服务号码提示（Identity.getSignInClient(...)
  // .getPhoneNumberHintIntent）。它在用户同意协议和跨境告知之前就调 Play 服务，违反「同意之前不联网、不调第三方」；
  // 连带去掉它失败时的 SIM 读号兜底（要 READ_PHONE_STATE，Tellomi 不声明也不申请，本来就总是读不到）。号码只能用户自己输。

  // Tellomi：先同意、再发号码（tellomi/tellomi#1211；ADR-0038 · ADR-0051 §E）。「下一步」（号码提示的自动确认已随
  // #1338 去掉）要先走到下面这个确认号码的对话框，所以只在这里拦：没勾 → 先二次确认；同意 = 勾选框看得见地打勾，停一下再出确认框；
  // 不同意 = 和点「修改号码」一样，什么都不发生。
  var consentChecked by remember { mutableStateOf(TellomiLegalConsent.hasAgreedToTerms(context)) }
  val onConsentCheckedChange: (Boolean) -> Unit = { checked ->
    consentChecked = checked
    TellomiLegalConsent.setAgreedToTerms(context, checked)
  }
  var holdConfirmForCheckmark by remember { mutableStateOf(false) }
  // Tellomi：跨境单独告知与同意（tellomi/tellomi#1133）。手机号是第一条发往境外（香港）服务端的个人信息，
  // 所以这一页排在协议同意之后、确认号码之前；同意过同一版本就不再出现。
  // Tellomi（tellomi/tellomi#1338）：号码注册是主设备，要亲自点过「同意并继续」；关联设备的「知道了」不算（hasGivenSeparateConsent）。
  var crossBorderAgreed by remember { mutableStateOf(TellomiCrossBorderConsent.hasGivenSeparateConsent(context)) }
  // 右上角菜单的「关联设备」也要连服务端（二维码），同意之前网络是关着的：和欢迎页一样先问，同意了再往下走。
  var pendingLinkDevice by remember { mutableStateOf(false) }
  val gatedOnEvent: (PhoneNumberEntryScreenEvents) -> Unit = { event ->
    if (event == PhoneNumberEntryScreenEvents.LinkDevice && !TellomiCrossBorderConsent.hasAgreed(context)) {
      pendingLinkDevice = true
    } else {
      onEvent(event)
    }
  }

  if (state.dialogs.confirmNumber) {
    when {
      !consentChecked -> TellomiTermsConsentDialog(
        onAgree = {
          onConsentCheckedChange(true)
          holdConfirmForCheckmark = true
        },
        onDisagree = { onEvent(PhoneNumberEntryScreenEvents.PhoneNumberCancelled) }
      )

      holdConfirmForCheckmark -> LaunchedEffect(Unit) {
        delay(350)
        holdConfirmForCheckmark = false
      }

      !crossBorderAgreed -> TellomiCrossBorderNotice(
        onAgree = {
          TellomiCrossBorderConsent.recordAgreement(context)
          crossBorderAgreed = true
        },
        onCancel = { onEvent(PhoneNumberEntryScreenEvents.PhoneNumberCancelled) }
      )

      else -> Dialogs.SimpleAlertDialog(
        title = stringResource(R.string.RegistrationActivity_is_the_phone_number),
        // Tellomi（#1210）：上游写「运营商可能收取短信费用」，大陆接收短信不收费；改成说明验证码的用途
        body = "+${state.countryCode} ${state.formattedNumber}\n\n${stringResource(R.string.TellomiRegistration__a_verification_code_will_be_sent)}",
        confirm = stringResource(id = android.R.string.ok),
        dismiss = stringResource(R.string.RegistrationActivity_edit_number),
        onConfirm = { onEvent(PhoneNumberEntryScreenEvents.PhoneNumberConfirmed) },
        onDismiss = { onEvent(PhoneNumberEntryScreenEvents.PhoneNumberCancelled) }
      )
    }
  }

  val simpleError: Pair<String, PhoneNumberEntryScreenEvents>? = when {
    state.dialogs.networkError -> stringResource(R.string.VerificationCodeScreen__network_error) to PhoneNumberEntryScreenEvents.NetworkErrorDialogDismissed
    state.dialogs.rateLimitedRetryAfter != null -> {
      val message = if (state.dialogs.rateLimitedRetryAfter.isPositive()) {
        // Tellomi（#1210）：上游把 Duration.toString()（「1m 30s」）原样填进去
        val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
        stringResource(R.string.VerificationCodeScreen__too_many_attempts_try_again_in_s, TellomiRegistration.retryAfterText(state.dialogs.rateLimitedRetryAfter, locale))
      } else {
        stringResource(R.string.VerificationCodeScreen__too_many_attempts)
      }
      message to PhoneNumberEntryScreenEvents.RateLimitedDialogDismissed
    }
    state.dialogs.unknownError -> stringResource(R.string.VerificationCodeScreen__an_unexpected_error_occurred) to PhoneNumberEntryScreenEvents.UnknownErrorDialogDismissed
    state.dialogs.couldNotRequestCodeWithSelectedTransport -> stringResource(R.string.VerificationCodeScreen__could_not_send_code_via_selected_method) to PhoneNumberEntryScreenEvents.CouldNotRequestCodeWithSelectedTransportDialogDismissed
    state.dialogs.unableToSendSms -> stringResource(R.string.VerificationCodeScreen__unable_to_send_sms) to PhoneNumberEntryScreenEvents.UnableToSendSmsDialogDismissed
    else -> null
  }

  simpleError?.let { (message, dismissedEvent) ->
    Dialogs.SimpleMessageDialog(
      message = message,
      dismiss = stringResource(android.R.string.ok),
      onDismiss = { onEvent(dismissedEvent) }
    )
  }

  if (state.dialogs.invalidPhoneNumber) {
    Dialogs.SimpleMessageDialog(
      title = stringResource(R.string.RegistrationActivity_invalid_phone_number),
      message = stringResource(R.string.RegistrationActivity_the_number_you_entered_is_not_valid),
      dismiss = stringResource(android.R.string.ok),
      onDismiss = { onEvent(PhoneNumberEntryScreenEvents.InvalidPhoneNumberDialogDismissed) }
    )
  }

  // Tellomi（ADR-0072 §4.2）：本机是已退出登录的账号，输入的却是另一个号码——一台手机只放一个账号，先说清会删掉旧号码的聊天记录。
  state.dialogs.confirmWipeForNewNumber?.let { maskedOldNumber ->
    Dialogs.SimpleAlertDialog(
      title = "",
      body = stringResource(R.string.TellomiRelogin__new_number_wipe_body, maskedOldNumber),
      confirm = stringResource(R.string.RegistrationActivity_continue),
      dismiss = stringResource(R.string.PinEntryScreen__cancel),
      onConfirm = { onEvent(PhoneNumberEntryScreenEvents.WipeForNewNumberConfirmed) },
      onDeny = { onEvent(PhoneNumberEntryScreenEvents.WipeForNewNumberCancelled) },
      onDismissRequest = { onEvent(PhoneNumberEntryScreenEvents.WipeForNewNumberCancelled) },
      confirmColor = MaterialTheme.colorScheme.error,
      modifier = Modifier.testTag(TestTags.PHONE_NUMBER_WIPE_FOR_NEW_NUMBER_DIALOG)
    )
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .testTag(TestTags.PHONE_NUMBER_SCREEN)
  ) {
    when (val layoutParams = RegistrationScaffold.rememberLayoutParams()) {
      is RegistrationScaffold.Params.OnePane -> OnePaneLayout(layoutParams, state, gatedOnEvent, consentChecked, onConsentCheckedChange)
      is RegistrationScaffold.Params.TwoPane -> TwoPaneLayout(layoutParams, state, gatedOnEvent, consentChecked, onConsentCheckedChange)
    }
  }

  if (pendingLinkDevice) {
    TellomiCrossBorderNotice(
      // Tellomi（tellomi/tellomi#1338）：关联设备只出只读告知、一个「知道了」，点了记下 cb-1、放开网络（需求 6.1 ④）；
      // 这不是单独同意，所以不动 crossBorderAgreed——退回来改走「下一步」时还要出完整同意。
      onAgree = {
        TellomiCrossBorderConsent.recordLinkedDeviceAcknowledgement(context)
        pendingLinkDevice = false
        onEvent(PhoneNumberEntryScreenEvents.LinkDevice)
      },
      onCancel = { pendingLinkDevice = false },
      readOnly = true
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnePaneLayout(
  params: RegistrationScaffold.Params.OnePane,
  state: PhoneNumberEntryState,
  onEvent: (PhoneNumberEntryScreenEvents) -> Unit,
  consentChecked: Boolean,
  onConsentCheckedChange: (Boolean) -> Unit
) {
  val scrollState = rememberScrollState()
  val topBarScrollBehavior = RegistrationScaffold.rememberTopBarScrollBehavior()

  OnePaneRegistrationScaffold(
    params = params,
    topBar = { TopAppBar(scrollBehavior = topBarScrollBehavior, isLinkAndSyncAvailable = state.isLinkAndSyncAvailable, onEvent = onEvent) },
    content = { paddingValues ->
      Column(
        modifier = Modifier
          .fillMaxSize()
          .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
          .verticalScroll(scrollState)
          .padding(paddingValues)
      ) {
        Description()

        Spacer(modifier = Modifier.height(36.dp))

        PhoneNumberInputFields(
          state = state,
          onEvent = onEvent,
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    footer = {
      RegistrationScaffold.FooterSurface(
        isElevated = scrollState.canScrollForward
      ) {
        Footer(params, state, onEvent, consentChecked, onConsentCheckedChange)
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwoPaneLayout(
  params: RegistrationScaffold.Params.TwoPane,
  state: PhoneNumberEntryState,
  onEvent: (PhoneNumberEntryScreenEvents) -> Unit,
  consentChecked: Boolean,
  onConsentCheckedChange: (Boolean) -> Unit
) {
  val firstPaneScrollState = rememberScrollState()
  val secondPaneScrollState = rememberScrollState()
  val topBarScrollBehavior = RegistrationScaffold.rememberTopBarScrollBehavior()

  TwoPaneRegistrationScaffold(
    params = params,
    topBar = { TopAppBar(scrollBehavior = topBarScrollBehavior, isLinkAndSyncAvailable = state.isLinkAndSyncAvailable, onEvent = onEvent) },
    firstPane = { paddingValues ->
      Column(
        modifier = Modifier
          .weight(1f)
          .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
          .verticalScroll(firstPaneScrollState)
          .padding(paddingValues)
      ) {
        Description(twoPane = true)
      }
    },
    secondPane = { paddingValues ->
      Column(
        modifier = Modifier
          .weight(1f)
          .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
          .verticalScroll(secondPaneScrollState)
          .padding(paddingValues)
      ) {
        PhoneNumberInputFields(
          state = state,
          onEvent = onEvent,
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    footer = {
      RegistrationScaffold.FooterSurface(
        isElevated = firstPaneScrollState.canScrollForward || secondPaneScrollState.canScrollForward
      ) {
        Footer(params, state, onEvent, consentChecked, onConsentCheckedChange)
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
  scrollBehavior: TopAppBarScrollBehavior,
  isLinkAndSyncAvailable: Boolean,
  onEvent: (PhoneNumberEntryScreenEvents) -> Unit
) {
  val context = LocalContext.current

  Scaffolds.DefaultTopAppBar(
    title = "",
    titleContent = { _, _ -> },
    onNavigationClick = { },
    navigationIcon = null,
    scrollBehavior = scrollBehavior,
    actions = {
      val menuController = remember { DropdownMenus.MenuController() }

      IconButton(
        onClick = { menuController.show() },
        modifier = Modifier.padding(horizontal = 8.dp)
      ) {
        Icon(
          imageVector = ImageVector.vectorResource(CoreR.drawable.symbol_more_vertical_24),
          contentDescription = stringResource(R.string.RegistrationActivity_open_menu)
        )
      }

      DropdownMenus.Menu(
        controller = menuController,
        offsetX = 24.dp,
        offsetY = 0.dp
      ) {
        DropdownMenus.Item(
          text = { Text(text = stringResource(R.string.RegistrationActivity_use_proxy)) },
          onClick = {
            RegistrationDependencies.get().proxyConfigCallback?.invoke(context)
            menuController.hide()
          }
        )
        if (isLinkAndSyncAvailable) {
          DropdownMenus.Item(
            text = { Text(text = stringResource(R.string.RegistrationActivity_link_device)) },
            onClick = {
              onEvent(PhoneNumberEntryScreenEvents.LinkDevice)
              menuController.hide()
            }
          )
        }
      }
    }
  )
}

@Composable
private fun Description(twoPane: Boolean = false) {
  Text(
    text = stringResource(R.string.RegistrationActivity_phone_number),
    style = if (twoPane) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
    modifier = Modifier
      .fillMaxWidth()
      .attachDebugLogHelper()
  )

  Text(
    text = stringResource(R.string.TellomiRegistration__you_will_receive_a_verification_code), // Tellomi（#1210）：同上
    style = if (twoPane) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal) else MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 16.dp)
  )
}

/**
 * Tellomi：协议行贴在「下一步」上面（ADR-0051 §E：勾选行贴底），默认不勾（tellomi/tellomi#1211）。
 */
@Composable
private fun Footer(
  params: RegistrationScaffold.Params,
  state: PhoneNumberEntryState,
  onEvent: (PhoneNumberEntryScreenEvents) -> Unit,
  consentChecked: Boolean,
  onConsentCheckedChange: (Boolean) -> Unit
) {
  Column {
    TellomiConsentRow(
      checked = consentChecked,
      onCheckedChange = onConsentCheckedChange,
      modifier = Modifier.padding(start = 20.dp, end = 32.dp, top = 8.dp)
    )

    NextButton(params, state, onEvent)
  }
}

@Composable
private fun NextButton(
  params: RegistrationScaffold.Params,
  state: PhoneNumberEntryState,
  onEvent: (PhoneNumberEntryScreenEvents) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(params.footerPadding),
    horizontalArrangement = Arrangement.End,
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (state.isPhoneNumberlessRegistrationAvailable) {
      TextButton(
        onClick = { onEvent(PhoneNumberEntryScreenEvents.RegisterWithoutNumber) },
        enabled = !state.showSpinner,
        modifier = Modifier.testTag(TestTags.PHONE_NUMBER_REGISTER_WITHOUT_NUMBER_BUTTON)
      ) {
        Text(
          stringResource(
            if (state.sawArchiveRestoreSelectionScreen) {
              R.string.RegistrationActivity_use_account_id
            } else {
              R.string.RegistrationActivity_register_without_number
            }
          )
        )
      }

      Spacer(modifier = Modifier.weight(1f))
    }

    Buttons.LargeTonal(
      onClick = { onEvent(PhoneNumberEntryScreenEvents.NextClicked) },
      enabled = state.isNextEnabled,
      modifier = Modifier.testTag(TestTags.PHONE_NUMBER_NEXT_BUTTON)
    ) {
      if (state.showSpinner) {
        CircularProgressIndicator(
          modifier = Modifier.size(24.dp),
          strokeWidth = 3.dp,
          color = MaterialTheme.colorScheme.primary
        )
      } else {
        Text(stringResource(R.string.RegistrationActivity_next))
      }
    }
  }
}

/**
 * The compact control to the left of the phone number field. It shows the selected calling code and opens the country
 * picker; in account ID mode there is no country to pick, so it shows a key and does nothing when tapped.
 */
@Composable
private fun CountryCodeButton(
  countryCode: String,
  isAccountId: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .widthIn(min = 69.dp)
      .height(56.dp)
      .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
      .background(MaterialTheme.colorScheme.outline)
      .padding(bottom = 1.dp)
      .background(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
      )
      .clickable(enabled = !isAccountId, onClick = onClick)
  ) {
    Row(
      modifier = Modifier
        .fillMaxHeight()
        .padding(start = 14.dp, end = 2.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (isAccountId) {
        Icon(
          imageVector = SignalIcons.Key.imageVector,
          contentDescription = stringResource(R.string.RegistrationActivity_account_id),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(24.dp)
        )
      } else {
        Text(
          text = "+$countryCode",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1
        )
      }

      Spacer(modifier = Modifier.width(4.dp))

      Icon(
        imageVector = SignalIcons.ArrowDropDown.imageVector,
        contentDescription = if (isAccountId) null else stringResource(R.string.RegistrationActivity_select_a_country),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp)
      )
    }
  }
}

/**
 * Phone number input fields containing the country code and phone number text fields.
 */
@Composable
private fun PhoneNumberInputFields(
  state: PhoneNumberEntryState,
  onEvent: (PhoneNumberEntryScreenEvents) -> Unit,
  modifier: Modifier = Modifier
) {
  var phoneNumberTextFieldValue by remember { mutableStateOf(TextFieldValue(state.formattedNumber)) }
  val focusRequester = remember { FocusRequester() }
  val interactionSource = remember { MutableInteractionSource() }
  val hasValidCountry = state.countryName.isNotEmpty()
  val isAccountId = state.enteredAccountId != null
  val label = if (isAccountId) R.string.RegistrationActivity_account_id else R.string.RegistrationActivity_phone_number_description
  val accountIdError = state.accountIdError.takeIf { isAccountId }
  val supportingText: (@Composable () -> Unit)? = when {
    accountIdError != null -> {
      { AccountIdErrorText(accountIdError) }
    }
    state.isRegionUnavailable -> {
      { Text(stringResource(R.string.TellomiRegistration__region_not_supported)) }
    }
    state.isNumberInvalid -> {
      { Text(stringResource(R.string.RegistrationActivity_not_a_valid_phone_number)) }
    }
    else -> null
  }

  LaunchedEffect(state.formattedNumber) {
    if (phoneNumberTextFieldValue.text == state.formattedNumber) {
      return@LaunchedEffect
    }

    if (isAccountId) {
      phoneNumberTextFieldValue = TextFieldValue(
        text = state.formattedNumber,
        selection = TextRange(state.formattedNumber.length)
      )
      return@LaunchedEffect
    }

    val oldText = phoneNumberTextFieldValue.text
    val oldCursorPos = phoneNumberTextFieldValue.selection.end
    val digitsBeforeCursor = oldText.take(oldCursorPos).count { it.isDigit() }

    var digitCount = 0
    var newCursorPos = state.formattedNumber.length
    for (i in state.formattedNumber.indices) {
      if (state.formattedNumber[i].isDigit()) {
        digitCount++
      }
      if (digitCount >= digitsBeforeCursor) {
        newCursorPos = i + 1
        break
      }
    }

    phoneNumberTextFieldValue = TextFieldValue(
      text = state.formattedNumber,
      selection = TextRange(newCursorPos)
    )
  }

  LaunchedEffect(hasValidCountry) {
    if (hasValidCountry) {
      focusRequester.requestFocus()
    }
  }

  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.Start,
    verticalAlignment = Alignment.Top
  ) {
    CountryCodeButton(
      countryCode = state.countryCode,
      isAccountId = isAccountId,
      onClick = { onEvent(PhoneNumberEntryScreenEvents.CountryPicker) },
      modifier = Modifier.testTag(TestTags.PHONE_NUMBER_COUNTRY_CODE_FIELD)
    )

    Spacer(modifier = Modifier.width(20.dp))

    TextField(
      value = phoneNumberTextFieldValue,
      onValueChange = { newValue ->
        // An account ID that is already complete leaves the state untouched, so there is no re-sync to lean on: the
        // field has to turn away the extra characters itself.
        val accepted = if (isAccountId) newValue.copy(text = AccountIdFormat.normalizeAndTruncate(newValue.text)) else newValue
        onEvent(PhoneNumberEntryScreenEvents.NationalNumberChanged(oldValue = phoneNumberTextFieldValue.text, newValue = accepted.text))
        phoneNumberTextFieldValue = accepted
      },
      modifier = Modifier
        .weight(1f)
        .focusRequester(focusRequester)
        .testTag(TestTags.PHONE_NUMBER_PHONE_FIELD),
      label = { TextFields.Label(stringResource(label), phoneNumberTextFieldValue.text.isNotEmpty(), interactionSource) },
      interactionSource = interactionSource,
      isError = state.isNumberInvalid || state.accountIdError != null || state.isRegionUnavailable,
      supportingText = supportingText,
      keyboardOptions = if (isAccountId) {
        KeyboardOptions(
          keyboardType = KeyboardType.Ascii,
          capitalization = KeyboardCapitalization.None,
          autoCorrectEnabled = false,
          imeAction = ImeAction.Done
        )
      } else {
        KeyboardOptions(
          keyboardType = KeyboardType.Phone,
          imeAction = ImeAction.Done
        )
      },
      keyboardActions = KeyboardActions(
        onDone = {
          if (state.isNextEnabled) {
            onEvent(PhoneNumberEntryScreenEvents.NextClicked)
          }
        }
      ),
      singleLine = true,
      // Tellomi（tellomi/tellomi#1213，ADR-0051 §C）：非空时有清空 ×。
      trailingIcon = if (phoneNumberTextFieldValue.text.isNotEmpty()) {
        {
          IconButton(
            onClick = {
              onEvent(PhoneNumberEntryScreenEvents.NationalNumberChanged(oldValue = phoneNumberTextFieldValue.text, newValue = ""))
              phoneNumberTextFieldValue = TextFieldValue("")
              // 框没有焦点时 × 也显示；清完就能接着输，和 iOS 一致（taishi 审查 b13 不阻塞）。
              focusRequester.requestFocus()
            },
            modifier = Modifier.testTag(TestTags.PHONE_NUMBER_CLEAR_BUTTON)
          ) {
            Icon(
              imageVector = SignalIcons.X.imageVector,
              contentDescription = stringResource(R.string.TellomiRegistration__clear_phone_number),
              tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      } else {
        null
      },
      visualTransformation = if (isAccountId) AccountIdVisualTransformation else VisualTransformation.None,
      textStyle = if (isAccountId) {
        accountIdTextStyle().copy(color = MaterialTheme.colorScheme.onSurface)
      } else {
        MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
      },
      colors = TextFieldDefaults.colors(
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        errorContainerColor = MaterialTheme.colorScheme.surfaceVariant
      )
    )
  }
}

@AllDevicePreviews
@Composable
private fun PhoneNumberScreenPreview() {
  Previews.Preview {
    PhoneNumberScreen(
      state = PhoneNumberEntryState(),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun PhoneNumberScreenRegisterWithoutNumberPreview() {
  Previews.Preview {
    PhoneNumberScreen(
      state = PhoneNumberEntryState(isPhoneNumberlessRegistrationAvailable = true),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun PhoneNumberScreenUseAccountIdPreview() {
  Previews.Preview {
    PhoneNumberScreen(
      state = PhoneNumberEntryState(
        isPhoneNumberlessRegistrationAvailable = true,
        sawArchiveRestoreSelectionScreen = true
      ),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun PhoneNumberScreenAccountIdPreview() {
  Previews.Preview {
    PhoneNumberScreen(
      state = PhoneNumberEntryState(
        accountId = "a6b284822e3283d07f2391360a4c2b91",
        formattedNumber = "a6b284822e3283d07f2391360a4c2b91",
        isPhoneNumberlessRegistrationAvailable = true
      ),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun PhoneNumberScreenSpinnerPreview() {
  Previews.Preview {
    PhoneNumberScreen(
      state = PhoneNumberEntryState(showSpinner = true),
      onEvent = {}
    )
  }
}
