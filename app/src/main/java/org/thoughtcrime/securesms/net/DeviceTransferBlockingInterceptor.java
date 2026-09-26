package org.thoughtcrime.securesms.net;

import androidx.annotation.NonNull;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Provide a way to block network access while performing a device transfer.
 */
public final class DeviceTransferBlockingInterceptor implements Interceptor {

  private static final String TAG = Log.tag(DeviceTransferBlockingInterceptor.class);

  private static final DeviceTransferBlockingInterceptor INSTANCE = new DeviceTransferBlockingInterceptor();

  private volatile boolean blockNetworking;

  public static DeviceTransferBlockingInterceptor getInstance() {
    return INSTANCE;
  }

  public DeviceTransferBlockingInterceptor() {
    this.blockNetworking = SignalStore.misc().isOldDeviceTransferLocked();
  }

  @Override
  public @NonNull Response intercept(@NonNull Chain chain) throws IOException {
    if (!isBlockingNetwork()) {
      return chain.proceed(chain.request());
    }

    Log.w(TAG, blockNetworking ? "Preventing request because in transfer mode."
                               : "Preventing request because cross-border consent has not been given yet.");
    return new Response.Builder().request(chain.request())
                                 .protocol(Protocol.HTTP_1_1)
                                 .receivedResponseAtMillis(System.currentTimeMillis())
                                 .message("")
                                 .body(ResponseBody.create(null, ""))
                                 .code(555)
                                 .build();
  }

  /**
   * Tellomi：跨境同意之前也拦（tellomi/tellomi#1133，见 {@link TellomiCrossBorderNetworkGate}）。
   * 两条 websocket 的 canConnect 和服务端的 OkHttp 请求都经过这里。
   */
  public boolean isBlockingNetwork() {
    return blockNetworking || TellomiCrossBorderNetworkGate.isBlocking();
  }

  public void blockNetwork() {
    blockNetworking = true;
    AppDependencies.resetNetwork();
  }

  public void unblockNetwork() {
    blockNetworking = false;
    AppDependencies.resetNetwork();
  }
}
