package org.thoughtcrime.securesms.util.rx

import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test

class RxStoreTest {

  private val testScheduler = TestScheduler()

  @Before
  fun setUp() {
    // Tellomi：不装 RxJava 的 init handler，免得这里的 TestScheduler 永久留成同一测试进程里的默认调度器（见 RxPluginsRule 的说明）
    RxJavaPlugins.setComputationSchedulerHandler { testScheduler }
  }

  @After
  fun tearDown() {
    RxJavaPlugins.reset()
  }

  @Test
  fun `Given an initial state, when I observe, then I expect my initial state`() {
    // GIVEN
    val testSubject = RxStore(1)

    // WHEN
    val subscriber = testSubject.stateFlowable.test()
    testScheduler.triggerActions()

    // THEN
    subscriber.assertValueAt(0, 1)
    subscriber.assertNotComplete()
    testSubject.dispose()
  }

  @Test
  fun `Given immediate observation, when I update, then I expect both states`() {
    // GIVEN
    val testSubject = RxStore(1)

    // WHEN
    val subscriber = testSubject.stateFlowable.test()
    testSubject.update { 2 }

    testScheduler.triggerActions()

    // THEN
    subscriber.assertValueAt(0, 1)
    subscriber.assertValueAt(1, 2)
    subscriber.assertNotComplete()
    testSubject.dispose()
  }

  @Test
  fun `Given late observation after several updates, when I observe, then I expect latest state`() {
    // GIVEN
    val testSubject = RxStore(1)
    testSubject.update { 2 }

    // WHEN
    testScheduler.triggerActions()
    val subscriber = testSubject.stateFlowable.test()
    testScheduler.triggerActions()

    // THEN
    subscriber.assertValueAt(0, 2)
    subscriber.assertNotComplete()
    testSubject.dispose()
  }
}
