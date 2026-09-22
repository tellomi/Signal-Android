package org.thoughtcrime.securesms.groups.v2;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.Base64;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.signal.storageservice.storage.protos.groups.GroupInviteLink;
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.thoughtcrime.securesms.util.TellomiLinks;
import okio.ByteString;

public final class GroupInviteLinkUrl {

  private static final String GROUP_URL_HOST   = "signal.group";
  private static final String GROUP_URL_PREFIX = "https://" + GROUP_URL_HOST + "/#";

  private final GroupMasterKey    groupMasterKey;
  private final GroupLinkPassword password;
  private final String            url;

  public static GroupInviteLinkUrl forGroup(@NonNull GroupMasterKey groupMasterKey,
                                            @NonNull DecryptedGroup group)
  {
    return new GroupInviteLinkUrl(groupMasterKey, GroupLinkPassword.fromBytes(group.inviteLinkPassword.toByteArray()));
  }

  public static boolean isGroupLink(@NonNull String urlString) {
    return getGroupUrl(urlString) != null;
  }

  /**
   * @return null iff not a group url.
   * @throws InvalidGroupLinkException If group url, but cannot be parsed.
   */
  public static @Nullable GroupInviteLinkUrl fromUri(@NonNull String urlString)
      throws InvalidGroupLinkException, UnknownGroupLinkVersionException
  {
    URI uri = getGroupUrl(urlString);

    if (uri == null) {
      return null;
    }

    try {
      if (!"/".equals(uri.getPath()) && uri.getPath().length() > 0) {
        throw new InvalidGroupLinkException("No path was expected in uri");
      }

      String encoding = uri.getFragment();

      if (encoding == null || encoding.length() == 0) {
        throw new InvalidGroupLinkException("No reference was in the uri");
      }

      byte[] bytes;
      try {
        bytes = Base64.decode(encoding);
      } catch (IllegalArgumentException e) {
        throw new InvalidGroupLinkException(e);
      }

      GroupInviteLink groupInviteLink = GroupInviteLink.ADAPTER.decode(bytes);

      //noinspection SwitchStatementWithTooFewBranches
      if (groupInviteLink.contentsV1 != null) {
        GroupInviteLink.GroupInviteLinkContentsV1 groupInviteLinkContentsV1 = groupInviteLink.contentsV1;
        GroupMasterKey                            groupMasterKey            = new GroupMasterKey(groupInviteLinkContentsV1.groupMasterKey.toByteArray());
        GroupLinkPassword                         password                  = GroupLinkPassword.fromBytes(groupInviteLinkContentsV1.inviteLinkPassword.toByteArray());

        return new GroupInviteLinkUrl(groupMasterKey, password);
      } else {
        throw new UnknownGroupLinkVersionException("Url contains no known group link content");
      }
    } catch (InvalidInputException | IllegalStateException | IOException e) {
      throw new InvalidGroupLinkException(e);
    }
  }

  /**
   * @return {@link URI} if the host name matches.
   */
  private static URI getGroupUrl(@NonNull String urlString) {
    try {
      URI url = new URI(urlString);

      // Tellomi：新旧两种形状都认（见 docs/signal/LINKS_AND_SCHEMES.md）
      //   旧：https|sgnl    ://signal.group/#<invite>
      //   新：https|tellomi ://tell.cc/g#<invite>
      if (!"https".equalsIgnoreCase(url.getScheme()) &&
          !TellomiLinks.isAppScheme(url.getScheme()))
      {
        return null;
      }

      // 注意**必须连路径一起判**：tell.cc 下还有 /u（找人）、/s（贴纸）、/call（通话链接）。
      // 只看 host 的话，`tell.cc/u#p/+86…` 也会走进来，然后被当成群邀请去 Base64 解片段，
      // 解不动就抛 InvalidGroupLinkException —— 用户看到的是「群链接无效」，而他点的根本不是群链接。
      // 旧的 signal.group 是整个域名专用，没有路径，保持原样只判 host。
      if (TellomiLinks.LEGACY_HOST_GROUP.equalsIgnoreCase(url.getHost())) {
        return url;
      }

      if (TellomiLinks.HOST.equalsIgnoreCase(url.getHost())) {
        String path = url.getPath();
        return "/g".equals(path) || "/g/".equals(path) ? url : null;
      }

      return null;

    } catch (URISyntaxException e) {
      return null;
    }
  }

  private GroupInviteLinkUrl(@NonNull GroupMasterKey groupMasterKey, @NonNull GroupLinkPassword password) {
    this.groupMasterKey = groupMasterKey;
    this.password       = password;
    this.url            = createUrl(groupMasterKey, password);
  }

  protected static @NonNull String createUrl(@NonNull GroupMasterKey groupMasterKey, @NonNull GroupLinkPassword password) {
    GroupInviteLink groupInviteLink = new GroupInviteLink.Builder()
                                                         .contentsV1(new GroupInviteLink.GroupInviteLinkContentsV1.Builder()
                                                                                                                  .groupMasterKey(ByteString.of(groupMasterKey.serialize()))
                                                                                                                  .inviteLinkPassword(ByteString.of(password.serialize()))
                                                                                                                  .build())
                                                         .build();

    String encoding = Base64.encodeUrlSafeWithoutPadding(groupInviteLink.encode());

    return GROUP_URL_PREFIX + encoding;
  }

  public @NonNull String getUrl() {
    return url;
  }

  public @NonNull GroupMasterKey getGroupMasterKey() {
    return groupMasterKey;
  }

  public @NonNull GroupLinkPassword getPassword() {
    return password;
  }

  public final static class InvalidGroupLinkException extends Exception {
    public InvalidGroupLinkException(String message) {
      super(message);
    }

    public InvalidGroupLinkException(Throwable cause) {
      super(cause);
    }
  }

  public final static class UnknownGroupLinkVersionException extends Exception {
    public UnknownGroupLinkVersionException(String message) {
      super(message);
    }
  }
}
