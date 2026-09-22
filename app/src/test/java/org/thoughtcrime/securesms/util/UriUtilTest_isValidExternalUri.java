/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util;

import android.app.Application;
import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.thoughtcrime.securesms.BuildConfig;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class)
public class UriUtilTest_isValidExternalUri {

  private final String  input;
  private final boolean output;

  // Tellomi：原来这里写死的是上游包名 org.thoughtcrime.securesms，而我们的 applicationId 是
  // app.tellomi。被测的 UriUtil.isValidExternalUri 比的是**运行时包名**（context.packageName），
  // 所以改名之后这批用例构造的 URI 变成了「别人家的包」，被正确判成外部 URI → 9 条全红。
  //
  // 生产代码没有问题（它本来就与包名无关）；错的是测试把包名焊死了。
  // 改成从运行时取，下次再改包名也不会失效。
  // 不能用 ApplicationProvider.getApplicationContext()：这是**静态初始化**，跑在 Robolectric
  // 把环境搭起来之前，会 ExceptionInInitializerError（我第一版就是这么写的，9 条红变成
  // initializationError 1 条红）。BuildConfig.APPLICATION_ID 是编译期常量，随包名走，没有这个问题。
  private static final String APPLICATION_ID = BuildConfig.APPLICATION_ID;

  @ParameterizedRobolectricTestRunner.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][]{
        { "content://other.app.package.name.org/path/public.txt",             true  },
        { "content://" + APPLICATION_ID + ".part/part/42",                   false },
        { "content://" + APPLICATION_ID + ".blob/blob/42",                   false },
        { "content://" + APPLICATION_ID + ".avatar/avatar/42",               false },
        { "content://" + APPLICATION_ID + ".fileprovider/external_files/f",  false },
        { "content://0@" + APPLICATION_ID + ".part/part/42",                 false },
        { "content://10@" + APPLICATION_ID + ".blob/blob/42",                false },
        { "content://0@0@" + APPLICATION_ID + ".part/part/42",               false },
        { "content://0@other.app.package.name.org/path/public.txt",           true  },
        { "file:///sdcard/public.txt",                                        true  },
        {"file:///data/data/" + APPLICATION_ID + "/private.txt",              false },
        {"file:///any/path/with/package/name/" + APPLICATION_ID,              false },
        {"file:///" + APPLICATION_ID + "/any/path/with/package/name",         false },
        { "file:///any/path/../with/back/references/private.txt",             false },
        { "file:///any/path/with/back/references/../private.txt",             false },
        { "file:///../any/path/with/back/references/private.txt",             false },
        { "file:///encoded/back/reference/%2F..%2F..path%2Fto%2Fprivate.txt", false },
        { "file:///public/%2E%2E%2Fprivate%2Fprivate.txt",                    false },
        { "file:///data/no/paths/in/data",                                    false },
        { "file://",                                                          false },
    });
  }

  public UriUtilTest_isValidExternalUri(String input, boolean output) {
    this.input  = input;
    this.output = output;
  }

  @Test
  public void parse() {
    Context context = ApplicationProvider.getApplicationContext();
    Uri     uri     = Uri.parse(input);

    assertEquals(output, UriUtil.isValidExternalUri(context, uri));
  }
}
