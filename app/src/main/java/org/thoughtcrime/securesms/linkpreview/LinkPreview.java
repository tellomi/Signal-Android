package org.thoughtcrime.securesms.linkpreview;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.ParcelCompat;
import androidx.core.text.HtmlCompat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.signal.core.models.database.AttachmentId;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.signal.core.util.JsonUtils;

import java.io.IOException;
import java.util.Optional;

public class LinkPreview implements Parcelable {

  private static final int MAX_FIELD_LENGTH = 500;

  @JsonProperty
  private final String       url;

  @JsonProperty
  private final String       title;

  @JsonProperty
  private final String       description;

  @JsonProperty
  private final long         date;

  @JsonProperty
  private final AttachmentId attachmentId;

  @JsonIgnore
  private final Optional<Attachment> thumbnail;

  /**
   * Tellomi（ADR-0063 §7.4，tellomi/tellomi#1420）：Preview.rich（1000 号字段）收到时的字节，含本机不认识的字段；
   * 落库（JSON 里是 base64）、读回、转发都原样带着，发送时原样发出。没有就是 null，JSON 里也不写这个键。
   */
  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final byte[] rich;

  public LinkPreview(@NonNull String url, @NonNull String title, @NonNull String description, long date, @NonNull DatabaseAttachment thumbnail) {
    this(url, title, description, date, thumbnail, null);
  }

  public LinkPreview(@NonNull String url, @NonNull String title, @NonNull String description, long date, @NonNull DatabaseAttachment thumbnail, @Nullable byte[] rich) {
    this.url          = url;
    this.title        = truncate(title);
    this.description  = truncate(description);
    this.date         = date;
    this.thumbnail    = Optional.of(thumbnail);
    this.attachmentId = thumbnail.attachmentId;
    this.rich         = rich;
  }

  public LinkPreview(@NonNull String url, @NonNull String title, @NonNull String description, long date, @NonNull Optional<Attachment> thumbnail) {
    this(url, title, description, date, thumbnail, null);
  }

  public LinkPreview(@NonNull String url, @NonNull String title, @NonNull String description, long date, @NonNull Optional<Attachment> thumbnail, @Nullable byte[] rich) {
    this.url          = url;
    this.title        = truncate(title);
    this.description  = truncate(description);
    this.date         = date;
    this.thumbnail    = thumbnail;
    this.attachmentId = null;
    this.rich         = rich;
  }

  public LinkPreview(@NonNull String url, @NonNull String title, @Nullable String description, long date, @Nullable AttachmentId attachmentId) {
    this(url, title, description, date, attachmentId, null);
  }

  @JsonCreator
  public LinkPreview(@JsonProperty("url")          @NonNull  String url,
                     @JsonProperty("title")        @NonNull  String title,
                     @JsonProperty("description")  @Nullable String description,
                     @JsonProperty("date")                   long date,
                     @JsonProperty("attachmentId") @Nullable AttachmentId attachmentId,
                     @JsonProperty("rich")         @Nullable byte[] rich)
  {
    this.url          = url;
    this.title        = truncate(title);
    this.description  = truncate(Optional.ofNullable(description).orElse(""));
    this.date         = date;
    this.attachmentId = attachmentId;
    this.thumbnail    = Optional.empty();
    this.rich         = rich;
  }

  protected LinkPreview(Parcel in) {
    url          = in.readString();
    title        = truncate(in.readString());
    description  = truncate(in.readString());
    date         = in.readLong();
    attachmentId = ParcelCompat.readParcelable(in, AttachmentId.class.getClassLoader(), AttachmentId.class);
    thumbnail    = Optional.ofNullable(ParcelCompat.readParcelable(in, Attachment.class.getClassLoader(), Attachment.class));
    rich         = in.createByteArray();
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(url);
    dest.writeString(title);
    dest.writeString(description);
    dest.writeLong(date);
    dest.writeParcelable(attachmentId, flags);
    dest.writeParcelable(thumbnail.orElse(null), 0);
    dest.writeByteArray(rich);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  public static final Creator<LinkPreview> CREATOR = new Creator<LinkPreview>() {
    @Override
    public LinkPreview createFromParcel(Parcel in) {
      return new LinkPreview(in);
    }

    @Override
    public LinkPreview[] newArray(int size) {
      return new LinkPreview[size];
    }
  };

  /**
   * Tellomi (ADR-0063 §7.4): this preview with only what rust/links says to keep of a received one: its thumbnail (whose attachment
   * is then never made or downloaded) and its {@code rich}. Everything else, title and description as received, is as it was.
   */
  public @NonNull LinkPreview withKept(boolean keepThumbnail, boolean keepRich) {
    if (keepThumbnail && keepRich) {
      return this;
    }
    return new LinkPreview(url, title, description, date, keepThumbnail ? thumbnail : Optional.empty(), keepRich ? rich : null);
  }

  public @NonNull String getUrl() {
    return url;
  }

  public @NonNull String getTitle() {
    return HtmlCompat.fromHtml(title, 0).toString();
  }

  public @NonNull String getDescription() {
    if (description.equals(title)) {
      return "";
    } else {
      return HtmlCompat.fromHtml(description, 0).toString();
    }
  }

  public long getDate() {
    return date;
  }

  public @NonNull Optional<Attachment> getThumbnail() {
    return thumbnail;
  }

  public @Nullable AttachmentId getAttachmentId() {
    return attachmentId;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public @Nullable byte[] getRich() {
    return rich;
  }

  private static @NonNull String truncate(@NonNull String value) {
    return value.length() > MAX_FIELD_LENGTH ? value.substring(0, MAX_FIELD_LENGTH) : value;
  }

  public @NonNull String serialize() throws IOException {
    return JsonUtils.toJson(this);
  }

  public static @NonNull LinkPreview deserialize(@NonNull String serialized) throws IOException {
    return JsonUtils.fromJson(serialized, LinkPreview.class);
  }
}
