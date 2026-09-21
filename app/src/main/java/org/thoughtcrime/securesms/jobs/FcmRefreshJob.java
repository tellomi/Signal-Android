/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.gcm.FcmUtil;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.messages.IncomingMessageObserver;
import org.thoughtcrime.securesms.net.SignalNetwork;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.signal.core.util.PlayServicesUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.RequestResultUtil;
import org.signal.network.exceptions.NonSuccessfulResponseCodeException;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class FcmRefreshJob extends BaseJob {

  public static final String KEY = "FcmRefreshJob";

  private static final String TAG = Log.tag(FcmRefreshJob.class);

  public FcmRefreshJob() {
    this(new Job.Parameters.Builder()
                           .setQueue("FcmRefreshJob")
                           .addConstraint(NetworkConstraint.KEY)
                           .setMaxAttempts(3)
                           .setLifespan(TimeUnit.HOURS.toMillis(6))
                           .setMaxInstancesForFactory(1)
                           .build());
  }

  private FcmRefreshJob(@NonNull Job.Parameters parameters) {
    super(parameters);
  }

  @Override
  public @Nullable byte[] serialize() {
    return null;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws Exception {
    if (TextSecurePreferences.isUnauthorizedReceived(context)) {
      Log.i(TAG, "No longer authorized. Ignoring.");
      return;
    }

    Log.i(TAG, "Reregistering FCM...");

    boolean playServicesMissing = PlayServicesUtil.getPlayServicesStatus(context) == PlayServicesUtil.PlayServicesStatus.MISSING ;
    if (playServicesMissing) {
      Log.w(TAG, "Play Services are unavailable.");
    }

    Optional<String> token = FcmUtil.getToken(context);

    if (token.isPresent()) {
      if (playServicesMissing) {
        Log.w(TAG, "We were able to get a token despite Play Services being missing!");
      }
      
      String oldToken = SignalStore.account().getFcmToken();

      if (!token.get().equals(oldToken)) {
        int oldLength = oldToken != null ? oldToken.length() : -1;
        Log.i(TAG, "Token changed. oldLength: " + oldLength + "  newLength: " + token.get().length());
      } else {
        Log.i(TAG, "Token didn't change.");
      }

      RequestResultUtil.successOrThrowNoError(SignalNetwork.account().setFcmToken(token.get()));
      SignalStore.account().setFcmToken(token.get());

      if (!SignalStore.account().isFcmEnabled()) {
        Log.w(TAG, "We had no Play Services, but were still able to get an FCM token! Re-enabling.");
        SignalStore.account().setFcmEnabled(true);
        AppDependencies.getJobManager().add(new RefreshAttributesJob());
        AppDependencies.resetNetwork();
        AppDependencies.startNetwork();
        IncomingMessageObserver.stopForegroundService(context);
      }

      if (SignalStore.settings().getForceWebsocketMode() == ForceWebsocketMode.ENABLED_AUTOMATICALLY) {
        Log.i(TAG, "FCM succeeded while in auto-enabled websocket mode. Reverting to disabled.");
        SignalStore.settings().setForceWebsocketMode(ForceWebsocketMode.DISABLED);
        IncomingMessageObserver.stopForegroundService(context);
        AppDependencies.resetNetwork();
        AppDependencies.startNetwork();
      }
    } else {
      throw new RetryLaterException(new IOException("Failed to retrieve a token."));
    }
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "FCM reregistration failed after retry attempt exhaustion!");

    PlayServicesUtil.PlayServicesStatus status = PlayServicesUtil.getPlayServicesStatus(context);

    if (status == PlayServicesUtil.PlayServicesStatus.MISSING) {
      Log.w(TAG, "This was a check where we tried to get a token despite having no Play Services. We failed. Marking down the time.");
      SignalStore.misc().setLastMissingPlayServicesFcmVerificationTime(System.currentTimeMillis());

      if (SignalStore.account().isFcmEnabled()) {
        Log.w(TAG, "Play Services are no longer available, and we failed to fetch a token. Disabling FCM.");
        SignalStore.account().setFcmEnabled(false);
        SignalStore.account().setFcmToken(null);
        AppDependencies.getJobManager().add(new RefreshAttributesJob());
        AppDependencies.resetNetwork();
        AppDependencies.startNetwork();
      }
    } else if (status == PlayServicesUtil.PlayServicesStatus.SUCCESS &&
               SignalStore.settings().getForceWebsocketMode() == ForceWebsocketMode.DISABLED &&
               (neverHadAnFcmToken() ||
                System.currentTimeMillis() - SignalStore.account().getFcmTokenLastSetTime() > TimeUnit.DAYS.toMillis(3)))
    {
      // Tellomi：多了 neverHadAnFcmToken() 这个条件。上游只看「连续失败超过 3 天」，那是为了
      // 避免 FCM 偶发抖动时来回切换——合理。但它把两种情况混成了一种：
      //   「以前能用、现在坏了」→ 等几天再说，对；
      //   「从来就没成功过」  → 再等三天也不会好，用户这三天一条后台消息都收不到。
      // 大陆的手机装着 GMS 但连不上 Google，永远是后一种。所以「从没拿到过 token」直接切。
      Log.w(TAG, "FCM has never worked (or has been failing for over 3 days) despite Play Services being available. Auto-enabling forced websocket mode so the user can still get messages.");
      SignalStore.settings().setForceWebsocketMode(ForceWebsocketMode.ENABLED_AUTOMATICALLY);
      AppDependencies.resetNetwork();
      AppDependencies.startNetwork();
    }
  }

  /**
   * Tellomi：这台设备**从来没有成功拿到过 FCM token**。
   *
   * 与「拿到过、现在失效了」要区别对待：后者可能是偶发抖动，值得等；前者等多久都不会好
   * （典型就是大陆装着 GMS 但连不上 Google 的手机）。
   */
  private boolean neverHadAnFcmToken() {
    return SignalStore.account().getFcmToken() == null && SignalStore.account().getFcmTokenLastSetTime() == 0;
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception throwable) {
    if (throwable instanceof NonSuccessfulResponseCodeException) return false;
    return true;
  }

  public static final class Factory implements Job.Factory<FcmRefreshJob> {
    @Override
    public @NonNull FcmRefreshJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new FcmRefreshJob(parameters);
    }
  }
}
