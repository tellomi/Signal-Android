package org.thoughtcrime.securesms.net;

import android.os.Build;

import org.thoughtcrime.securesms.BuildConfig;

/**
 * The user agent that should be used by default -- includes app name, version, etc.
 */
public class StandardUserAgentInterceptor extends UserAgentInterceptor {

  /**
   * Tellomi（tellomi/tellomi#1137，需求 app-update-and-version-policy 3.3；格式由 taishi 在中转包 8 第〇节第 1 条定）：
   * 最后加一个 {@code Build/} 段带上构建号（versionCode），例如 {@code Signal-Android/0.1.2 Android/34 Build/175101}。
   * 版本号保持原样，服务端的 {@code minimumVersions} / {@code blockedVersions} 照旧只看它；同一个 versionName 的热修包
   * versionCode 不同，服务端要按构建号拦时，从附加段的 {@code Build/} 读。和 iOS 的 {@code Signal-iOS/0.1.0 iOS/26.0 Build/123} 同一个格式。
   */
  public static final String USER_AGENT = "Signal-Android/" + BuildConfig.VERSION_NAME + " Android/" + Build.VERSION.SDK_INT + " Build/" + BuildConfig.VERSION_CODE;

  public StandardUserAgentInterceptor() {
    super(USER_AGENT);
  }
}
