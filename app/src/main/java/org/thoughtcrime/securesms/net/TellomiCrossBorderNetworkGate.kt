/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.net

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.signal.registration.screens.shared.TellomiCrossBorderConsent
import org.signal.registration.screens.shared.TellomiCrossBorderNotice
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.Environment

/**
 * Tellomi：跨境同意之前一个网络请求都不发（tellomi/tellomi#1133；需求 `docs/product/specs/privacy-compliance-hk-cross-border.md` 2.1 / 2.7）。
 *
 * 实测改之前：模拟器全新安装、首次启动提示还没点，启动 2 秒内就连了 grpc.chat / cdn / updates.tellomi.app
 * （未注册的聊天连接 + 11 个默认贴纸包下载任务），一直开着（#1133 的评论）。
 *
 * 闸放在上游已有的「设备转移时断网」那道闸上：[DeviceTransferBlockingInterceptor] 同时管两条 websocket 的 canConnect
 * 和服务端的 OkHttp 请求；再让 NetworkConstraint 等同意——不然只跑一次的任务（首次安装下载默认贴纸包）会直接失败。
 * 同意后重建网络、唤醒任务。已注册但还没同意的人（升级上来的），任何界面一出来就先盖上跨境告知。
 */
object TellomiCrossBorderNetworkGate {

  private val TAG = Log.tag(TellomiCrossBorderNetworkGate::class.java)
  private const val OVERLAY_TAG = "tellomi-cross-border-consent"

  /**
   * 还没同意跨境就拦。仪器测试里不拦（测试有自己的假网络）；依赖还没初始化时也不拦——那时候什么网络都还没开始。
   */
  @JvmStatic
  fun isBlocking(): Boolean {
    return AppDependencies.isInitialized &&
      !Environment.IS_INSTRUMENTATION &&
      !TellomiCrossBorderConsent.hasAgreed(AppDependencies.application)
  }

  @JvmStatic
  fun install(application: Application) {
    TellomiCrossBorderConsent.onAgreed = {
      Log.i(TAG, "Cross-border consent given; restarting network.")
      AppDependencies.resetNetwork()
      AppDependencies.startNetwork()
      AppDependencies.jobManager.onConstraintMet("TellomiCrossBorderConsent")
    }

    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityResumed(activity: Activity) = showNoticeIfNeeded(activity)
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
      override fun onActivityStarted(activity: Activity) = Unit
      override fun onActivityPaused(activity: Activity) = Unit
      override fun onActivityStopped(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
      override fun onActivityDestroyed(activity: Activity) = Unit
    })
  }

  /** 未注册的人在注册流程里单独问（号码页确认之前、欢迎页的恢复 / 关联设备）；这里只管已注册但没同意的。 */
  private fun showNoticeIfNeeded(activity: Activity) {
    if (activity !is ComponentActivity || !SignalStore.account.isRegistered || !isBlocking()) {
      return
    }

    val decorView = activity.window.decorView as ViewGroup
    if (decorView.findViewWithTag<View>(OVERLAY_TAG) != null) {
      return
    }

    Log.i(TAG, "Registered but no cross-border consent yet; showing the notice.")
    val overlay = ComposeView(activity)
    overlay.tag = OVERLAY_TAG
    overlay.setContent {
      SignalTheme {
        TellomiCrossBorderNotice(
          onAgree = {
            TellomiCrossBorderConsent.recordAgreement(activity)
            decorView.removeView(overlay)
          },
          // 已注册的人不同意就用不了 Tellomi：返回键不关这一页。
          onCancel = {}
        )
      }
    }
    decorView.addView(overlay)
  }
}
