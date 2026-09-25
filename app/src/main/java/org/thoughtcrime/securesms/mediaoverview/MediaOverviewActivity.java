/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.mediaoverview;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback;

import org.thoughtcrime.securesms.PassphraseRequiredActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.AnimatingToggle;
import org.thoughtcrime.securesms.components.BoldSelectionTabItem;
import org.thoughtcrime.securesms.components.ControllableTabLayout;
import org.thoughtcrime.securesms.database.MediaTable;
import org.thoughtcrime.securesms.database.MediaTable.Sorting;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.loaders.MediaLoader;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.SystemWindowInsetsSetter;
import org.signal.core.util.concurrent.SimpleTask;

import java.util.ArrayList;
import java.util.List;

import kotlin.Pair;

/**
 * Activity for displaying media attachments in-app
 */
public final class MediaOverviewActivity extends PassphraseRequiredActivity {

  private static final String THREAD_ID_EXTRA = "thread_id";
  private static final String TELLOMI_INITIAL_MEDIA_TYPE_EXTRA = "tellomi_initial_media_type";

  private final DynamicTheme dynamicTheme = new DynamicNoActionBarTheme();

  private Toolbar                toolbar;
  private ControllableTabLayout  tabLayout;
  private ViewPager              viewPager;
  private TextView               sortOrder;
  private View                   sortOrderArrow;
  private Sorting                currentSorting;
  private Boolean                currentDetailLayout;
  private MediaOverviewViewModel model;
  private AnimatingToggle        displayToggle;
  private View                   viewGrid;
  private View                   viewDetail;
  private long                   threadId;
  private boolean                tellomiSearchEnabled;
  private MenuItem               tellomiSearchItem;

  public static Intent forThread(@NonNull Context context, long threadId) {
    Intent intent = new Intent(context, MediaOverviewActivity.class);
    intent.putExtra(MediaOverviewActivity.THREAD_ID_EXTRA, threadId);
    return intent;
  }

  /** Tellomi：「我的收藏」的分类点进来，停在对应的页（#1174）。 */
  public static Intent forThread(@NonNull Context context, long threadId, @NonNull MediaLoader.MediaType initialMediaType) {
    Intent intent = forThread(context, threadId);
    intent.putExtra(TELLOMI_INITIAL_MEDIA_TYPE_EXTRA, initialMediaType.name());
    return intent;
  }

  public static Intent forAll(@NonNull Context context) {
    return forThread(context, MediaTable.ALL_THREADS);
  }

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle bundle, boolean ready) {
    setExitSharedElementCallback(new MaterialContainerTransformSharedElementCallback());
    setContentView(R.layout.media_overview_activity);

    initializeResources();
    initializeToolbar();

    boolean allThreads = threadId == MediaTable.ALL_THREADS;

    BoldSelectionTabItem.registerListeners(tabLayout);
    fillTabLayoutIfFits(tabLayout);
    tabLayout.setupWithViewPager(viewPager);
    viewPager.setAdapter(new MediaOverviewPagerAdapter(getSupportFragmentManager()));

    model = MediaOverviewViewModel.getMediaOverviewViewModel(this);
    model.setSortOrder(allThreads ? Sorting.Largest : Sorting.Newest);
    model.setDetailLayout(allThreads);
    model.getSortOrder().observe(this, this::setSorting);
    model.getDetailLayout().observe(this, this::setDetailLayout);

    sortOrder.setOnClickListener(this::showSortOrderDialog);
    sortOrderArrow.setOnClickListener(this::showSortOrderDialog);

    displayToggle.setOnClickListener(v -> setDetailLayout(!currentDetailLayout));

    viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
      @Override
      public void onPageSelected(int position) {
        boolean gridToggleEnabled = allowGridSelectionOnPage(position);
        displayToggle.animate()
                     .alpha(gridToggleEnabled ? 1 : 0)
                     .start();
        displayToggle.setEnabled(gridToggleEnabled);
      }
    });

    viewPager.setCurrentItem(allThreads ? viewPager.getAdapter().getCount() - 1 : 0);

    String initialMediaType = getIntent().getStringExtra(TELLOMI_INITIAL_MEDIA_TYPE_EXTRA);
    if (initialMediaType != null) {
      int page = ((MediaOverviewPagerAdapter) viewPager.getAdapter()).tellomiPageOf(MediaLoader.MediaType.valueOf(initialMediaType));
      if (page >= 0) {
        viewPager.setCurrentItem(page);
      }
    }
  }

  private static boolean allowGridSelectionOnPage(int page) {
    return page == 0;
  }

  private void setSorting(@NonNull Sorting sorting) {
    if (currentSorting == sorting) return;

    sortOrder.setText(sortingToString(sorting));
    currentSorting = sorting;
    model.setSortOrder(sorting);
  }

  private static @StringRes int sortingToString(@NonNull Sorting sorting) {
    switch (sorting) {
      case Oldest  : return R.string.MediaOverviewActivity_Oldest;
      case Newest  : return R.string.MediaOverviewActivity_Newest;
      case Largest : return R.string.MediaOverviewActivity_Storage_used;
      default      : throw new AssertionError();
    }
  }

  private void setDetailLayout(@NonNull Boolean detailLayout) {
    if (currentDetailLayout == detailLayout) return;

    currentDetailLayout = detailLayout;
    model.setDetailLayout(detailLayout);
    displayToggle.display(detailLayout ? viewGrid : viewDetail);
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
  }

  /** Tellomi：「我的收藏」的所有媒体顶栏有搜索，在当前这一页里按文字筛（#1174）。 */
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    if (tellomiSearchEnabled) {
      MenuItem   searchItem = menu.add(Menu.NONE, Menu.NONE, Menu.NONE, R.string.TellomiSavedCategories__search);
      SearchView searchView = new SearchView(this);
      searchView.setQueryHint(getString(R.string.TellomiSavedCategories__search_hint));
      searchView.setMaxWidth(Integer.MAX_VALUE);
      searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextSubmit(String query) {
          model.setTellomiQuery(query);
          return true;
        }

        @Override
        public boolean onQueryTextChange(String query) {
          model.setTellomiQuery(query);
          return true;
        }
      });
      searchItem.setIcon(org.signal.core.ui.R.drawable.symbol_search_24);
      searchItem.setActionView(searchView);
      searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
      searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
        @Override
        public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
          return true;
        }

        @Override
        public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
          model.setTellomiQuery(null);
          return true;
        }
      });
      tellomiSearchItem = searchItem;
      // 菜单重建时搜索框是收起的：查询也一起清掉，免得列表被一个看不见的查询筛着（例如转屏之后）
      model.setTellomiQuery(null);
    }
    return super.onCreateOptionsMenu(menu);
  }

  /**
   * Tellomi：搜索框展开时不重建菜单（#1174）。每一页装载完都会调这里（上游 {@link MediaOverviewPageFragment} 的 onLoadFinished），
   * 重建会把展开的搜索框收起来：输入第一个字、页重新筛完，搜索框就没了（走查模拟器上实测）。
   * 上游这个页面本来没有菜单，跳过重建没有别的影响。
   */
  @Override
  public void invalidateOptionsMenu() {
    if (tellomiSearchItem != null && tellomiSearchItem.isActionViewExpanded()) {
      return;
    }
    super.invalidateOptionsMenu();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }

    return false;
  }

  private void initializeResources() {
    Intent intent   = getIntent();
    long   threadId = intent.getLongExtra(THREAD_ID_EXTRA, Long.MIN_VALUE);

    if (threadId == Long.MIN_VALUE) throw new AssertionError();

    this.viewPager      = findViewById(R.id.pager);
    this.toolbar        = findViewById(R.id.toolbar);
    this.tabLayout      = findViewById(R.id.tab_layout);
    this.sortOrder      = findViewById(R.id.sort_order);
    this.sortOrderArrow = findViewById(R.id.sort_order_arrow);
    this.displayToggle  = findViewById(R.id.grid_list_toggle);
    this.viewDetail     = findViewById(R.id.view_detail);
    this.viewGrid       = findViewById(R.id.view_grid);
    this.threadId       = threadId;
  }

  private void initializeToolbar() {
    toolbar.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
    SystemWindowInsetsSetter.attach(toolbar, this, WindowInsetsCompat.Type.statusBars());

    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    if (threadId == MediaTable.ALL_THREADS) {
      getSupportActionBar().setTitle(R.string.MediaOverviewActivity_All_storage_use);
    } else {
      SimpleTask.run(() -> SignalDatabase.threads().getRecipientForThreadId(threadId),
        (recipient) -> {
          if (recipient != null) {
            getSupportActionBar().setTitle(tellomiTitle(recipient));
            tellomiSearchEnabled = recipient.isSelf();
            invalidateOptionsMenu();
            recipient.live().observe(this, r -> getSupportActionBar().setTitle(tellomiTitle(r)));
          }
        }
      );
    }
  }

  /** Tellomi：自己的会话（「我的收藏」）的所有媒体叫「我的收藏」，和会话顶栏一致，不显示自己的资料名（#1174）。 */
  private @NonNull String tellomiTitle(@NonNull Recipient recipient) {
    return recipient.isSelf() ? getString(R.string.note_to_self) : recipient.getDisplayName(this);
  }

  public void onEnterMultiSelect() {
    tabLayout.setEnabled(false);
    viewPager.setEnabled(false);
    toolbar.setVisibility(View.INVISIBLE);
  }

  public void onExitMultiSelect() {
    tabLayout.setEnabled(true);
    viewPager.setEnabled(true);
    toolbar.setVisibility(View.VISIBLE);
  }

  private void showSortOrderDialog(View v) {
    new MaterialAlertDialogBuilder(MediaOverviewActivity.this)
      .setTitle(R.string.MediaOverviewActivity_Sort_by)
      .setSingleChoiceItems(R.array.MediaOverviewActivity_Sort_by,
        currentSorting.ordinal(),
        (dialog, item) -> {
          setSorting(Sorting.values()[item]);
          dialog.dismiss();
        })
      .create()
      .show();
  }

  private static void fillTabLayoutIfFits(@NonNull TabLayout tabLayout) {
    tabLayout.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      int totalWidth = 0;
      int maxWidth   = 0;
      ViewGroup tabs = (ViewGroup) tabLayout.getChildAt(0);

      for (int i = 0; i < tabLayout.getTabCount(); i++) {
        int tabWidth = tabs.getChildAt(i).getWidth();
        totalWidth += tabWidth;
        maxWidth = Math.max(maxWidth, tabWidth);
      }

      int viewWidth    = right - left;
      int tabCount     = tabLayout.getTabCount();
      int fixedTabSize = tabCount > 0 ? viewWidth / tabCount : 0;

      if (totalWidth < viewWidth && maxWidth <= fixedTabSize) {
        tabLayout.setTabMode(TabLayout.MODE_FIXED);
      }
    });
  }

  private class MediaOverviewPagerAdapter extends FragmentStatePagerAdapter {

    private final List<Pair<MediaLoader.MediaType, CharSequence>> pages;

    MediaOverviewPagerAdapter(FragmentManager fragmentManager) {
      super(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);

      boolean allThreads = threadId == MediaTable.ALL_THREADS;

      pages = new ArrayList<>(allThreads ? 4 : 5);
      pages.add(new Pair<>(MediaLoader.MediaType.GALLERY,  getString(R.string.MediaOverviewActivity_Media)));
      pages.add(new Pair<>(MediaLoader.MediaType.DOCUMENT, getString(R.string.MediaOverviewActivity_Files)));
      pages.add(new Pair<>(MediaLoader.MediaType.AUDIO,    getString(R.string.MediaOverviewActivity_Audio)));
      if (!allThreads) {
        pages.add(new Pair<>(MediaLoader.MediaType.LINK,   getString(R.string.MediaOverviewActivity_Links)));
      }
      pages.add(new Pair<>(MediaLoader.MediaType.ALL,      getString(R.string.MediaOverviewActivity_All)));
    }

    @Override
    public  @NonNull Fragment getItem(int position) {
      MediaOverviewPageFragment.GridMode gridMode = allowGridSelectionOnPage(position)
                                                       ? MediaOverviewPageFragment.GridMode.FOLLOW_MODEL
                                                       : MediaOverviewPageFragment.GridMode.FIXED_DETAIL;

      return MediaOverviewPageFragment.newInstance(threadId, pages.get(position).getFirst(), gridMode);
    }

    @Override
    public int getCount() {
      return pages.size();
    }

    /** Tellomi：这一类在第几页，没有这一页时是 -1（#1174）。 */
    int tellomiPageOf(@NonNull MediaLoader.MediaType mediaType) {
      for (int i = 0; i < pages.size(); i++) {
        if (pages.get(i).getFirst() == mediaType) {
          return i;
        }
      }
      return -1;
    }

    @Override
    public CharSequence getPageTitle(int position) {
      return pages.get(position).getSecond();
    }
  }
}
