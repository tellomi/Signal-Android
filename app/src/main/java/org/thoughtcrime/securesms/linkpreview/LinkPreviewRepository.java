package org.thoughtcrime.securesms.linkpreview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Consumer;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.signal.core.util.Hex;
import org.signal.core.util.Result;
import org.signal.core.util.bitmaps.BitmapDecodingException;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.links.LinkRegistry;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.signal.ringrtc.CallLinkRootKey;
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroupJoinInfo;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.UriAttachment;
import org.thoughtcrime.securesms.calls.links.CallLinks;
import org.thoughtcrime.securesms.database.AttachmentTable;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.GroupRecord;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.v2.GroupInviteLinkUrl;
import org.thoughtcrime.securesms.jobs.AvatarGroupsV2DownloadJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil.OpenGraph;
import org.thoughtcrime.securesms.mms.PushMediaConstraints;
import org.thoughtcrime.securesms.net.CompositeRequestController;
import org.thoughtcrime.securesms.net.RequestController;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkCredentials;
import org.thoughtcrime.securesms.service.webrtc.links.ReadCallLinkResult;
import org.thoughtcrime.securesms.stickers.StickerRemoteUri;
import org.thoughtcrime.securesms.stickers.StickerUrl;
import org.thoughtcrime.securesms.util.AvatarUtil;
import org.thoughtcrime.securesms.util.ImageCompressionUtil;
import org.thoughtcrime.securesms.util.LinkUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.groupsv2.GroupLinkNotActiveException;
import org.whispersystems.signalservice.api.messages.SignalServiceStickerManifest;
import org.whispersystems.signalservice.api.messages.SignalServiceStickerManifest.StickerInfo;
import org.whispersystems.signalservice.api.util.OptionalUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import kotlin.Pair;

public class LinkPreviewRepository {

  private static final String TAG = Log.tag(LinkPreviewRepository.class);

  /**
   * Tellomi（ADR-0063 §4.4，tellomi/tellomi#1422）：第三方页面和预览图改由 {@link TellomiLinkFetcher} 抓，
   * 请求头、cookie、重定向、私网、超时、体积、每条链接的请求数都按抓取契约；上游这里自己建的 OkHttpClient 不再用。
   */
  private final TellomiLinkFetcher fetcher;

  /**
   * Tellomi（ADR-0063 §4.2 / §5.2）：注册表在的时候，抓什么、预览怎么拼由 rust/links 定（snapshot + Preview.rich），
   * 群、贴纸、通话链接仍用下面上游自己的查询；注册表加载不了就走上游原来的路。
   */
  private final TellomiLinkSender sender;

  public LinkPreviewRepository() {
    this(TellomiLinkFetcher.getDefault());
  }

  @VisibleForTesting
  LinkPreviewRepository(@NonNull TellomiLinkFetcher fetcher) {
    this.fetcher = fetcher;
    this.sender  = new TellomiLinkSender(fetcher,
                                         () -> SignalStore.tellomiLinks().getExpandShortLinks(),
                                         Locale::getDefault, // the in-app language sets the default (DynamicLanguageContextWrapper)
                                         new FirstPartyLookups());
  }

  public @NonNull Single<Result<LinkPreview, Error>> getLinkPreview(@NonNull String url) {
    return Single.<Result<LinkPreview, Error>>create(emitter -> {
      RequestController controller = getLinkPreview(AppDependencies.getApplication(),
                                                    url,
                                                    new Callback() {
                                                      @Override
                                                      public void onSuccess(@NonNull LinkPreview linkPreview) {
                                                        emitter.onSuccess(Result.success(linkPreview));
                                                      }

                                                      @Override
                                                      public void onError(@NonNull Error error) {
                                                        emitter.onSuccess(Result.failure(error));
                                                      }
                                                    });

      if (controller != null) {
        emitter.setCancellable(controller::cancel);
      }
    }).subscribeOn(Schedulers.io());
  }

  @Nullable RequestController getLinkPreview(@NonNull Context context,
                                             @NonNull String url,
                                             @NonNull Callback callback)
  {
    if (!SignalStore.settings().isLinkPreviewsEnabled()) {
      throw new IllegalStateException();
    }

    CompositeRequestController compositeController = new CompositeRequestController();

    if (!LinkUtil.isValidPreviewUrl(url)) {
      Log.w(TAG, "Tried to get a link preview for a non-whitelisted domain.");
      callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      return compositeController;
    }

    LinkRegistry registry = TellomiLinkRegistry.get();
    if (registry != null) {
      return fetchTellomiLinkPreview(registry, url, callback);
    }

    RequestController metadataController;

    if (StickerUrl.isValidShareLink(url)) {
      metadataController = fetchStickerPackLinkPreview(context, url, callback);
    } else if (GroupInviteLinkUrl.isGroupLink(url)) {
      metadataController = fetchGroupLinkPreview(context, url, callback);
    } else if (CallLinks.isCallLink(url)) {
      metadataController = fetchCallLinkPreview(context, url, callback);
    } else {
      TellomiLinkFetcher.Session session = fetcher.newSession();
      compositeController.addController(session::cancel);

      metadataController = fetchMetadata(session, url, metadata -> {
        if (metadata.isEmpty()) {
          callback.onError(Error.PREVIEW_NOT_AVAILABLE);
          return;
        }

        if (!metadata.getImageUrl().isPresent()) {
          callback.onSuccess(new LinkPreview(url, metadata.getTitle().orElse(""), metadata.getDescription().orElse(""), metadata.getDate(), Optional.empty()));
          return;
        }

        RequestController imageController = fetchThumbnail(session, metadata.getImageUrl().get(), attachment -> {
          if (!metadata.getTitle().isPresent() && !attachment.isPresent()) {
            callback.onError(Error.PREVIEW_NOT_AVAILABLE);
          } else {
            callback.onSuccess(new LinkPreview(url, metadata.getTitle().orElse(""), metadata.getDescription().orElse(""), metadata.getDate(), attachment));
          }
        });

        compositeController.addController(imageController);
      });
    }

    compositeController.addController(metadataController);
    return compositeController;
  }

  private @NonNull RequestController fetchTellomiLinkPreview(@NonNull LinkRegistry registry, @NonNull String url, @NonNull Callback callback) {
    TellomiLinkFetcher.Session session   = fetcher.newSession();
    AtomicBoolean              cancelled = new AtomicBoolean(false);

    SignalExecutors.UNBOUNDED.execute(() -> {
      TellomiLinkSender.Result result = sender.preview(registry, url, session, cancelled::get);

      if (result == null || cancelled.get()) {
        return;
      }
      if (result instanceof TellomiLinkSender.Result.Found) {
        callback.onSuccess(((TellomiLinkSender.Result.Found) result).getPreview());
      } else if (result instanceof TellomiLinkSender.Result.GroupLinkInactive) {
        callback.onError(Error.GROUP_LINK_INACTIVE);
      } else {
        callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      }
    });

    return () -> {
      cancelled.set(true);
      session.cancel();
    };
  }

  /** The job's `first_party` requests and its preview image, done the way this class already does them. */
  private static final class FirstPartyLookups implements TellomiLinkSender.Lookups {

    private static final long LOOKUP_TIMEOUT_MS = TellomiLinkSendJob.LINK_BUDGET_MS;

    @Override
    public @NonNull TellomiLinkSender.FirstParty firstParty(@NonNull String kind, @NonNull String url) {
      Context                                         context  = AppDependencies.getApplication();
      CompletableFuture<TellomiLinkSender.FirstParty> future   = new CompletableFuture<>();
      AtomicReference<Integer>                        count    = new AtomicReference<>();
      Callback                                        callback = new Callback() {
        @Override
        public void onTellomiCount(int value) {
          count.set(value);
        }

        @Override
        public void onSuccess(@NonNull LinkPreview linkPreview) {
          future.complete(new TellomiLinkSender.FirstParty.Found(linkPreview, count.get()));
        }

        @Override
        public void onError(@NonNull Error error) {
          future.complete(error == Error.GROUP_LINK_INACTIVE ? TellomiLinkSender.FirstParty.Inactive.INSTANCE
                                                             : TellomiLinkSender.FirstParty.NotFound.INSTANCE);
        }
      };

      switch (kind) {
        case "tellomi.group":
          if (!GroupInviteLinkUrl.isGroupLink(url)) return TellomiLinkSender.FirstParty.NotFound.INSTANCE;
          fetchGroupLinkPreview(context, url, callback);
          break;
        case "tellomi.sticker":
          if (!StickerUrl.isValidShareLink(url)) return TellomiLinkSender.FirstParty.NotFound.INSTANCE;
          fetchStickerPackLinkPreview(context, url, callback);
          break;
        case "tellomi.call":
          if (!CallLinks.isCallLink(url)) return TellomiLinkSender.FirstParty.NotFound.INSTANCE;
          fetchCallLinkPreview(context, url, callback);
          break;
        default:
          return TellomiLinkSender.FirstParty.NotFound.INSTANCE;
      }

      try {
        return future.get(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (ExecutionException | InterruptedException | TimeoutException e) {
        Log.w(TAG, "First-party lookup did not finish: " + e.getClass().getSimpleName());
        return TellomiLinkSender.FirstParty.NotFound.INSTANCE;
      }
    }

    @Override
    public @Nullable Attachment thumbnail(@NonNull byte[] bytes) {
      return thumbnailFromBytes(bytes).orElse(null);
    }
  }

  private @NonNull RequestController fetchMetadata(@NonNull TellomiLinkFetcher.Session session, @NonNull String url, Consumer<Metadata> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      // 失败的类别由抓取器记日志（不带 URL）；这里不再打异常，异常信息里可能有 URL（ADR-0063 §6.5）
      TellomiLinkFetcher.Result result = session.fetch(url, TellomiLinkFetcher.Step.HTML);

      if (result instanceof TellomiLinkFetcher.Result.Failure && ((TellomiLinkFetcher.Result.Failure) result).isDirectImage()) {
        // We've been linked directly to an image.
        okhttp3.HttpUrl imageUrl = Objects.requireNonNull(((TellomiLinkFetcher.Result.Failure) result).getFinalUrl());
        // The best we can do for a title is the filename in the URL itself,
        // but that's no worse than the body of the message.
        List<String> requestedUrlPathSegments = imageUrl.pathSegments();
        String       filename                 = requestedUrlPathSegments.get(requestedUrlPathSegments.size() - 1);
        callback.accept(new Metadata(Optional.of(filename), Optional.empty(), 0, Optional.of(imageUrl.toString())));
        return;
      }

      if (!(result instanceof TellomiLinkFetcher.Result.Body)) {
        callback.accept(Metadata.empty());
        return;
      }

      String           body        = ((TellomiLinkFetcher.Result.Body) result).text();
      OpenGraph        openGraph   = LinkPreviewUtil.parseOpenGraphFields(body);
      Optional<String> title       = openGraph.getTitle();
      Optional<String> description = openGraph.getDescription();
      Optional<String> imageUrl    = openGraph.getImageUrl();
      long             date        = openGraph.getDate();

      if (imageUrl.isPresent() && !LinkUtil.isValidPreviewUrl(imageUrl.get())) {
        Log.i(TAG, "Image URL was invalid or for a non-whitelisted domain. Skipping.");
        imageUrl = Optional.empty();
      }

      callback.accept(new Metadata(title, description, date, imageUrl));
    });

    return session::cancel;
  }

  private @NonNull RequestController fetchThumbnail(@NonNull TellomiLinkFetcher.Session session, @NonNull String imageUrl, @NonNull Consumer<Optional<Attachment>> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      TellomiLinkFetcher.Result result = session.fetch(imageUrl, TellomiLinkFetcher.Step.IMAGE);

      if (!(result instanceof TellomiLinkFetcher.Result.Body)) {
        callback.accept(Optional.empty());
        return;
      }

      callback.accept(thumbnailFromBytes(((TellomiLinkFetcher.Result.Body) result).getBytes()));
    });

    return session::cancel;
  }

  private static @NonNull Optional<Attachment> thumbnailFromBytes(@NonNull byte[] data) {
    try {
      Bitmap                           bitmap      = BitmapFactory.decodeByteArray(data, 0, data.length);
      Optional<Attachment>             thumbnail   = Optional.empty();
      PushMediaConstraints.MediaConfig mediaConfig = PushMediaConstraints.MediaConfig.getDefault(AppDependencies.getApplication());

      if (bitmap != null) {
        for (final int maxDimension : mediaConfig.getImageSizeTargets()) {
          ImageCompressionUtil.Result compressed = ImageCompressionUtil.compressWithinConstraints(
              AppDependencies.getApplication(),
              MediaUtil.IMAGE_JPEG,
              bitmap,
              maxDimension,
              mediaConfig.getMaxImageFileSize(),
              mediaConfig.getImageQualitySetting()
          );

          if (compressed != null) {
            thumbnail = Optional.of(bytesToAttachment(compressed.getData(), compressed.getWidth(), compressed.getHeight(), compressed.getMimeType()));
            break;
          }
        }
      }

      if (bitmap != null) bitmap.recycle();

      return thumbnail;
    } catch (IllegalArgumentException | BitmapDecodingException e) {
      Log.w(TAG, "Failed to decode the link preview image: " + e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static RequestController fetchStickerPackLinkPreview(@NonNull Context context,
                                                               @NonNull String packUrl,
                                                               @NonNull Callback callback)
  {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        Pair<String, String> stickerParams = StickerUrl.parseShareLink(packUrl).orElse(new Pair<>("", ""));
        String               packIdString  = stickerParams.getFirst();
        String               packKeyString = stickerParams.getSecond();
        byte[]               packIdBytes   = Hex.fromStringCondensed(packIdString);
        byte[]               packKeyBytes  = Hex.fromStringCondensed(packKeyString);

        SignalServiceMessageReceiver receiver = AppDependencies.getSignalServiceMessageReceiver();
        SignalServiceStickerManifest manifest = receiver.retrieveStickerManifest(packIdBytes, packKeyBytes);

        String                title        = OptionalUtil.or(manifest.getTitle(), manifest.getAuthor()).orElse("");
        Optional<StickerInfo> firstSticker = Optional.ofNullable(manifest.getStickers().size() > 0 ? manifest.getStickers().get(0) : null);
        Optional<StickerInfo> cover        = OptionalUtil.or(manifest.getCover(), firstSticker);

        if (cover.isPresent()) {
          Bitmap bitmap = Glide.with(context).asBitmap()
                                                .load(new StickerRemoteUri(packIdString, packKeyString, cover.get().getId()))
                                                .skipMemoryCache(true)
                                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                                .centerInside()
                                                .submit(512, 512)
                                                .get();

          Optional<Attachment> thumbnail = bitmapToAttachment(bitmap, Bitmap.CompressFormat.WEBP, MediaUtil.IMAGE_WEBP);

          callback.onTellomiCount(manifest.getStickers().size());
          callback.onSuccess(new LinkPreview(packUrl, title, "", 0, thumbnail));
        } else {
          callback.onError(Error.PREVIEW_NOT_AVAILABLE);
        }
      } catch (IOException | InvalidMessageException | ExecutionException | InterruptedException e) {
        Log.w(TAG, "Failed to fetch sticker pack link preview.");
        callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      }
    });

    return () -> Log.i(TAG, "Cancelled sticker pack link preview fetch -- no effect.");
  }

  private static RequestController fetchCallLinkPreview(@NonNull Context context,
                                                        @NonNull String callLinkUrl,
                                                        @NonNull Callback callback) {

    CallLinkRootKey callLinkRootKey = CallLinks.parseUrl(callLinkUrl);
    if (callLinkRootKey == null) {
      callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      return () -> { };
    }

    Disposable disposable = AppDependencies.getSignalCallManager()
                                           .getCallLinkManager()
                                           .readCallLink(new CallLinkCredentials(callLinkRootKey.getKeyBytes(), null))
                                           .observeOn(Schedulers.io())
                                           .subscribe(
                                                        result -> {
                                                          if (result instanceof ReadCallLinkResult.Success) {
                                                            ReadCallLinkResult.Success success = (ReadCallLinkResult.Success) result;
                                                            Log.i(TAG, "Successfully read call link.");

                                                            if (((ReadCallLinkResult.Success) result).getCallLinkState().hasBeenRevoked()) {
                                                              Log.i(TAG, "Call link has been revoked.");
                                                              callback.onError(Error.PREVIEW_NOT_AVAILABLE);
                                                              return;
                                                            }

                                                            // Note: thumbnails are generated recv-side using the CallLinkRootKey
                                                            callback.onSuccess(new LinkPreview(
                                                                callLinkUrl,
                                                                success.getCallLinkState().getName(),
                                                                "",
                                                                0,
                                                                Optional.empty()
                                                            ));
                                                          } else {
                                                            ReadCallLinkResult.Failure failure = (ReadCallLinkResult.Failure) result;
                                                            Log.w(TAG, "Failed to read call link: " + failure);
                                                            callback.onError(Error.PREVIEW_NOT_AVAILABLE);
                                                          }
                                                        },
                                                        error -> {
                                                          Log.w(TAG, "An error occurred: ", error);
                                                          callback.onError(Error.PREVIEW_NOT_AVAILABLE);
                                                        }
                                                    );

    return disposable::dispose;
  }

  private static RequestController fetchGroupLinkPreview(@NonNull Context context,
                                                         @NonNull String groupUrl,
                                                         @NonNull Callback callback)
  {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        GroupInviteLinkUrl groupInviteLinkUrl = GroupInviteLinkUrl.fromUri(groupUrl);
        if (groupInviteLinkUrl == null) {
          throw new AssertionError();
        }

        GroupMasterKey                      groupMasterKey = groupInviteLinkUrl.getGroupMasterKey();
        GroupId.V2            groupId = GroupId.v2(groupMasterKey);
        Optional<GroupRecord> group   = SignalDatabase.groups().getGroup(groupId);

        if (group.isPresent()) {
          Log.i(TAG, "Creating preview for locally available group");

          GroupRecord groupRecord = group.get();
          String      title       = groupRecord.getTitle();
          int                       memberCount = groupRecord.getMembers().size();
          String                    description = getMemberCountDescription(context, memberCount);
          Optional<Attachment>      thumbnail   = Optional.empty();

          if (AvatarHelper.hasAvatar(context, groupRecord.getRecipientId())) {
            Recipient recipient = Recipient.resolved(groupRecord.getRecipientId());
            Bitmap    bitmap    = AvatarUtil.loadIconBitmapSquareNoCache(context, recipient, 512, 512);

            thumbnail = bitmapToAttachment(bitmap, Bitmap.CompressFormat.WEBP, MediaUtil.IMAGE_WEBP);
          }

          callback.onTellomiCount(memberCount);
          callback.onSuccess(new LinkPreview(groupUrl, title, description, 0, thumbnail));
        } else {
          Log.i(TAG, "Group is not locally available for preview generation, fetching from server");

          DecryptedGroupJoinInfo joinInfo    = GroupManager.getGroupJoinInfoFromServer(context, groupMasterKey, groupInviteLinkUrl.getPassword());
          String                 description = getMemberCountDescription(context, joinInfo.memberCount);
          Optional<Attachment>   thumbnail   = Optional.empty();
          byte[]                 avatarBytes = AvatarGroupsV2DownloadJob.downloadGroupAvatarBytes(context, groupMasterKey, joinInfo.avatar);

          if (avatarBytes != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);

            thumbnail = bitmapToAttachment(bitmap, Bitmap.CompressFormat.WEBP, MediaUtil.IMAGE_WEBP);

            if (bitmap != null) bitmap.recycle();
          }

          callback.onTellomiCount(joinInfo.memberCount);
          callback.onSuccess(new LinkPreview(groupUrl, joinInfo.title, description, 0, thumbnail));
        }
      } catch (ExecutionException | InterruptedException | IOException | VerificationFailedException e) {
        Log.w(TAG, "Failed to fetch group link preview.", e);
        callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      } catch (GroupInviteLinkUrl.InvalidGroupLinkException | GroupInviteLinkUrl.UnknownGroupLinkVersionException e) {
        Log.w(TAG, "Bad group link.", e);
        callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      } catch (GroupLinkNotActiveException e) {
        Log.w(TAG, "Group link not active.", e);
        callback.onError(Error.GROUP_LINK_INACTIVE);
      }
    });

    return () -> Log.i(TAG, "Cancelled group link preview fetch -- no effect.");
  }

  private static @NonNull String getMemberCountDescription(@NonNull Context context, int memberCount) {
    return context.getResources()
                  .getQuantityString(R.plurals.LinkPreviewRepository_d_members,
                                     memberCount,
                                     memberCount);
  }

  private static Optional<Attachment> bitmapToAttachment(@Nullable Bitmap bitmap,
                                                         @NonNull Bitmap.CompressFormat format,
                                                         @NonNull String contentType)
  {
    if (bitmap == null) {
      return Optional.empty();
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    bitmap.compress(format, 80, baos);

    byte[] bytes = baos.toByteArray();
    return Optional.of(bytesToAttachment(bytes, bitmap.getWidth(), bitmap.getHeight(), contentType));
  }

  private static Attachment bytesToAttachment(byte[] bytes,
                                              int width,
                                              int height,
                                              @NonNull String contentType) {

    Uri uri = AppDependencies.getBlobs().forData(bytes).createForSingleSessionInMemory();

    return new UriAttachment(uri,
                             contentType,
                             AttachmentTable.TRANSFER_PROGRESS_STARTED,
                             bytes.length,
                             width,
                             height,
                             null,
                             null,
                             false,
                             false,
                             false,
                             false,
                             null,
                             null,
                             null,
                             null,
                             null,
                             null);
  }

  private static class Metadata {
    private final Optional<String> title;
    private final Optional<String> description;
    private final long             date;
    private final Optional<String> imageUrl;

    Metadata(Optional<String> title, Optional<String> description, long date, Optional<String> imageUrl) {
      this.title       = title;
      this.description = description;
      this.date        = date;
      this.imageUrl    = imageUrl;
    }

    static Metadata empty() {
      return new Metadata(Optional.empty(), Optional.empty(), 0, Optional.empty());
    }

    Optional<String> getTitle() {
      return title;
    }

    Optional<String> getDescription() {
      return description;
    }

    long getDate() {
      return date;
    }

    Optional<String> getImageUrl() {
      return imageUrl;
    }

    boolean isEmpty() {
      return !title.isPresent() && !imageUrl.isPresent();
    }
  }

  interface Callback {
    void onSuccess(@NonNull LinkPreview linkPreview);

    void onError(@NonNull Error error);

    /**
     * Tellomi (ADR-0063 §4.8, card-visual §3.9): how many members the group or stickers the pack has, when the
     * lookup knows. Called before {@link #onSuccess}.
     */
    default void onTellomiCount(int count) {}
  }
  
  public enum Error {
    PREVIEW_NOT_AVAILABLE,
    GROUP_LINK_INACTIVE
  }
}
