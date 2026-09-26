/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.SentMediaQuality
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1261，需求第三节 P-1、P-2、P-5、P-8…P-10，判据 1、3、5）：选图网格的顶栏、编号勾、底栏与「···」菜单。
 * 替换上游选图页底部「下一步（N）」按钮的用例（那个按钮和缩略条已由顶栏「✓N」与底栏「说明 + 发送」取代）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class MediaPickerGridTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaSelectScreenEvents>()

  // 判据 1：0 张时顶栏只有 ✕ 和「最近 ⌄」；选 1 张时出现「✓1」和「···」。

  @Test
  fun `Given nothing is selected, when displayed, then the top bar has only close and Recents`() {
    setContent(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_FOLDER_TITLE).assertTextContains("Recents")
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).assertDoesNotExist()
    composeTestRule.onNode(hasContentDescription("More options")).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_SEND_BAR).assertDoesNotExist()
  }

  @Test
  fun `Given one photo is selected, when displayed, then the count pill says 1 and the more menu is there`() {
    setContent(selectedMedia = MEDIA.take(1))

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).assertIsDisplayed().assertTextContains("1")
    composeTestRule.onNode(hasContentDescription("More options")).assertIsDisplayed()
  }

  // P-8：编号勾显示第几张；点勾选上 / 取消，点照片本身进单张预览 / 编辑（P-10）。

  @Test
  fun `Given two items are selected, when displayed, then each check shows its place in the selection`() {
    setContent(selectedMedia = listOf(MEDIA[3], MEDIA[1]))

    composeTestRule.onNodeWithTag(TestTags.mediaPickerCheck(MEDIA[3].uri.toString())).assertTextContains("1")
    composeTestRule.onNodeWithTag(TestTags.mediaPickerCheck(MEDIA[1].uri.toString())).assertTextContains("2")
  }

  @Test
  fun `Given a tile, when its check is clicked, then the selection is toggled rather than the editor opened`() {
    setContent(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.mediaPickerCheck(MEDIA[2].uri.toString())).performClick()

    assertThat(events.filterNot { it is MediaSelectScreenEvents.Refresh }).containsExactly(MediaSelectScreenEvents.MediaClick(MEDIA[2]))
  }

  @Test
  fun `Given a tile, when the photo itself is clicked, then it is opened in the editor`() {
    setContent(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.mediaPickerTile(MEDIA[2].uri.toString())).performClick()

    assertThat(events).contains(MediaSelectScreenEvents.OpenMedia(MEDIA[2]))
  }

  // 判据 5：选完在网格里写说明直接发出。

  @Test
  fun `Given a selection, when send is clicked, then it is sent from the grid`() {
    setContent(selectedMedia = MEDIA.take(2))

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_SEND_BAR).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON, useUnmergedTree = true).performClick()

    assertThat(events).contains(MediaSelectScreenEvents.Send)
  }

  @Test
  fun `Given a selection, when the caption field is clicked, then the message is asked for`() {
    setContent(selectedMedia = MEDIA.take(1))

    composeTestRule.onNodeWithText("Message", useUnmergedTree = true).performClick()

    assertThat(events).contains(MediaSelectScreenEvents.AddMessage(startWithEmojiKeyboard = false))
  }

  // 判据 3：「···」只有「以高清质量发送」「单独发送」，点了立即发出；D9：默认已是高时换成「以标准质量发送」。

  @Test
  fun `Given two photos in a conversation, when the more menu is opened, then it offers high quality and separately`() {
    setContent(selectedMedia = MEDIA.take(2), sendOptions = MediaSelectState.SendOptions(canSendSeparately = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send in High Quality").assertIsDisplayed()
    composeTestRule.onNodeWithText("Send Separately").performClick()

    assertThat(events).contains(MediaSelectScreenEvents.SendSeparately)
  }

  @Test
  fun `Given the menu, when send in high quality is picked, then it is sent right away at high quality`() {
    setContent(selectedMedia = MEDIA.take(1))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send in High Quality").performClick()

    assertThat(events).contains(MediaSelectScreenEvents.SendWithQuality(SentMediaQuality.HIGH))
  }

  @Test
  fun `Given the default is already high, when the more menu is opened, then it offers standard quality instead`() {
    setContent(selectedMedia = MEDIA.take(1), sendOptions = MediaSelectState.SendOptions(sentMediaQuality = SentMediaQuality.HIGH))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send in Standard Quality").performClick()
    composeTestRule.onNodeWithText("Send in High Quality").assertDoesNotExist()

    assertThat(events).contains(MediaSelectScreenEvents.SendWithQuality(SentMediaQuality.STANDARD))
  }

  @Test
  fun `Given one photo, when the more menu is opened, then there is no send separately`() {
    setContent(selectedMedia = MEDIA.take(1), sendOptions = MediaSelectState.SendOptions(canSendSeparately = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send Separately").assertDoesNotExist()
  }

  // P-1：「最近 ⌄」里换相册。

  @Test
  fun `Given the folder title, when another folder is picked, then the grid switches to it`() {
    setContent(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_FOLDER_TITLE).performClick()
    composeTestRule.onNodeWithText("Camera", substring = true).performClick()

    assertThat(events).contains(MediaSelectScreenEvents.SwitchFolder(CAMERA))
  }

  private fun setContent(
    selectedMedia: List<Media>,
    sendOptions: MediaSelectState.SendOptions = MediaSelectState.SendOptions()
  ) {
    composeTestRule.setContent {
      SignalTheme {
        Box(modifier = Modifier.size(SCREEN_WIDTH.dp, SCREEN_HEIGHT.dp)) {
          MediaSelectScreen(
            state = MediaSelectState.Files(
              selectedMediaFolder = RECENTS,
              selectedMediaFolderItems = MEDIA,
              selectedMedia = selectedMedia,
              mediaFolders = listOf(RECENTS, CAMERA),
              sendOptions = sendOptions
            ),
            onEvent = { events += it }
          )
        }
      }
    }

    composeTestRule.waitForIdle()
  }

  private companion object {
    private const val SCREEN_WIDTH = 400f
    private const val SCREEN_HEIGHT = 800f

    private val RECENTS = MediaFolder(
      thumbnailUri = "content://recents".toUri(),
      title = "All media",
      itemCount = 8,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      folderType = MediaFolder.FolderType.NORMAL
    )

    private val CAMERA = MediaFolder(
      thumbnailUri = "content://camera".toUri(),
      title = "Camera",
      itemCount = 3,
      bucketId = "camera",
      folderType = MediaFolder.FolderType.CAMERA
    )

    private val MEDIA: List<Media> = (0 until 8).map { index ->
      Media(
        uri = "content://media/$index".toUri(),
        contentType = "image/jpeg",
        date = index.toLong(),
        width = 100,
        height = 100,
        size = 1024,
        duration = 0,
        isBorderless = false,
        isVideoGif = false,
        bucketId = "bucket",
        caption = null,
        transformProperties = null,
        fileName = null
      )
    }
  }
}
