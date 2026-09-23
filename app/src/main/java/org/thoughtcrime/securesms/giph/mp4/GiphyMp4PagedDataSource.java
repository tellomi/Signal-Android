package org.thoughtcrime.securesms.giph.mp4;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.signal.paging.PagedDataSource;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.giph.model.GiphyImage;
import org.thoughtcrime.securesms.giph.model.GiphyResponse;
import org.thoughtcrime.securesms.net.ContentProxySelector;
import org.thoughtcrime.securesms.util.RemoteConfig;
import org.signal.core.util.JsonUtils;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Data source for GiphyImages.
 */
final class GiphyMp4PagedDataSource implements PagedDataSource<String, GiphyImage> {

  /**
   * Tellomi（#1078，ADR-0064 §4.4）：api_key 优先用服务端下发的那把，无值才回落编译期常量。
   *
   * **不能再做成 `static final`**：`static final` 在类加载时求值，那时 RemoteConfig 可能
   * 还没初始化，而且服务端换 key 之后（`hotSwappable = true`）这个进程再也拿不到新值——
   * 表现是「后台改了 key，用户还得杀进程重开」。每次取 URI 时现算，成本是拼一个字符串。
   *
   * 上游那把 key 是 **Signal 自己的**，我们一直在用它请求 GIPHY。
   */
  private static @NonNull Uri baseGiphyUri() {
    String apiKey = RemoteConfig.gifApiKey();
    if (TextUtils.isEmpty(apiKey)) {
      apiKey = BuildConfig.GIPHY_API_KEY;
    }
    return Uri.parse("https://api.giphy.com/v1/gifs/")
              .buildUpon()
              .appendQueryParameter("api_key", apiKey)
              .build();
  }

  private static @NonNull Uri trendingUri() {
    return baseGiphyUri().buildUpon().appendPath("trending").build();
  }

  private static @NonNull Uri searchUri() {
    return baseGiphyUri().buildUpon().appendPath("search").build();
  }


  private static final String TAG = Log.tag(GiphyMp4PagedDataSource.class);

  private final String       searchString;
  private final OkHttpClient client;

  GiphyMp4PagedDataSource(@Nullable String searchQuery) {
    this.searchString = Optional.ofNullable(searchQuery).map(String::trim).orElse("");
    this.client       = AppDependencies.getOkHttpClient().newBuilder().proxySelector(new ContentProxySelector()).build();
  }

  @Override
  public int size() {
    try {
      GiphyResponse response = performFetch(0, 1);

      return response.getPagination().getTotalCount();
    } catch (IOException | NullPointerException e) {
      Log.w(TAG, "Failed to get size", e);
      return 0;
    }
  }

  @Override
  public @NonNull List<GiphyImage> load(int start, int length, int totalSize, @NonNull CancellationSignal cancellationSignal) {
    try {
      Log.d(TAG, "Loading from " + start + " to " + (start + length));
      return new LinkedList<>(performFetch(start, length).getData());
    } catch (IOException | NullPointerException e) {
      Log.w(TAG, "Failed to load content", e);
      return new LinkedList<>();
    }
  }

  @Override
  public String getKey(@NonNull GiphyImage giphyImage) {
    return giphyImage.getGifUrl();
  }

  @Override
  public @Nullable GiphyImage load(String url) {
    throw new UnsupportedOperationException("Not implemented!");
  }

  private @NonNull GiphyResponse performFetch(int start, int length) throws IOException {
    String url;

    if (TextUtils.isEmpty(searchString)) url = getTrendingUrl(start, length);
    else                                 url = getSearchUrl(start, length, searchString);

    Request request = new Request.Builder().url(url).build();

    try (Response response = client.newCall(request).execute()) {

      if (!response.isSuccessful()) {
        throw new IOException("Unexpected code " + response);
      }

      if (response.body() == null) {
        throw new IOException("Response body was not present");
      }

      return JsonUtils.fromJson(response.body().byteStream(), GiphyResponse.class);
    }
  }

  private String getTrendingUrl(int start, int length) {
    return trendingUri().buildUpon()
                       .appendQueryParameter("offset", String.valueOf(start))
                       .appendQueryParameter("limit", String.valueOf(length))
                       .build()
                       .toString();
  }

  private String getSearchUrl(int start, int length, @NonNull String query) {
    return searchUri().buildUpon()
                     .appendQueryParameter("offset", String.valueOf(start))
                     .appendQueryParameter("limit", String.valueOf(length))
                     .appendQueryParameter("q", query)
                     .build()
                     .toString();
  }
}
