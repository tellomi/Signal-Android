package org.thoughtcrime.securesms.conversationlist;

import android.os.Handler;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ConversationListItemAnimator extends DefaultItemAnimator {

  private static final long ANIMATION_DURATION = 200;

  // Tellomi（交互审计 A-37）：移动不再默认关掉（原来只有被顶上去的那一行滑、其余行瞬移），见 TellomiChatListMotion。
  private boolean listScrolling;
  private int     pendingAdditions;
  private int     pendingRemovals;

  @VisibleForTesting
  boolean lastRunAnimated;

  public ConversationListItemAnimator() {
    setMoveDuration(TellomiChatListMotion.MOVE_DURATION_MS);
    setAddDuration(TellomiChatListMotion.ADD_DURATION_MS);
    setChangeDuration(ANIMATION_DURATION);
  }

  @Override
  public boolean animateChange(@NonNull RecyclerView.ViewHolder oldHolder, @NonNull RecyclerView.ViewHolder newHolder, int fromX, int fromY, int toX, int toY) {
    if (fromX != toX || fromY != toY) {
      return animateMove(newHolder, fromX, fromY, toX, toY);
    }

    dispatchChangeFinished(newHolder, true);
    return false;
  }

  @Override
  public boolean animateMove(@NonNull RecyclerView.ViewHolder holder, int fromX, int fromY, int toX, int toY) {
    boolean pending = super.animateMove(holder, fromX, fromY, toX, toY);
    if (pending) {
      holder.itemView.animate().setInterpolator(TellomiChatListMotion.MOVE_INTERPOLATOR);
    }
    return pending;
  }

  @Override
  public boolean animateAdd(@NonNull RecyclerView.ViewHolder holder) {
    pendingAdditions++;
    return super.animateAdd(holder);
  }

  @Override
  public boolean animateRemove(@NonNull RecyclerView.ViewHolder holder) {
    pendingRemovals++;
    return super.animateRemove(holder);
  }

  @Override
  public void runPendingAnimations() {
    boolean animate = TellomiChatListMotion.shouldAnimate(pendingAdditions, pendingRemovals, listScrolling);
    pendingAdditions = 0;
    pendingRemovals  = 0;
    lastRunAnimated  = animate;

    if (animate) {
      super.runPendingAnimations();
    } else {
      endAnimations();
    }
  }

  @Override
  public boolean canReuseUpdatedViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, @NonNull List<Object> payloads) {
    return true;
  }

  /** Tellomi（交互审计 A-37）：手指在滑或惯性滚动时来的更新直接换位，不播移动。 */
  @MainThread
  public void setListScrolling(boolean listScrolling) {
    this.listScrolling = listScrolling;
  }

  /** Tellomi（交互审计 A-37）：移动一直开着，滑动归档前后不用再开关；留着给原来的调用处。 */
  @MainThread
  public void enable() {
  }

  @MainThread
  public void disable() {
  }

  @MainThread
  public void disableChangeAnimations() {
    setChangeDuration(0);
  }

  @MainThread
  public void enableChangeAnimations() {
    setChangeDuration(ANIMATION_DURATION);
  }

  @MainThread
  public void postDisable(Handler handler) {
  }
}
