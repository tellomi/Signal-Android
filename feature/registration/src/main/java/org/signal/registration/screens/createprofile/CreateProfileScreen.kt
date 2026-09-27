/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.createprofile

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.signal.core.ui.WindowBreakpoint
import org.signal.core.ui.compose.AllDevicePreviews
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.rememberWindowBreakpoint
import org.signal.core.util.TellomiNames
import org.signal.registration.R
import org.signal.registration.screens.RegistrationScaffold
import org.signal.registration.test.TestTags

/**
 * Profile creation screen for the registration flow. Captures the user's given name, family name,
 * avatar, and phone-number discoverability before completing registration.
 *
 * Dispatches to a per-[WindowBreakpoint] layout following the pattern in `WelcomeScreen`. All three
 * breakpoints currently share the [CompactLayout] body — Medium/Large variants can be split out
 * later without changing this entry point.
 */
@Composable
fun CreateProfileScreen(
  state: CreateProfileState,
  onEvent: (CreateProfileScreenEvents) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val pickAvatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) {
      val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      }.getOrNull()
      if (bytes != null) {
        onEvent(CreateProfileScreenEvents.AvatarSelected(bytes))
      }
    }
  }
  val onAvatarClick = {
    pickAvatarLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
  }

  if (state.showUploadFailedDialog) {
    Dialogs.SimpleMessageDialog(
      message = stringResource(R.string.VerificationCodeScreen__an_unexpected_error_occurred),
      dismiss = stringResource(android.R.string.ok),
      onDismiss = { onEvent(CreateProfileScreenEvents.UploadFailedDialogDismissed) }
    )
  }

  if (state.isLoading) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  when (rememberWindowBreakpoint()) {
    is WindowBreakpoint.Small -> CompactLayout(state, onEvent, onAvatarClick, modifier)
    is WindowBreakpoint.Medium -> MediumLayout(state, onEvent, onAvatarClick, modifier)
    is WindowBreakpoint.Large -> LargeLayout(state, onEvent, onAvatarClick, modifier)
  }
}

@Composable
private fun CompactLayout(
  state: CreateProfileState,
  onEvent: (CreateProfileScreenEvents) -> Unit,
  onAvatarClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val givenNameInteractionSource = remember { MutableInteractionSource() }
  // Tellomi（tellomi/tellomi#1215）：「姓氏（可选）」框去掉了，上游给它的 familyNameInteractionSource 不要

  RegistrationScaffold(
    modifier = modifier
      .fillMaxSize()
      .testTag(TestTags.CREATE_PROFILE_SCREEN),
    content = {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
          text = stringResource(R.string.CreateProfileScreen__set_up_your_profile),
          style = MaterialTheme.typography.headlineMedium,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = stringResource(R.string.CreateProfileScreen__your_profile_is_end_to_end_encrypted),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Avatar(avatarBytes = state.avatar, initials = TellomiNames.abbreviation(state.givenName), onClick = onAvatarClick)

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
          value = state.givenName,
          onValueChange = { onEvent(CreateProfileScreenEvents.GivenNameChanged(it)) },
          // Tellomi（tellomi/tellomi#1210）：上游「名字（必需）」→「名字」+ 红色必填星号；
          // #1215 之后这一个框填全名，英文也写「Name」而不是「First name」
          label = {
            Text(
              buildAnnotatedString {
                append(stringResource(R.string.TellomiRegistration__name))
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append(" *") }
              }
            )
          },
          interactionSource = givenNameInteractionSource,
          singleLine = true,
          enabled = !state.isSubmitting,
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            // 下面还有用户名框：「下一项」跳过去（taishi 审查包 4）；用户名已确认、框锁住时没有下一项，仍是「完成」
            imeAction = if (state.showUsername && state.usernameEntry.confirmed == null) ImeAction.Next else ImeAction.Done
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.CREATE_PROFILE_GIVEN_NAME_FIELD)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Tellomi（tellomi/tellomi#1266）：重新注册时不显示，交给设置页
        if (state.showUsername) {
          TellomiUsernameField(
            entry = state.usernameEntry,
            enabled = !state.isSubmitting && state.usernameEntry.confirmed == null,
            onEvent = onEvent
          )
        }

        // Tellomi（tellomi/tellomi#1215）：只留一个「名字」框（去掉「姓氏（可选）」，保存时全进 given name）；
        // 「谁可以通过手机号找到我」也去掉——没有 CDSI 时它不起作用，换成一句实话。
        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = stringResource(R.string.TellomiRegistration__phone_number_not_shown),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.CREATE_PROFILE_PHONE_NUMBER_NOT_SHOWN)
        )
      }
    },
    footer = {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
      ) {
        Buttons.LargeTonal(
          onClick = { onEvent(CreateProfileScreenEvents.NextClicked) },
          enabled = state.isFormValid && !state.isSubmitting,
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp)
            .testTag(TestTags.CREATE_PROFILE_NEXT_BUTTON)
        ) {
          if (state.isSubmitting) {
            CircularProgressIndicator(
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              strokeWidth = 2.dp,
              modifier = Modifier.size(20.dp)
            )
          } else {
            Text(stringResource(R.string.CreateProfileScreen__next))
          }
        }
      }
    }
  )
}

@Composable
private fun MediumLayout(
  state: CreateProfileState,
  onEvent: (CreateProfileScreenEvents) -> Unit,
  onAvatarClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  // TODO [registration] dedicated medium-width layout. For now, reuse the compact body.
  CompactLayout(state = state, onEvent = onEvent, onAvatarClick = onAvatarClick, modifier = modifier)
}

@Composable
private fun LargeLayout(
  state: CreateProfileState,
  onEvent: (CreateProfileScreenEvents) -> Unit,
  onAvatarClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  // TODO [registration] dedicated large-width layout. For now, reuse the compact body.
  CompactLayout(state = state, onEvent = onEvent, onAvatarClick = onAvatarClick, modifier = modifier)
}

/**
 * Tellomi（tellomi/tellomi#1215 第二刀）：用户名（选填）。说明行一直占一行（出错 / 查到 / 在查都在这一行），界面不跳；
 * 不可用时下面给三个候选，点了照常走一遍检查。
 *
 * ADR-0066 §6.1b（owner 2026-09-27）：说明行上面再常驻一行灰色规则提示；打了（或粘贴了）大写当场转小写（[TellomiUsernameInput]），
 * 光标 / 选区不跳，规则提示换成「已自动转成小写」约 2 秒。所以输入框自己拿着 [TextFieldValue]（选区在这里），文字仍以视图模型为准。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TellomiUsernameField(
  entry: TellomiUsernameEntry,
  enabled: Boolean,
  onEvent: (CreateProfileScreenEvents) -> Unit
) {
  var fieldValue by remember { mutableStateOf(TextFieldValue(entry.text, TextRange(entry.text.length))) }
  var lowercasedCount by remember { mutableIntStateOf(0) }
  var showLowercasedHint by remember { mutableStateOf(false) }

  // 视图模型改了文字（去掉 @、点了候选……）才覆盖输入框，光标放到末尾；自己打的字视图模型原样收下，不会走到这里
  LaunchedEffect(entry.text) {
    if (entry.text != fieldValue.text) {
      fieldValue = TextFieldValue(entry.text, TextRange(entry.text.length))
    }
  }

  LaunchedEffect(lowercasedCount) {
    if (lowercasedCount > 0) {
      showLowercasedHint = true
      delay(TellomiUsernameInput.LOWERCASED_HINT_MS)
      showLowercasedHint = false
    }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
      value = fieldValue,
      onValueChange = { typed ->
        val lowered = TellomiUsernameInput.lowercase(typed)
        if (lowered !== typed) {
          lowercasedCount++
        }
        val textChanged = lowered.text != fieldValue.text
        fieldValue = lowered
        if (textChanged) {
          onEvent(CreateProfileScreenEvents.UsernameChanged(lowered.text))
        }
      },
      label = { Text(stringResource(R.string.TellomiRegistration__username_optional)) },
      singleLine = true,
      enabled = enabled,
      isError = entry.error != null,
      keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Ascii,
        imeAction = ImeAction.Done
      ),
      trailingIcon = when {
        entry.isChecking -> {
          {
            CircularProgressIndicator(
              strokeWidth = 2.dp,
              modifier = Modifier.size(18.dp)
            )
          }
        }
        entry.reservation != null && entry.error == null -> {
          {
            Icon(
              painter = SignalIcons.CheckCircle.painter,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary
            )
          }
        }
        else -> null
      },
      supportingText = {
        Column {
          // 规则提示：出错时也保持灰色（输入框出错时整个说明区默认是红色）
          Text(
            text = stringResource(if (showLowercasedHint) R.string.TellomiUsername__lowercased_hint else R.string.TellomiUsername__rules_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .testTag(TestTags.CREATE_PROFILE_USERNAME_RULES_HINT)
              .semantics { liveRegion = LiveRegionMode.Polite }
          )
          Text(
            text = usernameSupportingText(entry),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
              .testTag(TestTags.CREATE_PROFILE_USERNAME_SUPPORTING_TEXT)
              // 「正在检查 / 不可用 / 你的链接」变了读屏要念出来（taishi 审查包 4）。停顿 500ms 才查，一次输入最多念两回
              .semantics { liveRegion = LiveRegionMode.Polite }
          )
        }
      },
      modifier = Modifier
        .fillMaxWidth()
        .testTag(TestTags.CREATE_PROFILE_USERNAME_FIELD)
    )

    if (entry.candidates.isNotEmpty()) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = stringResource(R.string.TellomiRegistration__username_try),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.align(Alignment.CenterVertically)
        )
        entry.candidates.forEach { candidate ->
          SuggestionChip(
            onClick = { onEvent(CreateProfileScreenEvents.UsernameCandidateClicked(candidate)) },
            label = { Text(candidate) },
            enabled = enabled,
            modifier = Modifier.testTag(TestTags.CREATE_PROFILE_USERNAME_CANDIDATE)
          )
        }
      }
    }
  }
}

@Composable
private fun usernameSupportingText(entry: TellomiUsernameEntry): String {
  return when (entry.error) {
    TellomiUsernameEntry.Error.TOO_SHORT -> stringResource(R.string.TellomiRegistration__username_too_short)
    TellomiUsernameEntry.Error.TOO_LONG -> stringResource(R.string.TellomiRegistration__username_too_long)
    TellomiUsernameEntry.Error.INVALID_CHARACTERS -> stringResource(R.string.TellomiRegistration__username_invalid_characters)
    TellomiUsernameEntry.Error.MUST_START_WITH_LETTER -> stringResource(R.string.TellomiRegistration__username_must_start_with_letter)
    TellomiUsernameEntry.Error.NOT_AVAILABLE -> stringResource(R.string.TellomiRegistration__username_not_available)
    TellomiUsernameEntry.Error.CHECK_FAILED -> stringResource(R.string.TellomiRegistration__username_check_failed)
    TellomiUsernameEntry.Error.TOO_MANY_ATTEMPTS -> stringResource(R.string.TellomiRegistration__username_too_many_attempts)
    TellomiUsernameEntry.Error.RENAME_COOLDOWN -> pluralStringResource(R.plurals.TellomiRegistration__username_cooldown, entry.cooldownDays, entry.cooldownDays)
    null -> when {
      entry.isChecking -> stringResource(R.string.TellomiRegistration__username_checking)
      entry.reservation != null -> stringResource(R.string.TellomiRegistration__username_your_link, "tell.cc/" + entry.text.lowercase())
      else -> ""
    }
  }
}

@Composable
private fun Avatar(
  avatarBytes: ByteArray?,
  initials: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val setAvatarDescription = stringResource(R.string.CreateProfileScreen__set_avatar_description)
  val bitmap = remember(avatarBytes) {
    avatarBytes?.let {
      runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
    }
  }

  Box(modifier = modifier.size(112.dp)) {
    Box(
      modifier = Modifier
        .size(112.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClick = onClick),
      contentAlignment = Alignment.Center
    ) {
      if (bitmap != null) {
        androidx.compose.foundation.Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = stringResource(R.string.CreateProfileScreen__set_avatar_description),
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      } else if (initials != null) {
        // Tellomi（tellomi/tellomi#1215）：没选照片时，默认头像随名字实时变（中文取最后两个字，其它取首字母），
        // 与之后 App 里显示的默认头像同一个规则（TellomiNames.abbreviation）
        Text(
          text = initials,
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          // 和照片、相机图标两支一样念「设置头像」，否则读屏只念出名字（taishi 审查 2026-09-24）
          modifier = Modifier
            .testTag(TestTags.CREATE_PROFILE_AVATAR_INITIALS)
            .semantics { contentDescription = setAvatarDescription }
        )
      } else {
        Icon(
          painter = SignalIcons.Camera.painter,
          contentDescription = stringResource(R.string.CreateProfileScreen__set_avatar_description),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(40.dp)
        )
      }
    }

    Box(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .offset(x = 4.dp, y = 4.dp)
        .size(36.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary)
        .clickable(onClick = onClick),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        painter = SignalIcons.Camera.painter,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}

@AllDevicePreviews
@Composable
private fun CreateProfileScreenLoadingPreview() {
  Previews.Preview {
    CreateProfileScreen(
      state = CreateProfileState(isLoading = true),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun CreateProfileScreenEmptyPreview() {
  Previews.Preview {
    CreateProfileScreen(
      state = CreateProfileState(isLoading = false),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun CreateProfileScreenWithNamePreview() {
  Previews.Preview {
    CreateProfileScreen(
      state = CreateProfileState(
        givenName = "Alice",
        familyName = "Anderson",
        isLoading = false
      ),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun CreateProfileScreenChineseNamePreview() {
  Previews.Preview {
    CreateProfileScreen(
      state = CreateProfileState(
        givenName = "欧阳娜娜",
        isLoading = false
      ),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun CreateProfileScreenNobodyPreview() {
  Previews.Preview {
    CreateProfileScreen(
      state = CreateProfileState(
        givenName = "Alice",
        familyName = "Anderson",
        discoverableByPhoneNumber = false,
        isLoading = false
      ),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun CreateProfileScreenSubmittingPreview() {
  Previews.Preview {
    CreateProfileScreen(
      state = CreateProfileState(
        givenName = "Alice",
        familyName = "Anderson",
        isLoading = false,
        isSubmitting = true
      ),
      onEvent = {}
    )
  }
}
