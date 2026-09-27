package org.thoughtcrime.securesms.conversation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.conversation.v2.items.InteractiveConversationElement;
import org.signal.core.util.AccessibilityUtil;
import org.signal.core.util.ServiceUtil;

import java.util.Objects;

public class ConversationItemSwipeCallback extends ItemTouchHelper.SimpleCallback {

  private static long SWIPE_SUCCESS_VIBE_TIME_MS = 10;

  private boolean swipeBack;
  private boolean shouldTriggerSwipeFeedback;
  private boolean canTriggerSwipe;
  private float   latestDownX;
  private float   latestDownY;

  private final SwipeAvailabilityProvider     swipeAvailabilityProvider;
  private final ConversationItemTouchListener itemTouchListener;
  private final OnSwipeListener               onSwipeListener;

  public ConversationItemSwipeCallback(@NonNull SwipeAvailabilityProvider swipeAvailabilityProvider,
                                       @NonNull OnSwipeListener onSwipeListener)
  {
    // Tellomi（tellomi/tellomi#1109）：回复改成手指从右往左滑（Telegram 基线；RTL 下 START 自动镜像成从左往右）。
    super(0, ItemTouchHelper.START);
    this.itemTouchListener          = new ConversationItemTouchListener(this::updateLatestDownCoordinate);
    this.swipeAvailabilityProvider  = swipeAvailabilityProvider;
    this.onSwipeListener            = onSwipeListener;
    this.shouldTriggerSwipeFeedback = true;
    this.canTriggerSwipe            = true;
  }

  public void attachToRecyclerView(@NonNull RecyclerView recyclerView) {
    recyclerView.addOnItemTouchListener(itemTouchListener);
    new ItemTouchHelper(this).attachToRecyclerView(recyclerView);
  }

  @Override
  public boolean onMove(@NonNull RecyclerView recyclerView,
                        @NonNull RecyclerView.ViewHolder viewHolder,
                        @NonNull RecyclerView.ViewHolder target)
  {
    return false;
  }

  @Override
  public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
  }

  @Override
  public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder)
  {
    if (cannotSwipeViewHolder(viewHolder)) return 0;
    return super.getSwipeDirs(recyclerView, viewHolder);
  }

  @Override
  public int convertToAbsoluteDirection(int flags, int layoutDirection) {
    if (swipeBack) {
      swipeBack = false;
      return 0;
    }
    return super.convertToAbsoluteDirection(flags, layoutDirection);
  }

  @Override
  public void onChildDraw(
          @NonNull Canvas c,
          @NonNull RecyclerView recyclerView,
          @NonNull RecyclerView.ViewHolder viewHolder,
          float dx, float dy, int actionState, boolean isCurrentlyActive)
  {
    if (cannotSwipeViewHolder(viewHolder)) return;

    float   sign              = getSignFromDirection(viewHolder.itemView);
    boolean isCorrectSwipeDir = sameSign(dx, sign);

    if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && isCorrectSwipeDir) {
      ConversationSwipeAnimationHelper.update(requireInteractiveConversationElement(viewHolder), Math.abs(dx), sign);
      recyclerView.invalidate();
      handleSwipeFeedback(recyclerView.getContext(), requireInteractiveConversationElement(viewHolder), Math.abs(dx));
      if (canTriggerSwipe) {
        setTouchListener(recyclerView, viewHolder, Math.abs(dx));
      }
    } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE || dx == 0) {
      ConversationSwipeAnimationHelper.update(requireInteractiveConversationElement(viewHolder), 0, 1);
      recyclerView.invalidate();
    }

    if (dx == 0) {
      shouldTriggerSwipeFeedback = true;
      canTriggerSwipe            = true;
    }
  }

  private void handleSwipeFeedback(@NonNull Context context, @NonNull InteractiveConversationElement interactiveConversationElement, float dx) {
    if (dx > triggerDx(interactiveConversationElement) && shouldTriggerSwipeFeedback) {
      vibrate(context);
      ConversationSwipeAnimationHelper.trigger(interactiveConversationElement);
      shouldTriggerSwipeFeedback = false;
    }
  }

  private void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder) {
    if (cannotSwipeViewHolder(viewHolder)) return;

    InteractiveConversationElement element = requireInteractiveConversationElement(viewHolder);

    onSwipeListener.onSwipe(element.getConversationMessage());
  }

  @SuppressLint("ClickableViewAccessibility")
  private void setTouchListener(@NonNull RecyclerView recyclerView,
                                @NonNull RecyclerView.ViewHolder viewHolder,
                                float dx)
  {
    recyclerView.setOnTouchListener((v, event) -> {
      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          shouldTriggerSwipeFeedback = true;
          break;
        case MotionEvent.ACTION_UP:
          handleTouchActionUp(recyclerView, viewHolder, dx);
        case MotionEvent.ACTION_CANCEL:
          swipeBack = true;
          shouldTriggerSwipeFeedback = false;
          resetProgressIfAnimationsDisabled(recyclerView, viewHolder);
          break;
      }
      return false;
    });
  }

  private void handleTouchActionUp(@NonNull RecyclerView recyclerView,
                                   @NonNull RecyclerView.ViewHolder viewHolder,
                                   float dx)
  {
    if (dx > triggerDx(requireInteractiveConversationElement(viewHolder))) {
      canTriggerSwipe = false;
      onSwiped(viewHolder);
      if (shouldTriggerSwipeFeedback) {
        vibrate(viewHolder.itemView.getContext());
      }
      recyclerView.setOnTouchListener(null);
    }
    recyclerView.cancelPendingInputEvents();
  }

  private void resetProgressIfAnimationsDisabled(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
    if (AccessibilityUtil.areAnimationsDisabled(viewHolder.itemView.getContext())) {
      ConversationSwipeAnimationHelper.update(requireInteractiveConversationElement(viewHolder),
                                              0f,
                                              getSignFromDirection(viewHolder.itemView));
      recyclerView.invalidate();
    }
  }

  private @NonNull InteractiveConversationElement requireInteractiveConversationElement(@NonNull RecyclerView.ViewHolder viewHolder) {
    return Objects.requireNonNull(getInteractiveConversationElement(viewHolder));
  }

  private @Nullable InteractiveConversationElement getInteractiveConversationElement(@NonNull RecyclerView.ViewHolder viewHolder) {
    if (viewHolder instanceof InteractiveConversationElement) {
      return (InteractiveConversationElement) viewHolder;
    } else if (viewHolder.itemView instanceof InteractiveConversationElement) {
      return (InteractiveConversationElement) viewHolder.itemView;
    } else {
      return null;
    }
  }

  private boolean cannotSwipeViewHolder(@NonNull RecyclerView.ViewHolder viewHolder) {
    InteractiveConversationElement element = getInteractiveConversationElement(viewHolder);
    if (element == null) {
      return true;
    }

    return !swipeAvailabilityProvider.isSwipeAvailable(element.getConversationMessage()) ||
           element.disallowSwipe(latestDownX, latestDownY);
  }

  private void updateLatestDownCoordinate(float x, float y) {
    latestDownX = x;
    latestDownY = y;
  }

  private static float getSignFromDirection(@NonNull View view) {
    return TellomiSwipeToReply.replySign(view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
  }

  /** Tellomi（tellomi/tellomi#1109）：过这个位移松手才算回复——对方的消息 45dp、自己的 60dp（Telegram 同值）。 */
  static float triggerDx(@NonNull InteractiveConversationElement element) {
    boolean isOutgoing = element.getConversationMessage().getMessageRecord().isOutgoing();
    return TellomiSwipeToReply.triggerDp(isOutgoing) * Resources.getSystem().getDisplayMetrics().density;
  }

  private static boolean sameSign(float dX, float sign) {
    return dX * sign > 0;
  }

  private static void vibrate(@NonNull Context context) {
    Vibrator vibrator = ServiceUtil.getVibrator(context);
    if (vibrator != null) vibrator.vibrate(SWIPE_SUCCESS_VIBE_TIME_MS);
  }

  public interface SwipeAvailabilityProvider {
    boolean isSwipeAvailable(ConversationMessage conversationMessage);
  }

  public interface OnSwipeListener {
    void onSwipe(ConversationMessage conversationMessage);
  }
}
