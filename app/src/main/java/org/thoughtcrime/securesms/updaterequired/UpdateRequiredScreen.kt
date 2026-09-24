/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.AllDevicePreviews
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredState.Download

object UpdateRequiredTestTags {
  const val PRIMARY_BUTTON = "update_required_primary_button"
  const val DOWNLOAD_FROM_WEBSITE = "update_required_download_from_website"
}

/**
 * 「必须更新」阻断页（tellomi/tellomi#1138，需求 3.4）：Tellomi 标 → 标题 → 一句原因 → 版本与大小 → 唯一主按钮。
 * 没有关闭按钮；下载进度画在主按钮里；失败时按钮变「重试」，下方给「去官网下载」。
 */
@Composable
fun UpdateRequiredScreen(
  state: UpdateRequiredState,
  onEvent: (UpdateRequiredScreenEvent) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier = modifier) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxSize()
        .systemBarsPadding()
        .displayCutoutPadding()
        .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
      BoxWithConstraints(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = maxHeight)
            .verticalScroll(rememberScrollState())
        ) {
          Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_signal_logo_large),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(72.dp)
          )

          Spacer(modifier = Modifier.height(32.dp))

          Text(
            text = stringResource(R.string.TellomiUpdateRequired__title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
          )

          Spacer(modifier = Modifier.height(16.dp))

          Text(
            text = stringResource(
              when (state.reason) {
                UpdateRequiredState.Reason.SERVER_REJECTED -> R.string.TellomiUpdateRequired__reason_server_rejected
                UpdateRequiredState.Reason.BUILD_EXPIRED -> R.string.TellomiUpdateRequired__reason_build_expired
              }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
          )

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = stringResource(R.string.TellomiUpdateRequired__chats_kept),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
          )

          VersionLine(state)
        }
      }

      StatusMessage(state)

      Buttons.LargePrimary(
        onClick = { onEvent(UpdateRequiredScreenEvent.PrimaryClicked) },
        modifier = Modifier
          .fillMaxWidth()
          .testTag(UpdateRequiredTestTags.PRIMARY_BUTTON)
      ) {
        PrimaryButtonContent(state)
      }

      if (state.managesAppUpdates && (state.download == Download.Failed || state.download == Download.NoNewerVersion)) {
        TextButton(
          onClick = { onEvent(UpdateRequiredScreenEvent.DownloadFromWebsiteClicked) },
          modifier = Modifier
            .padding(top = 8.dp)
            .testTag(UpdateRequiredTestTags.DOWNLOAD_FROM_WEBSITE)
        ) {
          Text(text = stringResource(R.string.TellomiUpdateRequired__download_from_website))
        }
      }
    }
  }
}

@Composable
private fun VersionLine(state: UpdateRequiredState) {
  val versionName = state.newVersionName ?: return
  val context = LocalContext.current
  val text = if (state.totalBytes != null) {
    stringResource(R.string.TellomiUpdateRequired__new_version_and_size, versionName, Formatter.formatShortFileSize(context, state.totalBytes))
  } else {
    stringResource(R.string.TellomiUpdateRequired__new_version, versionName)
  }

  Spacer(modifier = Modifier.height(24.dp))
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth()
  )
}

@Composable
private fun StatusMessage(state: UpdateRequiredState) {
  val message = when {
    state.download == Download.NeedsInstallPermission -> R.string.TellomiUpdateRequired__install_permission
    state.download == Download.Failed -> R.string.TellomiUpdateRequired__download_failed
    state.download == Download.NoNewerVersion -> R.string.TellomiUpdateRequired__no_newer_version
    state.isOffline && state.managesAppUpdates -> R.string.TellomiUpdateRequired__offline
    else -> null
  } ?: return

  Text(
    text = stringResource(message),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = 16.dp)
  )
}

@Composable
private fun PrimaryButtonContent(state: UpdateRequiredState) {
  if (!state.managesAppUpdates) {
    Text(text = stringResource(R.string.TellomiUpdateRequired__update_now))
    return
  }

  when (val download = state.download) {
    Download.Idle -> Text(text = stringResource(R.string.TellomiUpdateRequired__update_now))
    Download.NeedsInstallPermission -> Text(text = stringResource(R.string.TellomiUpdateRequired__open_settings))
    Download.ReadyToInstall -> Text(text = stringResource(R.string.TellomiUpdateRequired__install))
    Download.Failed, Download.NoNewerVersion -> Text(text = stringResource(R.string.TellomiUpdateRequired__retry))
    is Download.InProgress -> {
      // 进度画在按钮里（需求 3.4）。
      if (download.percent == null) {
        CircularProgressIndicator(
          color = MaterialTheme.colorScheme.onPrimary,
          strokeWidth = 2.dp,
          modifier = Modifier.size(18.dp)
        )
      } else {
        CircularProgressIndicator(
          progress = { download.percent / 100f },
          color = MaterialTheme.colorScheme.onPrimary,
          trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
          strokeWidth = 2.dp,
          modifier = Modifier.size(18.dp)
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = if (download.percent == null) {
          stringResource(R.string.TellomiUpdateRequired__preparing)
        } else {
          stringResource(R.string.TellomiUpdateRequired__downloading, download.percent)
        }
      )
    }
  }
}

@AllDevicePreviews
@Composable
private fun UpdateRequiredScreenIdlePreview() {
  Previews.Preview {
    UpdateRequiredScreen(state = UpdateRequiredState(newVersionName = "0.1.3"), onEvent = {})
  }
}

@AllDevicePreviews
@Composable
private fun UpdateRequiredScreenDownloadingPreview() {
  Previews.Preview {
    UpdateRequiredScreen(
      state = UpdateRequiredState(newVersionName = "0.1.3", totalBytes = 78_000_000, download = Download.InProgress(42)),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun UpdateRequiredScreenFailedPreview() {
  Previews.Preview {
    UpdateRequiredScreen(state = UpdateRequiredState(reason = UpdateRequiredState.Reason.BUILD_EXPIRED, download = Download.Failed), onEvent = {})
  }
}
