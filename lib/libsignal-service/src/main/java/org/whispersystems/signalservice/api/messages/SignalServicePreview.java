package org.whispersystems.signalservice.api.messages;


import java.util.Optional;

public class SignalServicePreview {
  private final String                            url;
  private final String                            title;
  private final String                            description;
  private final long                              date;
  private final Optional<SignalServiceAttachment> image;
  // Tellomi（ADR-0063 §7.4，tellomi/tellomi#1420）：Preview.rich（1000 号字段）的字节，原样发出；没有就是 null。
  private final byte[]                            rich;

  public SignalServicePreview(String url, String title, String description, long date, Optional<SignalServiceAttachment> image) {
    this(url, title, description, date, image, null);
  }

  public SignalServicePreview(String url, String title, String description, long date, Optional<SignalServiceAttachment> image, byte[] rich) {
    this.url         = url;
    this.title       = title;
    this.description = description;
    this.date        = date;
    this.image       = image;
    this.rich        = rich;
  }

  public String getUrl() {
    return url;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public long getDate() {
    return date;
  }

  public Optional<SignalServiceAttachment> getImage() {
    return image;
  }

  public byte[] getRich() {
    return rich;
  }
}
