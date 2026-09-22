package org.thoughtcrime.securesms.gcm;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.signal.core.util.PlayServicesUtil;
import org.signal.core.util.logging.Log;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class FcmUtil {

  private static final String TAG = Log.tag(FcmUtil.class);

  /**
   * Tellomi：上游这里是没有上限的 {@code Tasks.await(...)}。FCM 拿不到 Google 时它不会失败也不会返回，
   * 注册流程会永远停在转圈上（本机 Android 模拟器实测：点「下一步」之后 10 分钟一条日志都没有，
   * 因为它卡在这一行）。我们的主战场是大陆，"连不上 Google" 是常态而不是异常，
   * 所以这里必须有上限——拿不到就当没有 FCM 往下走（注册流程本来就支持无 FCM 的 SMS 路径）。
   */
  private static final long TOKEN_TIMEOUT_SECONDS = 15;

  /**
   * Retrieves the current FCM token. If one isn't available, it'll be generated.
   */
  @WorkerThread
  public static Optional<String> getToken(Context context) {
    String token = null;

    // Tellomi（#952）：没装 GMS 的机器上，下面那个 await 一定会走满 15 秒超时——
    // 不是"可能拿不到"，是**结构上不可能拿到**。注册流程里会取两次 token
    // （创建会话一次、注册账号一次），于是无 GMS 用户凭空多等 30 秒，界面上没有任何解释。
    // 先问一次 Play Services 在不在，MISSING 就直接当作没有 FCM 返回。
    // 只短路 MISSING：NEEDS_UPDATE / TRANSIENT_ERROR 仍然照常去取，因为那两种情况真有可能拿到。
    if (PlayServicesUtil.getPlayServicesStatus(context) == PlayServicesUtil.PlayServicesStatus.MISSING) {
      Log.i(TAG, "No Play Services; skipping the " + TOKEN_TIMEOUT_SECONDS + "s token wait entirely.");
      return Optional.empty();
    }

    // Must be called manually if running outside of main process
    FirebaseApp.initializeApp(context);

    try {
      token = Tasks.await(FirebaseMessaging.getInstance().getToken(), TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Log.w(TAG, "Was interrupted while waiting for the token.");
    } catch (ExecutionException e) {
      Log.w(TAG, "Failed to get the token.", e.getCause());
    } catch (TimeoutException e) {
      Log.w(TAG, "Timed out after " + TOKEN_TIMEOUT_SECONDS + "s waiting for the token; continuing without FCM.");
    }

    return Optional.ofNullable(TextUtils.isEmpty(token) ? null : token);
  }
}
