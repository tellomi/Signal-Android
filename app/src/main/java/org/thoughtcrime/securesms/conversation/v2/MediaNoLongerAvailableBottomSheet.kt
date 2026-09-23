/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.BottomSheets
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.ComposeBottomSheetDialogFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.horizontalGutters
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.backup.v2.MessageBackupTier
import org.thoughtcrime.securesms.components.settings.app.subscription.MessageBackupsCheckoutLauncher.createBackupsCheckoutLauncher
import org.thoughtcrime.securesms.util.Environment

/**
 * Bottom sheet displayed when the user taps media that is not available for download,
 * over 30 days old, and they do not currently have a subscription.
 */
class MediaNoLongerAvailableBottomSheet : ComposeBottomSheetDialogFragment() {

  private lateinit var checkoutLauncher: ActivityResultLauncher<MessageBackupTier?>

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    checkoutLauncher = createBackupsCheckoutLauncher {
      dismissAllowingStateLoss()
    }
  }

  @Composable
  override fun SheetContent() {
    MediaNoLongerAvailableBottomSheetContent(
      showBackupsUpsell = Environment.Backups.PAID_BACKUPS_ENABLED,
      onContinueClick = {
        checkoutLauncher.launch(MessageBackupTier.PAID)
      },
      onNotNowClick = { dismissAllowingStateLoss() }
    )
  }
}

/**
 * Tellomi（#984）：付费备份档不做时（[Environment.Backups.PAID_BACKUPS_ENABLED]），只告诉用户「此媒体已不可用」，
 * 不再推付费备份——「继续」会直接进付费档的结账流程，那一页写着「Signal 是一个非营利性平台」。
 * 附件在服务端过期后点它就会弹这张表，是正常使用就能走到的入口（`ConversationFragment.onDisplayMediaNoLongerAvailableSheet`）。
 */
@Composable
private fun MediaNoLongerAvailableBottomSheetContent(
  showBackupsUpsell: Boolean = true,
  onContinueClick: () -> Unit = {},
  onNotNowClick: () -> Unit = {}
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .horizontalGutters()
  ) {
    BottomSheets.Handle()

    Image(
      painter = painterResource(R.drawable.image_signal_backups_media),
      contentDescription = null,
      modifier = Modifier.padding(vertical = 16.dp)
    )

    Text(
      text = stringResource(R.string.MediaNoLongerAvailableSheet__this_media_is_no_longer_available),
      style = MaterialTheme.typography.titleLarge,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(bottom = if (showBackupsUpsell) 10.dp else 32.dp)
    )

    if (showBackupsUpsell) {
      Text(
        text = stringResource(R.string.MediaNoLongerAvailableSheet__to_start_backing_up_all_your_media),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 92.dp)
      )

      Buttons.LargeTonal(
        onClick = onContinueClick,
        modifier = Modifier
          .padding(bottom = 22.dp)
          .defaultMinSize(minWidth = 220.dp)
      ) {
        Text(
          text = stringResource(R.string.MediaNoLongerAvailableSheet__continue)
        )
      }
    }

    TextButton(
      onClick = onNotNowClick,
      modifier = Modifier
        .padding(bottom = 32.dp)
        .defaultMinSize(minWidth = 220.dp)
    ) {
      Text(
        text = stringResource(if (showBackupsUpsell) R.string.MediaNoLongerAvailableSheet__not_now else android.R.string.ok)
      )
    }
  }
}

@DayNightPreviews
@Composable
private fun MediaNoLongerAvailableBottomSheetContentPreview() {
  Previews.BottomSheetContentPreview {
    MediaNoLongerAvailableBottomSheetContent()
  }
}
