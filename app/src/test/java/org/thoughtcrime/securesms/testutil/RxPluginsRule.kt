/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.testutil

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.rules.ExternalResource

/**
 * Sets up RxJava / RxAndroid scheduler overrides.
 *
 * Tellomi：只装运行时的 handler，不装 `setInit*SchedulerHandler`。init handler 只在 `Schedulers` / `AndroidSchedulers`
 * 这个类第一次初始化时问一次，返回的调度器就永久成了这个类加载器（同一个 Robolectric 沙箱 / 同一个测试进程）里的默认值，
 * [RxJavaPlugins.reset] 也改不回来。本规则要是恰好赶上那一刻，后面同一进程里用真 `Schedulers.io()` 的测试拿到的就是
 * 这里没人推进的 TestScheduler，`blockingGet()` 永远等下去（全量跑时 `UsernameQrScanRepositoryTest` 就是这样挂住的）。
 * 运行时 handler 在规则生效期间已经把每次 `Schedulers.io()` / `AndroidSchedulers.mainThread()` 都导到这里，init 那份用不上。
 * 被测代码碰 `AndroidSchedulers` 时要有真的主线程 Looper，即 Robolectric 测试。
 */
class RxPluginsRule(
  val defaultScheduler: TestScheduler = TestScheduler(),
  val computationScheduler: TestScheduler = defaultScheduler,
  val ioScheduler: TestScheduler = defaultScheduler,
  val singleScheduler: TestScheduler = defaultScheduler,
  val newThreadScheduler: TestScheduler = defaultScheduler,
  val mainThreadScheduler: TestScheduler = defaultScheduler
) : ExternalResource() {

  override fun before() {
    RxJavaPlugins.setComputationSchedulerHandler { computationScheduler }

    RxJavaPlugins.setIoSchedulerHandler { ioScheduler }

    RxJavaPlugins.setSingleSchedulerHandler { singleScheduler }

    RxJavaPlugins.setNewThreadSchedulerHandler { newThreadScheduler }

    RxAndroidPlugins.setMainThreadSchedulerHandler { mainThreadScheduler }
  }

  override fun after() {
    RxJavaPlugins.reset()
    RxAndroidPlugins.reset()
  }
}
