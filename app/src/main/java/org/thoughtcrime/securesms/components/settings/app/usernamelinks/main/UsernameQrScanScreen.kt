package org.thoughtcrime.securesms.components.settings.app.usernamelinks.main

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.signal.camera.CameraCaptureMode
import org.signal.camera.CameraScreen
import org.signal.camera.CameraScreenEvents
import org.signal.camera.CameraScreenState
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.TellomiUsernames
import org.signal.core.util.Util
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.qr.QrCrosshair
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.CommunicationActions
import org.signal.mediasend.R as MediaSendR

/**
 * A screen that allows you to scan a QR code to start a chat.
 */
@Composable
fun UsernameQrScanScreen(
  qrScanResult: QrScanResult?,
  cameraState: CameraScreenState,
  cameraEmitter: (CameraScreenEvents) -> Unit,
  onQrResultHandled: () -> Unit,
  onOpenCameraClicked: () -> Unit,
  onOpenGalleryClicked: () -> Unit,
  onRecipientFound: (Recipient) -> Unit,
  hasCameraPermission: Boolean,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  when (qrScanResult) {
    QrScanResult.InvalidData -> {
      QrScanResultDialog(message = stringResource(R.string.UsernameLinkSettings_qr_result_invalid), onDismiss = onQrResultHandled)
    }

    QrScanResult.DeviceLinkCode -> {
      QrScanResultDialog(
        title = stringResource(R.string.UsernameLinkSettings_qr_result_device_link_title),
        message = stringResource(R.string.UsernameLinkSettings_qr_result_device_link_body),
        onDismiss = onQrResultHandled
      )
    }

    QrScanResult.NetworkError -> {
      QrScanResultDialog(message = stringResource(R.string.UsernameLinkSettings_qr_result_network_error), onDismiss = onQrResultHandled)
    }

    QrScanResult.QrNotFound -> {
      QrScanResultDialog(
        title = stringResource(R.string.UsernameLinkSettings_qr_code_not_found),
        message = stringResource(R.string.UsernameLinkSettings_try_scanning_another_image_containing_a_signal_qr_code),
        onDismiss = onQrResultHandled
      )
    }

    is QrScanResult.NotFound -> {
      if (qrScanResult.username != null) {
        QrScanResultDialog(message = stringResource(R.string.UsernameLinkSettings_qr_result_not_found, TellomiUsernames.toDisplayUsername(qrScanResult.username)), onDismiss = onQrResultHandled) // Tellomi（#1106 第三刀）
      } else {
        QrScanResultDialog(message = stringResource(R.string.UsernameLinkSettings_qr_result_not_found_no_username), onDismiss = onQrResultHandled)
      }
    }

    is QrScanResult.Success -> {
      onRecipientFound(qrScanResult.recipient)
    }

    // Tellomi（tellomi/tellomi#947，需求 §3.2）：群邀请码直接进加群；不是 Tellomi 的码显示内容，不再一律「二维码无效」
    is QrScanResult.GroupInvite -> {
      LaunchedEffect(qrScanResult) {
        context.findFragmentActivity()?.let { CommunicationActions.handlePotentialGroupLinkUrl(it, qrScanResult.url) }
        onQrResultHandled()
      }
    }

    is QrScanResult.OtherContent -> {
      ScannedContentDialog(text = qrScanResult.text, onDismiss = onQrResultHandled)
    }

    null -> {}
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .fillMaxHeight()
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f, true)
        .background(Color.Black)
    ) {
      if (hasCameraPermission) {
        CameraScreen(
          state = cameraState,
          emitter = cameraEmitter,
          enableQrScanning = true,
          captureMode = CameraCaptureMode.ImageOnly,
          roundCorners = false,
          fillViewport = true,
          modifier = Modifier.fillMaxSize()
        ) {
          QrCrosshair(modifier = Modifier.fillMaxSize())
        }
      } else {
        Column(
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .align(Alignment.Center)
            .padding(48.dp)
        ) {
          Text(
            text = stringResource(MediaSendR.string.CameraXFragment_to_scan_qr_code_allow_camera),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
          )
          Buttons.MediumTonal(
            colors = ButtonDefaults.filledTonalButtonColors(),
            onClick = onOpenCameraClicked
          ) {
            Text(stringResource(MediaSendR.string.CameraXFragment_allow_access))
          }
        }
      }

      FloatingActionButton(
        shape = CircleShape,
        containerColor = SignalTheme.colors.colorSurface1,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 24.dp),
        onClick = onOpenGalleryClicked
      ) {
        Image(
          painter = painterResource(id = R.drawable.symbol_album_24),
          contentDescription = null,
          colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
        )
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      Text(
        text = stringResource(R.string.UsernameLinkSettings_qr_scan_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

/** Tellomi（tellomi/tellomi#947）：两个宿主（设置页的 Fragment、找人页的 Activity）里 Compose 的 context 都能解到 FragmentActivity。 */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
  is FragmentActivity -> this
  is ContextWrapper -> baseContext.findFragmentActivity()
  else -> null
}

/** Tellomi（tellomi/tellomi#947）：不是 Tellomi 的码——显示内容，网址可以「打开链接」，都可以「复制」。 */
@Composable
private fun ScannedContentDialog(text: String, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val isWebLink = remember(text) { text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true) }
  val copiedMessage = stringResource(R.string.UsernameLinkSettings__tellomi_qr_result_copied)
  val copy = {
    Util.copyToClipboard(context, text)
    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
  }

  Dialogs.SimpleAlertDialog(
    title = stringResource(R.string.UsernameLinkSettings__tellomi_qr_result_content_title),
    body = if (text.length > 500) text.take(500) + "…" else text,
    confirm = stringResource(if (isWebLink) R.string.UsernameLinkSettings__tellomi_qr_result_open_link else R.string.UsernameLinkSettings__tellomi_qr_result_copy),
    onConfirm = { if (isWebLink) CommunicationActions.openBrowserLink(context, text) else copy() },
    dismiss = stringResource(if (isWebLink) R.string.UsernameLinkSettings__tellomi_qr_result_copy else android.R.string.cancel),
    onDeny = { if (isWebLink) copy() },
    onDismiss = onDismiss
  )
}

@Composable
private fun QrScanResultDialog(title: String? = null, message: String, onDismiss: () -> Unit) {
  Dialogs.SimpleMessageDialog(
    title = title,
    message = message,
    dismiss = stringResource(id = android.R.string.ok),
    onDismiss = onDismiss
  )
}

@DayNightPreviews
@Composable
private fun UsernameQrScanScreenPreview() {
  Previews.Preview {
    UsernameQrScanScreen(
      qrScanResult = null,
      cameraState = CameraScreenState(),
      cameraEmitter = {},
      onQrResultHandled = {},
      onOpenCameraClicked = {},
      onOpenGalleryClicked = {},
      onRecipientFound = {},
      hasCameraPermission = true
    )
  }
}

@DayNightPreviews
@Composable
private fun UsernameQrScanScreenNoPermissionPreview() {
  Previews.Preview {
    UsernameQrScanScreen(
      qrScanResult = null,
      cameraState = CameraScreenState(),
      cameraEmitter = {},
      onQrResultHandled = {},
      onOpenCameraClicked = {},
      onOpenGalleryClicked = {},
      onRecipientFound = {},
      hasCameraPermission = false
    )
  }
}
