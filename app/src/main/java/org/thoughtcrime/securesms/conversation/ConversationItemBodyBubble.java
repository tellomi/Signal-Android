package org.thoughtcrime.securesms.conversation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.components.Outliner;
import org.thoughtcrime.securesms.util.Projection;
import org.signal.core.util.Util;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConversationItemBodyBubble extends LinearLayout {

  @Nullable private List<Outliner>              outliners = Collections.emptyList();
  @Nullable private OnSizeChangedListener       sizeChangedListener;
  @Nullable private OnVisibilityChangedListener visibilityChangedListener;

  /** Tellomi（#1257）：[backgroundGapTop, backgroundGapBottom) 这一条不画气泡背景（横滑相册铺在那里）。 */
  private int backgroundGapTop    = -1;
  private int backgroundGapBottom = -1;

  private ClipProjectionDrawable clipProjectionDrawable;
  private Projection             quoteViewProjection;
  private Projection             videoPlayerProjection;

  private final BodyBubbleLayoutTransition bodyBubbleLayoutTransition = new BodyBubbleLayoutTransition();

  public ConversationItemBodyBubble(Context context) {
    super(context);
    init();
  }

  public ConversationItemBodyBubble(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public ConversationItemBodyBubble(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    setLayoutTransition(bodyBubbleLayoutTransition);
  }

  public void setParentScrolling(boolean isParentScrolling) {
    if (isParentScrolling) {
      setLayoutTransition(null);
    } else {
      setLayoutTransition(bodyBubbleLayoutTransition);
    }
  }

  public void setOutliners(@NonNull List<Outliner> outliners) {
    this.outliners = outliners;
  }

  public void setOnSizeChangedListener(@Nullable OnSizeChangedListener listener) {
    this.sizeChangedListener = listener;
  }

  public void setOnVisibilityChangedListener(@Nullable OnVisibilityChangedListener listener) {
    this.visibilityChangedListener = listener;
  }

  @Override
  public void setBackground(Drawable background) {
    clipProjectionDrawable = new ClipProjectionDrawable(background);

    clipProjectionDrawable.setProjections(getProjections());
    clipProjectionDrawable.setGap(backgroundGapTop, backgroundGapBottom);
    super.setBackground(clipProjectionDrawable);
  }

  /**
   * Tellomi（#1257）：横滑相册从气泡里拆出去之后，气泡被它分成上下两段：上面群昵称 / 引用，下面说明。
   * 这一条（含上下的缝）不画背景，上下两段各自画成完整的气泡形状。传 -1 取消。
   */
  public void setBackgroundGap(int top, int bottom) {
    if (backgroundGapTop == top && backgroundGapBottom == bottom) {
      return;
    }

    backgroundGapTop    = top;
    backgroundGapBottom = bottom;

    if (clipProjectionDrawable != null) {
      clipProjectionDrawable.setGap(top, bottom);
    }
  }

  public void setQuoteViewProjection(@Nullable Projection quoteViewProjection) {
    if (this.quoteViewProjection != null) {
      this.quoteViewProjection.release();
    }

    this.quoteViewProjection = quoteViewProjection;
    clipProjectionDrawable.setProjections(getProjections());
  }

  public void setVideoPlayerProjection(@Nullable Projection videoPlayerProjection) {
    if (this.videoPlayerProjection != null) {
      this.videoPlayerProjection.release();
    }

    this.videoPlayerProjection = videoPlayerProjection;
    clipProjectionDrawable.setProjections(getProjections());
  }

  public @Nullable Projection getVideoPlayerProjection() {
    return videoPlayerProjection;
  }

  public @NonNull Set<Projection> getProjections() {
    return Stream.of(quoteViewProjection, videoPlayerProjection)
                 .filter(Objects::nonNull)
                 .collect(Collectors.toSet());
  }

  @Override
  public void onDrawForeground(Canvas canvas) {
    super.onDrawForeground(canvas);

    if (Util.isEmpty(outliners)) return;

    for (Outliner outliner : outliners) {
      outliner.draw(canvas, 0, getMeasuredWidth(), getMeasuredHeight(), 0);
    }
  }

  @Override
  protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
    super.onVisibilityChanged(changedView, visibility);

    if (changedView == this && visibilityChangedListener != null) {
      visibilityChangedListener.onVisibilityChanged(visibility);
    }
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    if (sizeChangedListener != null) {
      post(() -> {
        if (sizeChangedListener != null) {
          sizeChangedListener.onSizeChanged(width, height);
        }
      });
    }
  }

  public interface OnSizeChangedListener {
    void onSizeChanged(int width, int height);
  }

  public interface OnVisibilityChangedListener {
    void onVisibilityChanged(int visibility);
  }
}

