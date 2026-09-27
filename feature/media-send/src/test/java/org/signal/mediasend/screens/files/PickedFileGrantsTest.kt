/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tellomi（tellomi/tellomi#1121 F-4）：Sheet 替系统选择器挑的文件拿了持久读授权（[PickedFileGrants.take]，用例在
 * [AttachmentFilesScreenTest]），会话页发完要放掉——不然每发一次就多占一个系统给 App 的持久授权名额。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class PickedFileGrantsTest {

  private val contentResolver = ApplicationProvider.getApplicationContext<Application>().contentResolver

  @Test
  fun `release drops the persisted read grant of every picked file and skips one it never had`() {
    val picked = listOf("content://docs/a.pdf".toUri(), "content://docs/b.zip".toUri())
    picked.forEach { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    assertThat(contentResolver.persistedUriPermissions.map { it.uri }.toSet()).isEqualTo(picked.toSet())

    PickedFileGrants.release(contentResolver, picked + "content://docs/never-granted.txt".toUri())

    assertThat(contentResolver.persistedUriPermissions).isEmpty()
  }
}
