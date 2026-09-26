/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.megaphone

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.IconButtons
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.conversation.NewConversationActivity
import org.thoughtcrime.securesms.groups.ui.creategroup.CreateGroupActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.main.EmptyMegaphoneActionController
import org.thoughtcrime.securesms.profiles.manage.EditProfileActivity
import org.thoughtcrime.securesms.wallpaper.ChatWallpaperActivity
import org.signal.core.ui.R as CoreUiR

/**
 * The onboarding megaphone (list of cards)
 */
@Composable
fun OnboardingMegaphone(
  megaphoneActionController: MegaphoneActionController,
  modifier: Modifier = Modifier,
  onboardingState: OnboardingState = OnboardingState.rememberOnboardingState(megaphoneActionController)
) {
  // Tellomi（tellomi/tellomi#1218 F-02）：列表那边改了找朋友的开关（出现第一个真人会话 / 列表空了）就重读，
  // 卡片当场收起或出现，不用等下次回到前台
  val findFriendsChanges by TellomiOnboarding.findFriendsChanges.collectAsStateWithLifecycle()
  LaunchedEffect(onboardingState, findFriendsChanges) {
    onboardingState.refresh()
  }

  Column(
    modifier = modifier
      .background(MaterialTheme.colorScheme.background)
      .padding(bottom = 22.dp)
  ) {
    Box(
      modifier = Modifier
        .height(24.dp)
        .fillMaxWidth()
        .background(
          brush = Brush.verticalGradient(
            colors = listOf(
              Color.Transparent,
              MaterialTheme.colorScheme.background
            )
          )
        )
    )

    Text(
      text = stringResource(R.string.Megaphones_get_started),
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.padding(start = 16.dp, top = 4.dp),
      color = MaterialTheme.colorScheme.onSurface
    )

    val onboardingItems = remember(onboardingState.displayState) {
      OnboardingListItem.entries.filter(onboardingState.displayState::shouldDisplayListItem)
    }

    LazyRow(
      modifier = Modifier.padding(top = 10.dp)
    ) {
      itemsIndexed(items = onboardingItems) { idx, item ->
        OnboardingMegaphoneListItem(
          onboardingListItem = item,
          onActionClick = {
            onboardingState.onItemActionClick(item)
          },
          onCloseClick = {
            onboardingState.onItemCloseClick(item)
          },
          modifier = if (idx == 0) Modifier.padding(start = 16.dp) else Modifier
        )
      }
    }
  }
}

/**
 * Single megaphone list item, such as "Invite Friends"
 */
@Composable
private fun OnboardingMegaphoneListItem(
  onboardingListItem: OnboardingListItem,
  onActionClick: (OnboardingListItem) -> Unit,
  onCloseClick: (OnboardingListItem) -> Unit,
  modifier: Modifier = Modifier
) {
  Card(
    shape = RoundedCornerShape(28.dp),
    elevation = CardDefaults.cardElevation(0.dp),
    colors = CardDefaults.cardColors(
      containerColor = colorResource(onboardingListItem.cardColor)
    ),
    modifier = modifier
      .padding(end = 12.dp)
      .width(152.dp)
      .clickable(onClick = { onActionClick(onboardingListItem) })
  ) {
    Box(
      modifier = Modifier.fillMaxWidth()
    ) {
      IconButtons.IconButton(
        onClick = { onCloseClick(onboardingListItem) },
        size = 48.dp,
        modifier = Modifier.align(Alignment.TopEnd)
      ) {
        Icon(
          imageVector = SignalIcons.X.imageVector,
          tint = colorResource(CoreUiR.color.signal_light_colorOutline),
          contentDescription = stringResource(R.string.Material3SearchToolbar__close)
        )
      }

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 84.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Icon(
          imageVector = ImageVector.vectorResource(onboardingListItem.icon),
          contentDescription = null,
          tint = colorResource(CoreUiR.color.signal_light_colorOnSurface),
          modifier = Modifier.size(24.dp)
        )

        Text(
          text = stringResource(onboardingListItem.title),
          style = MaterialTheme.typography.labelMedium,
          textAlign = TextAlign.Center,
          maxLines = 2,
          color = colorResource(CoreUiR.color.signal_light_colorOnSurface),
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 8.dp)
        )
      }
    }
  }
}

@DayNightPreviews
@Composable
private fun OnboardingMegaphonePreview() {
  Previews.Preview {
    OnboardingMegaphone(
      megaphoneActionController = EmptyMegaphoneActionController,
      onboardingState = OnboardingState.rememberOnboardingState()
    )
  }
}

@DayNightPreviews
@Composable
private fun OnboardingMegaphoneListItemPreview() {
  Previews.Preview {
    OnboardingMegaphoneListItem(
      onboardingListItem = OnboardingListItem.INVITE,
      onActionClick = {},
      onCloseClick = {}
    )
  }
}

/**
 * Represents a card that can be displayed to the user when showing onboarding content.
 */
enum class OnboardingListItem(
  @StringRes val title: Int,
  @DrawableRes val icon: Int,
  @ColorRes val cardColor: Int
) {
  // Tellomi（tellomi/tellomi#1218 F-02）：找朋友三条路——没有 CDSI，用户名、二维码、邀请是别人找到你的全部办法
  FIND_BY_USERNAME(
    title = R.string.TellomiOnboarding__search_by_username,
    icon = CoreUiR.drawable.symbol_search_24,
    cardColor = R.color.onboarding_background_1
  ),
  MY_QR_CODE(
    title = R.string.TellomiOnboarding__my_qr_code,
    icon = CoreUiR.drawable.symbol_qrcode_24,
    cardColor = R.color.onboarding_background_3
  ),
  GROUP(
    title = R.string.Megaphones_new_group,
    icon = R.drawable.symbol_group_24,
    cardColor = R.color.onboarding_background_1
  ),
  INVITE(
    title = R.string.Megaphones_invite_friends,
    icon = R.drawable.symbol_invite_24,
    cardColor = R.color.onboarding_background_2
  ),
  ADD_PHOTO(
    title = R.string.Megaphones_add_a_profile_photo,
    icon = CoreUiR.drawable.symbol_person_circle_24,
    cardColor = R.color.onboarding_background_4
  ),
  APPEARANCE(
    title = R.string.Megaphones_chat_colors,
    icon = R.drawable.ic_color_24,
    cardColor = R.color.onboarding_background_3
  )
}

/**
 * Maintains the list of displayable cards and drives actions performed by the user.
 */
abstract class OnboardingState private constructor(
  initialState: DisplayState = DisplayState(),
  val megaphoneActionController: MegaphoneActionController
) {

  companion object {
    /**
     * Grabs an [OnboardingState], keyed to the given [MegaphoneActionController]
     */
    @Composable
    fun rememberOnboardingState(megaphoneActionController: MegaphoneActionController = EmptyMegaphoneActionController): OnboardingState {
      return if (LocalInspectionMode.current) {
        Preview
      } else {
        remember(megaphoneActionController) { Real(megaphoneActionController = megaphoneActionController) }
      }
    }
  }

  /**
   * The latest display state for the list of onboarding items. An empty list means we can
   * mark this megaphone as complete.
   */
  var displayState: DisplayState by mutableStateOf(initialState)

  /**
   * When a list item is clicked.
   */
  abstract fun onItemActionClick(onboardingListItem: OnboardingListItem)

  /**
   * When a list item close button is clicked.
   */
  abstract fun onItemCloseClick(onboardingListItem: OnboardingListItem)

  /**
   * Tellomi：开关在别处被改了（出现第一个真人会话时 [TellomiOnboarding] 收起找朋友三条路），重新读一遍。
   */
  open fun refresh() = Unit

  /**
   * Preview implementation, used automatically when rendering previews.
   */
  private object Preview : OnboardingState(
    initialState = DisplayState(
      shouldShowNewGroup = true,
      shouldShowInviteFriends = true,
      shouldShowAddPhoto = true,
      shouldShowAppearance = true,
      shouldShowFindByUsername = true,
      shouldShowMyQrCode = true
    ),
    megaphoneActionController = EmptyMegaphoneActionController
  ) {
    override fun onItemCloseClick(onboardingListItem: OnboardingListItem) {
      displayState = when (onboardingListItem) {
        OnboardingListItem.GROUP -> displayState.copy(shouldShowNewGroup = false)
        OnboardingListItem.INVITE -> displayState.copy(shouldShowInviteFriends = false)
        OnboardingListItem.ADD_PHOTO -> displayState.copy(shouldShowAddPhoto = false)
        OnboardingListItem.APPEARANCE -> displayState.copy(shouldShowAppearance = false)
        OnboardingListItem.FIND_BY_USERNAME -> displayState.copy(shouldShowFindByUsername = false)
        OnboardingListItem.MY_QR_CODE -> displayState.copy(shouldShowMyQrCode = false)
      }
    }

    override fun onItemActionClick(onboardingListItem: OnboardingListItem) = Unit
  }

  /**
   * Real implementation, used automatically on-device. Backed by SignalStore.
   */
  private class Real(megaphoneActionController: MegaphoneActionController) : OnboardingState(megaphoneActionController = megaphoneActionController) {
    override fun refresh() {
      displayState = DisplayState()

      if (displayState.hasNoVisibleContent()) {
        megaphoneActionController.onMegaphoneCompleted(Megaphones.Event.ONBOARDING)
      }
    }

    override fun onItemCloseClick(onboardingListItem: OnboardingListItem) {
      when (onboardingListItem) {
        OnboardingListItem.GROUP -> SignalStore.onboarding.setShowNewGroup(false)
        OnboardingListItem.INVITE -> SignalStore.onboarding.setShowInviteFriends(false)
        OnboardingListItem.ADD_PHOTO -> SignalStore.onboarding.setShowAddPhoto(false)
        OnboardingListItem.APPEARANCE -> SignalStore.onboarding.setShowAppearance(false)
        OnboardingListItem.FIND_BY_USERNAME -> SignalStore.onboarding.setShowFindByUsername(false)
        OnboardingListItem.MY_QR_CODE -> SignalStore.onboarding.setShowMyQrCode(false)
      }

      displayState = DisplayState()

      if (displayState.hasNoVisibleContent()) {
        megaphoneActionController.onMegaphoneCompleted(Megaphones.Event.ONBOARDING)
      }
    }

    override fun onItemActionClick(onboardingListItem: OnboardingListItem) {
      when (onboardingListItem) {
        OnboardingListItem.GROUP -> megaphoneActionController.onMegaphoneNavigationRequested(CreateGroupActivity.createIntent(megaphoneActionController.megaphoneActivity))
        OnboardingListItem.INVITE -> megaphoneActionController.onMegaphoneNavigationRequested(AppSettingsActivity.invite(megaphoneActionController.megaphoneActivity))
        OnboardingListItem.ADD_PHOTO -> {
          megaphoneActionController.onMegaphoneNavigationRequested(EditProfileActivity.getIntentForAvatarEdit(megaphoneActionController.megaphoneActivity))
          SignalStore.onboarding.setShowAddPhoto(false)
        }
        OnboardingListItem.APPEARANCE -> {
          megaphoneActionController.onMegaphoneNavigationRequested(ChatWallpaperActivity.createIntent(megaphoneActionController.megaphoneActivity))
          SignalStore.onboarding.setShowAppearance(false)
        }
        // 找朋友三条路点了不收起：有了第一个真人会话才自动收（TellomiOnboarding），或者用户点 ✕
        OnboardingListItem.FIND_BY_USERNAME -> megaphoneActionController.onMegaphoneNavigationRequested(NewConversationActivity.createFindByUsernameIntent(megaphoneActionController.megaphoneActivity))
        OnboardingListItem.MY_QR_CODE -> megaphoneActionController.onMegaphoneNavigationRequested(TellomiOnboarding.myQrCodeIntent(megaphoneActionController.megaphoneActivity))
      }

      displayState = DisplayState()

      if (displayState.hasNoVisibleContent()) {
        megaphoneActionController.onMegaphoneCompleted(Megaphones.Event.ONBOARDING)
      }
    }
  }

  /**
   * Simple display state, driven by [SignalStore] by default.
   */
  data class DisplayState(
    private val shouldShowNewGroup: Boolean = SignalStore.onboarding.shouldShowNewGroup(),
    private val shouldShowInviteFriends: Boolean = SignalStore.onboarding.shouldShowInviteFriends(),
    private val shouldShowAddPhoto: Boolean = SignalStore.onboarding.shouldShowAddPhoto() && !SignalStore.misc.hasEverHadAnAvatar,
    private val shouldShowAppearance: Boolean = SignalStore.onboarding.shouldShowAppearance(),
    private val shouldShowFindByUsername: Boolean = SignalStore.onboarding.shouldShowFindByUsername(),
    private val shouldShowMyQrCode: Boolean = SignalStore.onboarding.shouldShowMyQrCode()
  ) {
    fun hasNoVisibleContent(): Boolean = OnboardingListItem.entries.none(::shouldDisplayListItem)

    fun shouldDisplayListItem(onboardingListItem: OnboardingListItem): Boolean {
      return when (onboardingListItem) {
        // Tellomi（tellomi/tellomi#1218 第 5 条）：「新建群组」「聊天颜色」不出——新用户还没有可拉进群的人，聊天颜色不是上手必需
        OnboardingListItem.GROUP -> false
        OnboardingListItem.INVITE -> shouldShowInviteFriends
        OnboardingListItem.ADD_PHOTO -> shouldShowAddPhoto
        OnboardingListItem.APPEARANCE -> false
        OnboardingListItem.FIND_BY_USERNAME -> shouldShowFindByUsername
        OnboardingListItem.MY_QR_CODE -> shouldShowMyQrCode
      }
    }
  }
}
