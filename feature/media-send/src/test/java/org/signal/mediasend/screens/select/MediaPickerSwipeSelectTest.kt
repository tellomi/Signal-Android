/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * Tellomi（tellomi/tellomi#1261 P-8）：在真的选图网格上横着滑，不用长按就连续多选；和上游的长按拖动多选、点按、滚动互不干扰。
 * 选中状态照 ViewModel 那样随事件更新（选上追加在末尾、取消就拿掉）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaPickerSwipeSelectTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaSelectScreenEvents>()
  private var selection by mutableStateOf(emptyList<Media>())

  @Test
  fun `When swiping right across a row without long pressing, then those tiles are selected in order`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(0))
      moveBy(Offset(4f, 1f))
      moveBy(Offset(12f, 1f))
      moveTo(tileCenter(1))
      moveTo(tileCenter(2))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(listOf(MEDIA[0], MEDIA[1], MEDIA[2]), selection)
    assertTrue("不是点按：没有进单张预览", events.none { it is MediaSelectScreenEvents.OpenMedia })
  }

  @Test
  fun `When the swipe turns upwards, then the grid does not scroll and the tile above is selected too`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(3))
      moveBy(Offset(12f, 0f))
      moveTo(tileCenter(4))
      moveTo(tileCenter(1))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(listOf(MEDIA[3], MEDIA[4], MEDIA[1]), selection)
    assertEquals("滑动选择期间网格不滚", 0f, gridScrollOffset(), 0.5f)
  }

  @Test
  fun `When the swipe starts on a selected tile, then the tiles passed over are unselected`() {
    selection = listOf(MEDIA[4], MEDIA[5], MEDIA[3])
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(5))
      moveBy(Offset(-12f, 0f))
      moveTo(tileCenter(4))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(listOf(MEDIA[3]), selection)
  }

  @Test
  fun `When dragging vertically, then nothing is selected`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(4))
      moveBy(Offset(2f, 40f))
      moveBy(Offset(80f, 40f))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(emptyList<Media>(), selection)
  }

  @Test
  fun `When the swipe starts within 44dp of the leading edge, then it is left to the back gesture`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(Offset(20.dp.toPx(), tileCenter(0).y))
      moveBy(Offset(12f, 0f))
      moveTo(tileCenter(1))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(emptyList<Media>(), selection)
  }

  @Test
  fun `When long pressing then dragging, then the upstream range selection handles it alone`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(0))
      advanceEventTime(LONG_PRESS_MS)
      moveTo(tileCenter(1))
      moveTo(tileCenter(2))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(setOf(MEDIA[0], MEDIA[1], MEDIA[2]), selection.toSet())
    assertTrue("长按拖动时横滑不插手（不会先选后取消）", events.none { it is MediaSelectScreenEvents.MediaUnselected })
  }

  @Test
  fun `When long pressing a selected tile then dragging, then only the upstream unselect happens`() {
    selection = listOf(MEDIA[0])
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(0))
      advanceEventTime(LONG_PRESS_MS)
      moveTo(tileCenter(1))
      moveTo(tileCenter(2))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals("长按已选的那张：上游取消它并就此停下，横滑不接着选", emptyList<Media>(), selection)
    assertTrue(events.none { it is MediaSelectScreenEvents.MediaSelected })
  }

  @Test
  fun `When a tile is tapped, then it still opens instead of being selected`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(1))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(emptyList<Media>(), selection)
    assertEquals(listOf(MediaSelectScreenEvents.OpenMedia(MEDIA[1])), events.filterIsInstance<MediaSelectScreenEvents.OpenMedia>())
  }

  private fun gridScrollOffset(): Float {
    val grid = composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).fetchSemanticsNode()
    return grid.config[SemanticsProperties.VerticalScrollAxisRange].value()
  }

  private fun tileCenter(index: Int): Offset {
    val grid = composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).fetchSemanticsNode()
    val tile = grid.children[index]
    val offset = tile.positionInRoot - grid.positionInRoot

    return Offset(offset.x + tile.size.width / 2f, offset.y + tile.size.height / 2f)
  }

  private fun onEvent(event: MediaSelectScreenEvents) {
    events += event
    when (event) {
      is MediaSelectScreenEvents.MediaSelected -> selection = selection + event.media.filterNot { media -> selection.any { it.uri == media.uri } }
      is MediaSelectScreenEvents.MediaUnselected -> selection = selection.filterNot { media -> event.media.any { it.uri == media.uri } }
      else -> Unit
    }
  }

  private fun setContent() {
    composeTestRule.setContent {
      SignalTheme {
        Box(modifier = Modifier.size(GRID_WIDTH.dp, GRID_HEIGHT.dp)) {
          MediaSelectScreen(
            state = MediaSelectState.Files(
              selectedMediaFolder = FOLDER,
              selectedMediaFolderItems = MEDIA,
              selectedMedia = selection,
              mediaPermissions = MediaPermissions.FULL
            ),
            onEvent = ::onEvent
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
      itemCount = 30,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      folderType = MediaFolder.FolderType.NORMAL
    )

    /** 30 张：10 行，比 800dp 高，网格能滚（「不滚」才有意义）。 */
    private val MEDIA: List<Media> = (0 until 30).map { index ->
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
