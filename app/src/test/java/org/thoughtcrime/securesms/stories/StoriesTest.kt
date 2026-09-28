package org.thoughtcrime.securesms.stories

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.signal.core.models.database.AttachmentId
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob

class StoriesTest {
  private val testScheduler = TestScheduler()
  private val mockJobManager = mockk<JobManager> {
    every { add(any()) } just runs
  }
  private val mockAttachmentTable = mockk<AttachmentTable>()
  private val mockSignalDatabase = mockk<SignalDatabase>()

  @Before
  fun setUp() {
    mockkStatic(AppDependencies::class)

    // Tellomi：不装 RxJava 的 init handler，免得这里的 TestScheduler 永久留成同一测试进程里的默认调度器（见 RxPluginsRule 的说明）
    RxJavaPlugins.setIoSchedulerHandler { testScheduler }

    SignalDatabase.setSignalDatabaseInstanceForTesting(mockSignalDatabase)
    every { SignalDatabase.attachments } returns mockAttachmentTable
    every { AppDependencies.jobManager } returns mockJobManager
    every { mockAttachmentTable.getAttachmentsForMessage(any()) } returns emptyList()
  }

  @After
  fun tearDown() {
    RxJavaPlugins.reset()
    unmockkAll()
  }

  @Test
  fun `Given a MessageRecord with no attachments and a LinkPreview without a thumbnail, when I enqueueAttachmentsFromStoryForDownload, then I enqueue nothing`() {
    // GIVEN
    val messageRecord = FakeMessageRecords.buildMediaMmsMessageRecord(
      linkPreviews = listOf(FakeMessageRecords.buildLinkPreview())
    )

    // WHEN
    val testObserver = Stories.enqueueAttachmentsFromStoryForDownload(messageRecord, true).test()
    testScheduler.triggerActions()

    // THEN
    testObserver.assertComplete()
    verify(exactly = 0) { mockJobManager.add(any()) }
  }

  @Test
  fun `Given a MessageRecord with no attachments and a LinkPreview with a thumbnail, when I enqueueAttachmentsFromStoryForDownload, then I enqueue once`() {
    // GIVEN
    val messageRecord = FakeMessageRecords.buildMediaMmsMessageRecord(
      linkPreviews = listOf(
        FakeMessageRecords.buildLinkPreview(
          attachmentId = AttachmentId(1)
        )
      )
    )

    // WHEN
    val testObserver = Stories.enqueueAttachmentsFromStoryForDownload(messageRecord, true).test()
    testScheduler.triggerActions()

    // THEN
    testObserver.assertComplete()
    val slot = slot<AttachmentDownloadJob>()
    verify { mockJobManager.add(capture(slot)) }
  }

  @Test
  fun `Given a MessageRecord with an attachment, when I enqueueAttachmentsFromStoryForDownload, then I enqueue once`() {
    // GIVEN
    val attachment = FakeMessageRecords.buildDatabaseAttachment()
    val messageRecord = FakeMessageRecords.buildMediaMmsMessageRecord()
    every { mockAttachmentTable.getAttachmentsForMessage(any()) } returns listOf(attachment)

    // WHEN
    val testObserver = Stories.enqueueAttachmentsFromStoryForDownload(messageRecord, true).test()
    testScheduler.triggerActions()

    // THEN
    testObserver.assertComplete()
    val slot = slot<AttachmentDownloadJob>()
    verify { mockJobManager.add(capture(slot)) }
  }
}
