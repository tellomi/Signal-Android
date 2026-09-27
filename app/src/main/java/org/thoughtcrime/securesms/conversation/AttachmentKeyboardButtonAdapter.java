package org.thoughtcrime.securesms.conversation;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;

import java.util.ArrayList;
import java.util.List;

class AttachmentKeyboardButtonAdapter extends RecyclerView.Adapter<AttachmentKeyboardButtonAdapter.ButtonViewHolder> {

  private final List<AttachmentKeyboardButton> buttons;
  private final Listener                       listener;

  private boolean wallpaperEnabled;

  AttachmentKeyboardButtonAdapter(@NonNull Listener listener) {
    this.buttons  = new ArrayList<>();
    this.listener = listener;

    setHasStableIds(true);
  }

  @Override
  public long getItemId(int position) {
    return buttons.get(position).getTitleRes();
  }

  @Override
  public @NonNull
  ButtonViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ButtonViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.attachment_keyboard_button_item, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ButtonViewHolder holder, int position) {
    holder.bind(buttons.get(position), wallpaperEnabled, listener);
  }

  @Override
  public void onViewRecycled(@NonNull ButtonViewHolder holder) {
    holder.recycle();
  }

  @Override
  public int getItemCount() {
    return buttons.size();
  }

  public void setButtons(@NonNull List<AttachmentKeyboardButton> buttons) {
    this.buttons.clear();
    this.buttons.addAll(buttons);
    notifyDataSetChanged();
  }

  public void setWallpaperEnabled(boolean enabled) {
    if (wallpaperEnabled != enabled) {
      wallpaperEnabled = enabled;
      notifyDataSetChanged();
    }
  }

  interface Listener {
    void onClick(@NonNull AttachmentKeyboardButton button);
  }

  static class ButtonViewHolder extends RecyclerView.ViewHolder {

    private final ImageView image;
    private final TextView  title;

    public ButtonViewHolder(@NonNull View itemView) {
      super(itemView);

      this.image = itemView.findViewById(R.id.icon);
      this.title = itemView.findViewById(R.id.label);
    }

    void bind(@NonNull AttachmentKeyboardButton button, boolean wallpaperEnabled, @NonNull Listener listener) {
      image.setImageResource(button.getIconRes());
      title.setText(button.getTitleRes());

      // Tellomi（tellomi/tellomi#1235、#1124）：高德接上之前「位置」置灰，点了提示「即将支持」（ConversationFragment），
      // 不走大陆打不开、又要用 Signal 的 key 的谷歌地图。owner 2026-09-24：等高德，暂时不开谷歌账单。
      boolean comingSoon = button == AttachmentKeyboardButton.LOCATION && !BuildConfig.MAPS_AVAILABLE;
      itemView.setAlpha(comingSoon ? 0.38f : 1f);
      // 读屏：只降透明度的话，TalkBack 念的是普通的「位置，按钮」，要点了才从 Toast 听到「即将支持」（taishi 审查 b9 不阻塞 1）
      ViewCompat.setStateDescription(itemView, comingSoon ? itemView.getContext().getString(R.string.TellomiLocation__coming_soon) : null);

      itemView.setOnClickListener(v -> listener.onClick(button));
    }

    void recycle() {
      itemView.setOnClickListener(null);
    }
  }
}
