/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
 * Tellomi（tellomi/tellomi#1261 P-7）：「照片」只给了部分访问时，横幅在网格里、占第一格（整行），跟着网格滚走。
 * 长按拖动多选按格子下标算，横幅占掉的那一格要减掉，否则选中的会是手指下那张的下一张。
 * （替换上游选图页底部缩略条的用例：那条缩略条已由顶栏「✓N」取代。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaPickerLimitedAccessDragTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaSelectScreenEvents>()

  private val selected: Set<Media>
    get() = events.filterIsInstance<MediaSelectScreenEvents.MediaSelected>().flatMap { it.media }.toSet()

  @Test
  fun `Given limited access, when displayed, then the banner heads the grid`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_LIMITED_ACCESS).assertIsDisplayed()
    composeTestRule.onNodeWithText("You've limited Tellomi's access to your photos.").assertIsDisplayed()
    composeTestRule.onNodeWithText("Manage").assertIsDisplayed()
    assertEquals(TestTags.MEDIA_PICKER_LIMITED_ACCESS, gridChildTag(0))
  }

  @Test
  fun `Given the banner heads the grid, when a tile is long pressed, then that tile is selected`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(0))
      advanceEventTime(LONG_PRESS_MS)
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(setOf(MEDIA[0]), selected)
  }

  @Test
  fun `Given the banner heads the grid, when dragging across tiles, then exactly those tiles are selected`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(0))
      advanceEventTime(LONG_PRESS_MS)
      moveTo(tileCenter(1))
      moveTo(tileCenter(2))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(setOf(MEDIA[0], MEDIA[1], MEDIA[2]), selected)
  }

  private fun gridChildTag(index: Int): String? {
    val grid = composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).fetchSemanticsNode()
    return grid.children[index].config.getOrElseNullable(SemanticsProperties.TestTag) { null }
  }

  /** 第 [index] 张媒体的格子中心；网格的第 0 个孩子是横幅，媒体从第 1 个开始。 */
  private fun tileCenter(index: Int): Offset {
    val grid = composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).fetchSemanticsNode()
    val tile = grid.children[index + 1]
    val offset = tile.positionInRoot - grid.positionInRoot

    return Offset(offset.x + tile.size.width / 2f, offset.y + tile.size.height / 2f)
  }

  private fun setContent() {
    composeTestRule.setContent {
      SignalTheme {
        Box(modifier = Modifier.size(GRID_WIDTH.dp, GRID_HEIGHT.dp)) {
          MediaSelectScreen(
            state = MediaSelectState.Files(
              selectedMediaFolder = FOLDER,
              selectedMediaFolderItems = MEDIA,
              selectedMedia = emptyList(),
              mediaPermissions = MediaPermissions.PARTIAL
            ),
            onEvent = { events += it }
          )
        }
      }
    }
    composeTestRule.waitForIdle()
  }

  private companion object {
    private const val LONG_PRESS_MS = 600L
    private const val GRID_WIDTH = 400f
    private const val GRID_HEIGHT = 800f

    private val FOLDER = MediaFolder(
      thumbnailUri = "content://folder".toUri(),
      title = "All media",
      itemCount = 8,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      folderType = MediaFolder.FolderType.NORMAL
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
        fileName = "media_$index.jpg"
      )
    }
  }
}
