/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1261 P-8）：「最近」第一格的相机格，在真的选图网格上（400 × 800，1dp = 1px）。
 * 竖屏 3 列、间距 2：格子边长 (400 - 4) / 3 = 132。相机格一格宽、两行高（132 × 2 + 2），其它格子绕开它排。
 * 相机格可点，里面的取景节点被合并进它的语义里：找取景要查未合并的树（否则「不存在」永远成立）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class MediaPickerCameraCellTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaSelectScreenEvents>()
  private var selection by mutableStateOf(emptyList<Media>())

  @Test
  fun `Given camera permission, the first cell of Recents is a live viewfinder two rows tall`() {
    setContent(PickerCameraAccess.GRANTED)

    composeTestRule.onNodeWithTag(FAKE_VIEWFINDER, useUnmergedTree = true).assertExists()
    val camera = bounds(TestTags.MEDIA_PICKER_CAMERA)
    val side = bounds(TestTags.mediaPickerTile(MEDIA[0].uri.toString())).width
    assertEquals(0f, camera.left, 0.5f)
    assertEquals(0f, camera.top, 0.5f)
    assertEquals("一格宽", side, camera.width, 0.5f)
    assertEquals("两行高（含 2 的间距）", side * 2 + 2f, camera.height, 0.5f)

    // 第 0、1 张在第一行第 1、2 列；第 2、3 张在第二行第 1、2 列；第 4 张回到第三行第 0 列。
    val t0 = tile(0)
    val t2 = tile(2)
    val t4 = tile(4)
    assertEquals(side + 2f, t0.left, 0.5f)
    assertEquals(0f, t0.top, 0.5f)
    assertEquals(t0.left, t2.left, 0.5f)
    assertEquals(side + 2f, t2.top, 0.5f)
    assertEquals(0f, t4.left, 0.5f)
    assertEquals(camera.bottom + 2f, t4.top, 0.5f)
  }

  @Test
  fun `Without camera permission, it is a camera icon and tapping it goes to the camera`() {
    setContent(PickerCameraAccess.NOT_GRANTED)

    composeTestRule.onNodeWithTag(FAKE_VIEWFINDER, useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_CAMERA)
      .assertContentDescriptionEquals("Go to camera")
      .performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf(MediaSelectScreenEvents.NavigateToCamera), events.filterIsInstance<MediaSelectScreenEvents.NavigateToCamera>())
  }

  @Test
  fun `Tapping the lower half of the camera also goes to the camera`() {
    setContent(PickerCameraAccess.GRANTED)

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_CAMERA_SPACER).performClick()
    composeTestRule.waitForIdle()

    assertEquals(1, events.count { it is MediaSelectScreenEvents.NavigateToCamera })
  }

  @Test
  fun `Without a camera there is no camera cell`() {
    setContent(PickerCameraAccess.NONE)

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_CAMERA).assertDoesNotExist()
    assertEquals(Offset.Zero, tile(0).topLeft)
  }

  @Test
  fun `Only Recents has the camera cell`() {
    setContent(PickerCameraAccess.GRANTED, folder = SCREENSHOTS)

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_CAMERA).assertDoesNotExist()
    assertEquals(Offset.Zero, tile(0).topLeft)
  }

  @Test
  fun `Swiping across the first row beside the camera selects those files`() {
    setContent(PickerCameraAccess.GRANTED)

    val first = tile(0)
    val second = tile(1)
    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(first.center)
      moveBy(Offset(12f, 0f))
      moveTo(second.center)
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(listOf(MEDIA[0], MEDIA[1]), selection)
  }

  @Test
  fun `Long press dragging over the second row beside the camera selects those files`() {
    setContent(PickerCameraAccess.GRANTED)

    val third = tile(2)
    val fourth = tile(3)
    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(third.center)
      advanceEventTime(600)
      moveTo(fourth.center)
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(setOf(MEDIA[2], MEDIA[3]), selection.toSet())
    assertTrue(events.none { it is MediaSelectScreenEvents.NavigateToCamera })
  }

  /** 某个节点在网格坐标里的范围。 */
  private fun bounds(tag: String): Rect {
    val grid = composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).fetchSemanticsNode()
    val node = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode()
    val offset = node.positionInRoot - grid.positionInRoot
    return Rect(offset, androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
  }

  private fun tile(index: Int): Rect = bounds(TestTags.mediaPickerTile(MEDIA[index].uri.toString()))

  private fun onEvent(event: MediaSelectScreenEvents) {
    events += event
    when (event) {
      is MediaSelectScreenEvents.MediaSelected -> selection = selection + event.media.filterNot { media -> selection.any { it.uri == media.uri } }
      is MediaSelectScreenEvents.MediaUnselected -> selection = selection.filterNot { media -> event.media.any { it.uri == media.uri } }
      else -> Unit
    }
  }

  private fun setContent(cameraAccess: PickerCameraAccess, folder: MediaFolder = RECENTS) {
    composeTestRule.setContent {
      SignalTheme {
        CompositionLocalProvider(
          LocalPickerCameraViewfinder provides { modifier -> Box(modifier = modifier.testTag(FAKE_VIEWFINDER)) }
        ) {
          Box(modifier = Modifier.size(400.dp, 800.dp)) {
            MediaSelectScreen(
              state = MediaSelectState.Files(
                selectedMediaFolder = folder,
                selectedMediaFolderItems = MEDIA,
                selectedMedia = selection,
                mediaPermissions = MediaPermissions.FULL,
                cameraAccess = cameraAccess
              ),
              onEvent = ::onEvent
            )
          }
        }
      }
    }
    composeTestRule.waitForIdle()
  }

  private companion object {
    private const val FAKE_VIEWFINDER = "fake_viewfinder"

    private val RECENTS = MediaFolder(
      thumbnailUri = "content://folder".toUri(),
      title = "Recents",
      itemCount = 12,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      folderType = MediaFolder.FolderType.NORMAL
    )

    private val SCREENSHOTS = MediaFolder(
      thumbnailUri = "content://folder/screenshots".toUri(),
      title = "Screenshots",
      itemCount = 12,
      bucketId = "screenshots",
      folderType = MediaFolder.FolderType.NORMAL
    )

    private val MEDIA: List<Media> = (0 until 12).map { index ->
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
        fileName = "media_$index.jpg"
      )
    }
  }
}
