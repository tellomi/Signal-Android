package org.thoughtcrime.securesms.mediaoverview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.SavedStateViewModelFactory;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.thoughtcrime.securesms.database.MediaTable.Sorting;

import java.util.Objects;

public class MediaOverviewViewModel extends ViewModel {

  private final MutableLiveData<Sorting> sortOrder;
  private final MutableLiveData<Boolean> detailLayout;
  private final MutableLiveData<String>  tellomiQuery = new MutableLiveData<>(null);

  public MediaOverviewViewModel(@NonNull SavedStateHandle savedStateHandle) {
    sortOrder    = savedStateHandle.getLiveData("SORT_ORDER", Sorting.Newest);
    detailLayout = savedStateHandle.getLiveData("DETAIL_LAYOUT", false);
  }

  public LiveData<Sorting> getSortOrder() {
    return sortOrder;
  }

  public LiveData<Boolean> getDetailLayout() {
    return detailLayout;
  }

  public void setSortOrder(@NonNull Sorting sortOrder) {
    this.sortOrder.setValue(sortOrder);
  }

  public void setDetailLayout(boolean detailLayout) {
    this.detailLayout.setValue(detailLayout);
  }

  /** Tellomi：「我的收藏」按类型搜的查询，几页共用（#1174）。 */
  public LiveData<String> getTellomiQuery() {
    return tellomiQuery;
  }

  public void setTellomiQuery(@Nullable String query) {
    if (!Objects.equals(query, tellomiQuery.getValue())) {
      tellomiQuery.setValue(query);
    }
  }

  static MediaOverviewViewModel getMediaOverviewViewModel(@NonNull FragmentActivity activity) {
    SavedStateViewModelFactory savedStateViewModelFactory = new SavedStateViewModelFactory(activity.getApplication(), activity);

    return new ViewModelProvider(activity, savedStateViewModelFactory).get(MediaOverviewViewModel.class);
  }
}
