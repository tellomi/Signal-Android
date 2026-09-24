package org.thoughtcrime.securesms.net;

import android.os.Build;

import org.thoughtcrime.securesms.BuildConfig;

/**
 * The user agent that should be used by default -- includes app name, version, etc.
 */
public class StandardUserAgentInterceptor extends UserAgentInterceptor {

  /**
   * Tellomi（tellomi/tellomi#1137，需求 app-update-and-version-policy 3.3）：版本号后面用「+」带上构建号（versionCode），
   * 例如 {@code Signal-Android/0.1.2+175101 Android/34}。同一个 versionName 的热修包 versionCode 不同，服务端的
   * {@code remoteDeprecation.blockedVersions} 按整串比较，就能点名拦住某一个构建；{@code minimumVersions} 比大小不看「+」后面，
   * 含义和原来一样。格式三端统一，由服务端（taishi）定。
   */
  public static final String USER_AGENT = "Signal-Android/" + BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE + " Android/" + Build.VERSION.SDK_INT;

  public StandardUserAgentInterceptor() {
    super(USER_AGENT);
  }
}
