package org.thoughtcrime.securesms.net;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.conscrypt.ConscryptSignal;
import org.signal.core.util.logging.Log;
import org.signal.network.util.HttpsProxySocketFactory;
import org.thoughtcrime.securesms.BuildConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

public class ContentProxySelector extends ProxySelector {

  private static final String TAG = Log.tag(ContentProxySelector.class);

  public static final Set<String> WHITELISTED_DOMAINS = new HashSet<>();
  static {
    WHITELISTED_DOMAINS.add("giphy.com");
  }

  private final List<Proxy> CONTENT = new ArrayList<Proxy>(1) {{
    add(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(BuildConfig.CONTENT_PROXY_HOST,
                                                                      BuildConfig.CONTENT_PROXY_PORT)));
  }};

  private static volatile InnerTls innerTls;

  /**
   * Tellomi（#1078）：把一个 OkHttpClient.Builder 配成「经内容代理，而且和代理之间**先做 TLS，再在里面发 CONNECT**」。
   * 所有走内容代理的 client 都从这里配，别再各自 {@code .proxySelector(new ContentProxySelector())}——
   * 下面三件事少一件都不行。
   *
   * 1. {@code proxySelector}：白名单内一律走代理，白名单外直接抛异常，**从不返回 DIRECT**。
   * 2. {@code socketFactory}：{@link HttpsProxySocketFactory}，在 connect 那一刻和代理做 TLS（SNI 与证书都按
   *    {@code CONTENT_PROXY_HOST}）。上游只设 {@code Proxy.Type.HTTP}，对 443 端口发的是**明文** CONNECT，
   *    目标主机名（api.giphy.com）和隧道里 TLS 的 SNI 都在明文里——从大陆出发会被墙 reset，换成我们自己的代理也一样。
   *    香港那台的 nginx 按 SNI 分流：SNI = contentproxy.tellomi.app 的进 4444 终结 TLS 再交给 tinyproxy。
   *    2026-09-23 大陆直连实测（OkHttp 5.3.2）：明文 → api.giphy.com {@code Connection reset}；外层 TLS →
   *    CONNECT 200、Giphy 401（没带 key，符合预期）；白名单外的 example.com 两种都是 CONNECT 403。
   *    这个工厂永远连代理主机，只因为第 1 条保证了每个请求都走代理，这样用才是对的。
   * 3. {@code sslSocketFactory}：OkHttp 在隧道里对目标做的那层 TLS，是叠在第 2 条给的 socket（一个 Socket 子类）上的。
   *    这里给它一个开了 engine 模式的 Conscrypt 工厂——上游对自己的 TLS 代理 socket 也是这么做的，只是开在**全局**
   *    （ApplicationContext 的 proxy-init、SignalProxyUtil.enableProxy）；这里只对内容代理这几个 client 开，不影响别的连接。
   *    **2026-09-23 在 Android 16 模拟器上实测：不开也能通**（红控制没能复现失败：两种都是 HTTP 401、里层 TLS 1.3）。
   *    保留它是保险：Conscrypt 选 FileDescriptor 还是 engine 实现，取决于能不能反射到 socket 的文件描述符，
   *    这在旧版本 Android（minSdk 23）上没有实测过。
   */
  public static @NonNull OkHttpClient.Builder configure(@NonNull OkHttpClient.Builder builder) {
    builder.proxySelector(new ContentProxySelector())
           .socketFactory(new HttpsProxySocketFactory(BuildConfig.CONTENT_PROXY_HOST));

    InnerTls inner = innerTls();
    if (inner != null) {
      builder.sslSocketFactory(inner.socketFactory, inner.trustManager);
    }

    return builder;
  }

  /** 和 OkHttp 默认的构造方式一致（平台默认 TrustManager + {@code SSLContext.getInstance("TLS")}），只多开 engine 模式。 */
  private static @Nullable InnerTls innerTls() {
    InnerTls result = innerTls;
    if (result != null) {
      return result;
    }

    synchronized (ContentProxySelector.class) {
      if (innerTls == null) {
        try {
          TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
          trustManagerFactory.init((KeyStore) null);
          X509TrustManager trustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];

          SSLContext context = SSLContext.getInstance("TLS");
          context.init(null, new TrustManager[] { trustManager }, null);

          SSLSocketFactory socketFactory = context.getSocketFactory();
          if (ConscryptSignal.isConscrypt(socketFactory)) {
            ConscryptSignal.setUseEngineSocket(socketFactory, true);
          } else {
            Log.w(TAG, "TLS provider is not our Conscrypt (" + socketFactory.getClass().getName() + "); TLS inside the content proxy tunnel may fail.");
          }

          innerTls = new InnerTls(socketFactory, trustManager);
        } catch (GeneralSecurityException | ClassCastException e) {
          Log.w(TAG, "Could not build the TLS factory for the content proxy tunnel.", e);
          return null;
        }
      }
      return innerTls;
    }
  }

  @Override
  public List<Proxy> select(URI uri) {
    String host = uri.getHost();
    if (host != null) {
      for (String domain : WHITELISTED_DOMAINS) {
        if (host.equals(domain) || host.endsWith("." + domain)) {
          return CONTENT;
        }
      }
    }
    throw new IllegalArgumentException("Tried to proxy a non-whitelisted domain.");
  }

  @Override
  public void connectFailed(URI uri, SocketAddress address, IOException failure) {
    if (failure instanceof SocketException) {
      Log.d(TAG, "Socket exception. Likely a cancellation.");
    } else {
      Log.w(TAG, "Connection failed.", failure);
    }
  }

  private static final class InnerTls {
    final SSLSocketFactory socketFactory;
    final X509TrustManager trustManager;

    InnerTls(@NonNull SSLSocketFactory socketFactory, @NonNull X509TrustManager trustManager) {
      this.socketFactory = socketFactory;
      this.trustManager  = trustManager;
    }
  }
}
