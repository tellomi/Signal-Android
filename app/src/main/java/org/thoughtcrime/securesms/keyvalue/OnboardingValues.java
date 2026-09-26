package org.thoughtcrime.securesms.keyvalue;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

public final class OnboardingValues extends SignalStoreValues {

  private static final String SHOW_NEW_GROUP      = "onboarding.new_group";
  private static final String SHOW_INVITE_FRIENDS = "onboarding.invite_friends";
  private static final String SHOW_APPEARANCE     = "onboarding.appearance";
  private static final String SHOW_ADD_PHOTO      = "onboarding.add_photo";

  // Tellomi（tellomi/tellomi#1218 F-02、第 5 条）：首屏「开始使用」换成找朋友三条路（搜索用户名 / 我的二维码 / 邀请朋友）+ 设头像；
  // 上游的「新建群组」「聊天颜色」两张不出。三条路在出现第一个真人会话后自动收起（TellomiOnboarding）。
  private static final String SHOW_FIND_BY_USERNAME = "onboarding.tellomi.find_by_username";
  private static final String SHOW_MY_QR_CODE       = "onboarding.tellomi.my_qr_code";

  OnboardingValues(@NonNull KeyValueStore store) {
    super(store);
  }

  @Override
  void onFirstEverAppLaunch() {
    putBoolean(SHOW_NEW_GROUP, true);
    putBoolean(SHOW_INVITE_FRIENDS, true);
    putBoolean(SHOW_APPEARANCE, true);
    putBoolean(SHOW_ADD_PHOTO, true);
    putBoolean(SHOW_FIND_BY_USERNAME, true);
    putBoolean(SHOW_MY_QR_CODE, true);
  }

  @Override
  @NonNull List<String> getKeysToIncludeInBackup() {
    return Collections.emptyList();
  }

  public void clearAll() {
    setShowNewGroup(false);
    setShowInviteFriends(false);
    setShowAppearance(false);
    setShowAddPhoto(false);
    setShowFindByUsername(false);
    setShowMyQrCode(false);
  }

  /** Tellomi：找朋友三条路一起收起（出现第一个真人会话时）。 */
  public void clearFindFriends() {
    setShowFindByUsername(false);
    setShowMyQrCode(false);
    setShowInviteFriends(false);
  }

  /** Tellomi：只看会显示的四张（上游的「新建群组」「聊天颜色」不出，开关就不算）。 */
  public boolean hasOnboarding(@NonNull Context context) {
    return shouldShowFindByUsername() ||
           shouldShowMyQrCode()       ||
           shouldShowInviteFriends()  ||
           shouldShowAddPhoto();
  }

  public void setShowNewGroup(boolean value) {
    putBoolean(SHOW_NEW_GROUP, value);
  }

  public boolean shouldShowNewGroup() {
    return getBoolean(SHOW_NEW_GROUP, false);
  }

  public void setShowInviteFriends(boolean value) {
    putBoolean(SHOW_INVITE_FRIENDS, value);
  }

  public boolean shouldShowInviteFriends() {
    return getBoolean(SHOW_INVITE_FRIENDS, false);
  }

  public void setShowAppearance(boolean value) {
    putBoolean(SHOW_APPEARANCE, value);
  }

  public boolean shouldShowAppearance() {
    return getBoolean(SHOW_APPEARANCE, false);
  }

  public void setShowAddPhoto(boolean value) {
    putBoolean(SHOW_ADD_PHOTO, value);
  }

  public boolean shouldShowAddPhoto() {
    return getBoolean(SHOW_ADD_PHOTO, false);
  }

  public void setShowFindByUsername(boolean value) {
    putBoolean(SHOW_FIND_BY_USERNAME, value);
  }

  public boolean shouldShowFindByUsername() {
    return getBoolean(SHOW_FIND_BY_USERNAME, false);
  }

  public void setShowMyQrCode(boolean value) {
    putBoolean(SHOW_MY_QR_CODE, value);
  }

  public boolean shouldShowMyQrCode() {
    return getBoolean(SHOW_MY_QR_CODE, false);
  }
}
