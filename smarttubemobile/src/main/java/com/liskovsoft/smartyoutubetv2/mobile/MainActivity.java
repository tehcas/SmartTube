package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.disposables.Disposable;

public final class MainActivity extends Activity implements BrowseView {
    private static final String STATE_SECTION_ID = "mobile_section_id";
    private static final String STATE_SCROLL_Y = "mobile_scroll_y";
    private static final int MAX_CARDS_PER_SHELF = 12;

    private final List<BrowseSection> sections = new ArrayList<>();
    private final List<VideoGroup> visibleGroups = new ArrayList<>();

    private BrowsePresenter presenter;
    private LinearLayout navigationItems;
    private LinearLayout navigationPanel;
    private LinearLayout shelvesContainer;
    private LinearLayout stateContainer;
    private TextView screenTitle;
    private TextView screenSubtitle;
    private TextView stateMessage;
    private Button stateAction;
    private ProgressBar progressBar;
    private ScrollView contentScroll;
    private ImageButton menuButton;
    private boolean isTablet;
    private boolean progressShowing;
    private boolean hasContent;
    private boolean initialSelectionHandled;
    private boolean homeFallbackStarted;
    private int selectedSectionId = -1;
    private int requestedSectionId = -1;
    private int pendingScrollY;
    private Disposable homeFallbackAction;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();

        isTablet = getResources().getBoolean(R.bool.is_tablet);
        configureAdaptiveNavigation();

        if (savedInstanceState != null) {
            selectedSectionId = savedInstanceState.getInt(STATE_SECTION_ID, -1);
            pendingScrollY = savedInstanceState.getInt(STATE_SCROLL_Y, 0);
            initialSelectionHandled = selectedSectionId != -1;
        }

        presenter = BrowsePresenter.instance(this);
        presenter.setView(this);
        presenter.onViewInitialized();
        handleDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        presenter.setView(this);
        presenter.onViewResumed();
    }

    @Override
    protected void onPause() {
        presenter.onViewPaused();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        disposeHomeFallback();
        presenter.onViewDestroyed();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SECTION_ID, selectedSectionId);
        outState.putInt(STATE_SCROLL_Y, contentScroll.getScrollY());
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        navigationItems = findViewById(R.id.navigation_items);
        navigationPanel = findViewById(R.id.navigation_panel);
        shelvesContainer = findViewById(R.id.shelves_container);
        stateContainer = findViewById(R.id.state_container);
        screenTitle = findViewById(R.id.screen_title);
        screenSubtitle = findViewById(R.id.screen_subtitle);
        stateMessage = findViewById(R.id.state_message);
        stateAction = findViewById(R.id.state_action);
        progressBar = findViewById(R.id.loading_indicator);
        contentScroll = findViewById(R.id.content_scroll);
        menuButton = findViewById(R.id.menu_button);
        findViewById(R.id.search_button).setOnClickListener(view ->
                startActivity(new Intent(this, SearchActivity.class)));
    }

    private void configureAdaptiveNavigation() {
        if (isTablet) {
            menuButton.setVisibility(View.GONE);
            navigationPanel.setVisibility(View.VISIBLE);
        } else {
            menuButton.setOnClickListener(view -> toggleNavigation());
            navigationPanel.setVisibility(View.GONE);
            navigationPanel.setOnClickListener(view -> { });
        }
    }

    private void toggleNavigation() {
        navigationPanel.setVisibility(navigationPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void closePhoneNavigation() {
        if (!isTablet) {
            navigationPanel.setVisibility(View.GONE);
        }
    }

    @Override
    public void addSection(int index, BrowseSection section) {
        runUi(() -> {
            int safeIndex = Math.max(0, Math.min(index, sections.size()));
            sections.remove(section);
            sections.add(safeIndex, section);
            renderNavigation();
        });
    }

    @Override
    public void removeSection(BrowseSection section) {
        runUi(() -> {
            sections.remove(section);
            renderNavigation();
        });
    }

    @Override
    public void removeAllSections() {
        runUi(() -> {
            sections.clear();
            navigationItems.removeAllViews();
        });
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        runUi(() -> {
            if (sections.isEmpty()) {
                return;
            }

            int targetIndex = Math.max(0, Math.min(index, sections.size() - 1));
            boolean explicitNavigation = requestedSectionId == sections.get(targetIndex).getId();
            if (!initialSelectionHandled) {
                int homeIndex = findSectionIndex(MediaGroup.TYPE_HOME);
                if (homeIndex >= 0) {
                    targetIndex = homeIndex;
                }
                initialSelectionHandled = true;
            } else if (!explicitNavigation && selectedSectionId != -1) {
                int restoredIndex = findSectionIndex(selectedSectionId);
                if (restoredIndex >= 0 && visibleGroups.isEmpty()) {
                    targetIndex = restoredIndex;
                }
            }

            BrowseSection section = sections.get(targetIndex);
            requestedSectionId = -1;
            disposeHomeFallback();
            homeFallbackStarted = false;
            selectedSectionId = section.getId();
            screenTitle.setText(section.getTitle());
            screenSubtitle.setText(sectionSubtitle(section.getId()));
            visibleGroups.clear();
            hasContent = false;
            shelvesContainer.removeAllViews();
            hideState();
            renderNavigation();
            presenter.onSectionFocused(section.getId());
            closePhoneNavigation();

            if (focusOnContent) {
                contentScroll.requestFocus();
            }
        });
    }

    @Override
    public void updateSection(VideoGroup group) {
        if (group == null) {
            return;
        }
        runUi(() -> {
            BrowseSection section = group.getSection();
            if (homeFallbackStarted && section != null && section.getId() == MediaGroup.TYPE_HOME) {
                return;
            }
            applyVideoGroup(group);
        });
    }

    @Override
    public void updateSection(SettingsGroup group) {
        runUi(() -> renderSettings(group));
    }

    @Override
    public void clearSection(BrowseSection section) {
        runUi(() -> {
            if (section != null && section.getId() == selectedSectionId) {
                if (homeFallbackStarted && section.getId() == MediaGroup.TYPE_HOME) {
                    return;
                }
                visibleGroups.clear();
                hasContent = false;
                renderShelves();
            }
        });
    }

    @Override
    public void selectSectionItem(int index) {
        // Vertical position restoration is handled by ScrollView state in this mobile adapter.
    }

    @Override
    public void selectSectionItem(Video item) {
        // Item-level restoration will move to RecyclerView stable IDs as M2 is expanded.
    }

    @Override
    public void showError(ErrorFragmentData data) {
        runUi(() -> {
            progressShowing = false;
            progressBar.setVisibility(View.GONE);
            if (selectedSectionId == MediaGroup.TYPE_HOME && !hasContent) {
                if (!homeFallbackStarted) {
                    loadSignedOutHomeFallback();
                }
                return;
            }
            boolean accountBlocked = isLibrarySignInState(data);
            showState(libraryStateMessage(data),
                    accountBlocked ? getString(R.string.account_sign_in_m6) : data != null ? data.getActionText() : null,
                    accountBlocked
                            ? () -> Toast.makeText(this, R.string.account_sign_in_m6_message, Toast.LENGTH_LONG).show()
                            : data != null ? data::onAction : null);
        });
    }

    private String sectionSubtitle(int sectionId) {
        if (sectionId == MediaGroup.TYPE_SUBSCRIPTIONS) return getString(R.string.subscriptions_subtitle);
        if (sectionId == MediaGroup.TYPE_HISTORY) return getString(R.string.history_subtitle);
        if (sectionId == MediaGroup.TYPE_USER_PLAYLISTS) return getString(R.string.playlists_subtitle);
        return getString(R.string.real_smarttube_data);
    }

    private String libraryStateMessage(ErrorFragmentData data) {
        String fallback = data != null ? data.getMessage() : getString(R.string.empty_section);
        if (!isLibrarySignInState(data)) return fallback;
        if (selectedSectionId == MediaGroup.TYPE_SUBSCRIPTIONS) return getString(R.string.subscriptions_sign_in_state);
        if (selectedSectionId == MediaGroup.TYPE_HISTORY) return getString(R.string.history_sign_in_state);
        if (selectedSectionId == MediaGroup.TYPE_USER_PLAYLISTS) return getString(R.string.playlists_sign_in_state);
        return fallback;
    }

    private boolean isLibrarySignInState(ErrorFragmentData data) {
        boolean librarySection = selectedSectionId == MediaGroup.TYPE_SUBSCRIPTIONS
                || selectedSectionId == MediaGroup.TYPE_HISTORY
                || selectedSectionId == MediaGroup.TYPE_USER_PLAYLISTS;
        return librarySection && data != null
                && TextUtils.equals(data.getActionText(), getString(R.string.action_signin));
    }

    @Override
    public void showProgressBar(boolean show) {
        runUi(() -> {
            progressShowing = show;
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && !hasContent) {
                showState(getString(R.string.loading_section, screenTitle.getText()), null, null);
            } else if (!show && hasContent) {
                hideState();
            } else if (!show && !hasContent && stateContainer.getVisibility() != View.VISIBLE) {
                showState(getString(R.string.empty_section), getString(R.string.retry), presenter::refresh);
            }
        });
    }

    @Override
    public boolean isProgressBarShowing() {
        return progressShowing;
    }

    @Override
    public void focusOnContent() {
        runUi(contentScroll::requestFocus);
    }

    @Override
    public boolean isEmpty() {
        return !hasContent;
    }

    @Override
    public void updateBadge() {
        // Account badge support is tracked for the authenticated M6 surface.
    }

    private void applyVideoGroup(VideoGroup incoming) {
        BrowseSection section = incoming.getSection();
        if (section != null && section.getId() != selectedSectionId) {
            return;
        }

        int action = incoming.getAction();
        if (action == VideoGroup.ACTION_REPLACE) {
            visibleGroups.clear();
        }

        if (action == VideoGroup.ACTION_REMOVE || action == VideoGroup.ACTION_REMOVE_AUTHOR) {
            removeVideos(incoming, action == VideoGroup.ACTION_REMOVE_AUTHOR);
        } else if (action == VideoGroup.ACTION_SYNC) {
            syncVideos(incoming);
        } else if (!incoming.isEmpty()) {
            int existingIndex = findGroupIndex(incoming);
            if (existingIndex >= 0) {
                visibleGroups.set(existingIndex, incoming);
            } else if (action == VideoGroup.ACTION_PREPEND) {
                visibleGroups.add(0, incoming);
            } else {
                visibleGroups.add(incoming);
            }
        }

        renderShelves();
    }

    private void loadSignedOutHomeFallback() {
        BrowseSection homeSection = findSection(MediaGroup.TYPE_HOME);
        if (homeSection == null) {
            showState(getString(R.string.empty_section), getString(R.string.retry), presenter::refresh);
            return;
        }

        disposeHomeFallback();
        homeFallbackStarted = true;
        progressShowing = true;
        progressBar.setVisibility(View.VISIBLE);
        screenSubtitle.setText(R.string.signed_out_explore_feed);
        showState(getString(R.string.loading_signed_out_feed), null, null);

        homeFallbackAction = YouTubeServiceManager.instance()
                .getContentService()
                .getSearchObserve(getString(R.string.signed_out_discovery_query))
                .subscribe(
                        mediaGroups -> runUi(() -> {
                            visibleGroups.clear();
                            boolean first = true;
                            for (MediaGroup mediaGroup : mediaGroups) {
                                if (mediaGroup == null || mediaGroup.isEmpty()) {
                                    continue;
                                }
                                VideoGroup group = VideoGroup.from(mediaGroup, homeSection);
                                if (first) {
                                    group.setAction(VideoGroup.ACTION_REPLACE);
                                    first = false;
                                }
                                applyVideoGroup(group);
                            }
                            progressShowing = false;
                            progressBar.setVisibility(View.GONE);
                            if (!hasContent) {
                                showState(getString(R.string.empty_section), getString(R.string.retry), this::loadSignedOutHomeFallback);
                            }
                        }),
                        error -> runUi(() -> {
                            progressShowing = false;
                            progressBar.setVisibility(View.GONE);
                            showState(getString(R.string.load_failed, error.getMessage()), getString(R.string.retry), this::loadSignedOutHomeFallback);
                        }));
    }

    private void disposeHomeFallback() {
        if (homeFallbackAction != null && !homeFallbackAction.isDisposed()) {
            homeFallbackAction.dispose();
        }
        homeFallbackAction = null;
    }

    private void removeVideos(VideoGroup incoming, boolean byAuthor) {
        for (VideoGroup group : visibleGroups) {
            List<Video> removals = new ArrayList<>();
            for (Video candidate : group.getVideos()) {
                for (Video removed : incoming.getVideos()) {
                    boolean match = byAuthor
                            ? TextUtils.equals(candidate.getAuthor(), removed.getAuthor())
                            : candidate.equals(removed);
                    if (match) {
                        removals.add(candidate);
                    }
                }
            }
            for (Video removal : removals) {
                group.remove(removal);
            }
        }
    }

    private void syncVideos(VideoGroup incoming) {
        for (VideoGroup group : visibleGroups) {
            for (Video replacement : incoming.getVideos()) {
                int index = group.indexOf(replacement);
                if (index >= 0) {
                    group.remove(group.get(index));
                    group.add(index, replacement);
                }
            }
        }
    }

    private int findGroupIndex(VideoGroup target) {
        for (int i = 0; i < visibleGroups.size(); i++) {
            VideoGroup candidate = visibleGroups.get(i);
            if (candidate.getId() == target.getId()
                    || (!TextUtils.isEmpty(candidate.getTitle()) && TextUtils.equals(candidate.getTitle(), target.getTitle()))) {
                return i;
            }
        }
        return -1;
    }

    private void renderNavigation() {
        navigationItems.removeAllViews();
        for (BrowseSection section : sections) {
            TextView item = new TextView(this);
            item.setText(section.getTitle());
            item.setTextSize(isTablet ? 15 : 17);
            item.setTextColor(section.getId() == selectedSectionId
                    ? color(R.color.smarttube_text_primary)
                    : color(R.color.smarttube_text_secondary));
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setTypeface(Typeface.DEFAULT, section.getId() == selectedSectionId ? Typeface.BOLD : Typeface.NORMAL);
            item.setPadding(dp(18), dp(14), dp(18), dp(14));
            item.setBackground(createNavigationBackground(section.getId() == selectedSectionId));
            item.setContentDescription(getString(R.string.navigate_to_section, section.getTitle()));
            item.setOnClickListener(view -> {
                int selectedIndex = findSectionIndex(section.getId());
                if (selectedIndex >= 0) {
                    requestedSectionId = section.getId();
                    selectSection(selectedIndex, true);
                }
            });
            item.setOnLongClickListener(view -> {
                Toast.makeText(this, getString(R.string.section_customization_m8), Toast.LENGTH_SHORT).show();
                return true;
            });
            navigationItems.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private GradientDrawable createNavigationBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? color(R.color.smarttube_surface_selected) : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private void renderShelves() {
        shelvesContainer.removeAllViews();
        hasContent = false;

        for (VideoGroup group : visibleGroups) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            hasContent = true;
            shelvesContainer.addView(createShelf(group));
        }

        if (hasContent) {
            hideState();
            screenSubtitle.setText(getResources().getQuantityString(
                    R.plurals.loaded_shelves, visibleGroups.size(), visibleGroups.size()));
            if (pendingScrollY > 0) {
                contentScroll.post(() -> {
                    contentScroll.scrollTo(0, pendingScrollY);
                    pendingScrollY = 0;
                });
            }
        } else if (!progressShowing) {
            showState(getString(R.string.empty_section), getString(R.string.retry), presenter::refresh);
        }
    }

    private View createShelf(VideoGroup group) {
        LinearLayout shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setPadding(0, 0, 0, dp(24));

        TextView title = new TextView(this);
        title.setText(TextUtils.isEmpty(group.getTitle()) ? getString(R.string.recommended) : group.getTitle());
        title.setTextColor(color(R.color.smarttube_text_primary));
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(18), dp(8), dp(18), dp(12));
        shelf.addView(title);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(dp(12), 0, dp(12), 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int count = Math.min(MAX_CARDS_PER_SHELF, group.getVideos().size());
        for (int i = 0; i < count; i++) {
            row.addView(createVideoCard(group.get(i), group.isShorts()));
        }
        scroller.addView(row);
        shelf.addView(scroller);
        return shelf;
    }

    private View createVideoCard(Video video, boolean shorts) {
        int cardWidth = dp(shorts ? 168 : 292);
        int imageHeight = dp(shorts ? 272 : 164);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(8));
        card.setBackground(createCardBackground());
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(buildCardDescription(video));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(color(R.color.smarttube_surface));
        card.addView(thumbnail, new LinearLayout.LayoutParams(cardWidth - dp(12), imageHeight));
        Glide.with(getApplicationContext())
                .load(video.getCardImageUrl())
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_placeholder)
                .centerCrop()
                .into(thumbnail);

        TextView title = new TextView(this);
        title.setText(TextUtils.isEmpty(video.getTitle()) ? getString(R.string.untitled_video) : video.getTitle());
        title.setTextColor(color(R.color.smarttube_text_primary));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(4), dp(9), dp(4), 0);
        card.addView(title, new LinearLayout.LayoutParams(cardWidth - dp(12), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout metadataRow = new LinearLayout(this);
        metadataRow.setOrientation(LinearLayout.HORIZONTAL);
        metadataRow.setGravity(Gravity.CENTER_VERTICAL);
        metadataRow.setPadding(dp(4), dp(5), dp(2), 0);

        TextView metadata = new TextView(this);
        metadata.setText(buildMetadata(video));
        metadata.setTextColor(color(R.color.smarttube_text_secondary));
        metadata.setTextSize(13);
        metadata.setMaxLines(1);
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        metadataRow.addView(metadata, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView overflow = new TextView(this);
        overflow.setText("⋮");
        overflow.setTextColor(color(R.color.smarttube_text_primary));
        overflow.setTextSize(24);
        overflow.setGravity(Gravity.CENTER);
        overflow.setContentDescription(getString(R.string.more_actions_for, video.getTitle()));
        overflow.setOnClickListener(view -> showVideoActions(video));
        metadataRow.addView(overflow, new LinearLayout.LayoutParams(dp(40), dp(40)));
        card.addView(metadataRow, new LinearLayout.LayoutParams(cardWidth - dp(12), ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(view -> openWatch(video));
        card.setOnLongClickListener(view -> {
            showVideoActions(video);
            return true;
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(6), 0, dp(6), 0);
        card.setLayoutParams(params);
        return card;
    }

    private GradientDrawable createCardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(R.color.smarttube_surface));
        drawable.setCornerRadius(dp(14));
        return drawable;
    }

    private String buildMetadata(Video video) {
        String author = video.getAuthor();
        CharSequence second = video.getSecondTitle();
        if (!TextUtils.isEmpty(author)) {
            return author;
        }
        return second != null ? second.toString() : "";
    }

    private String buildCardDescription(Video video) {
        String title = TextUtils.isEmpty(video.getTitle()) ? getString(R.string.untitled_video) : video.getTitle();
        String metadata = buildMetadata(video);
        return TextUtils.isEmpty(metadata) ? title : title + ", " + metadata;
    }

    private void showVideoActions(Video video) {
        String link = video.videoId != null ? "https://youtu.be/" + video.videoId : null;
        List<String> actions = new ArrayList<>();
        actions.add(getString(R.string.open_watch_route));
        if (link != null) {
            actions.add(getString(R.string.copy_video_link));
        }

        new AlertDialog.Builder(this)
                .setTitle(video.getTitle())
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        openWatch(video);
                    } else if (link != null) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.video_link), link));
                        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openWatch(Video video) {
        if (video == null || TextUtils.isEmpty(video.videoId)) {
            Toast.makeText(this, R.string.non_video_route_m3, Toast.LENGTH_SHORT).show();
            return;
        }

        presenter.onVideoItemSelected(video);
        startActivity(WatchActivity.createIntent(this, video));
    }

    private void renderSettings(SettingsGroup group) {
        shelvesContainer.removeAllViews();
        visibleGroups.clear();
        hasContent = group != null && !group.isEmpty();
        if (!hasContent) {
            showState(getString(R.string.empty_section), null, null);
            return;
        }

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (SettingsItem item : group.getItems()) {
            TextView row = new TextView(this);
            row.setText(item.title);
            row.setTextColor(color(R.color.smarttube_text_primary));
            row.setTextSize(17);
            row.setPadding(dp(20), dp(18), dp(20), dp(18));
            row.setBackground(createCardBackground());
            row.setOnClickListener(view -> {
                if (item.onClick != null) {
                    item.onClick.run();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(16), dp(6), dp(16), dp(6));
            grid.addView(row, params);
        }
        shelvesContainer.addView(grid);
        hideState();
    }

    private void showState(String message, @Nullable String actionText, @Nullable Runnable action) {
        stateMessage.setText(message);
        if (!TextUtils.isEmpty(actionText) && action != null) {
            stateAction.setText(actionText);
            stateAction.setVisibility(View.VISIBLE);
            stateAction.setOnClickListener(view -> action.run());
        } else {
            stateAction.setVisibility(View.GONE);
            stateAction.setOnClickListener(null);
        }
        stateContainer.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        stateContainer.setVisibility(View.GONE);
    }

    private int findSectionIndex(int sectionId) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).getId() == sectionId) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private BrowseSection findSection(int sectionId) {
        int index = findSectionIndex(sectionId);
        return index >= 0 ? sections.get(index) : null;
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        Uri uri = intent.getData();
        String videoId = uri.getQueryParameter("v");
        if (TextUtils.isEmpty(videoId) && "youtu.be".equals(uri.getHost()) && uri.getPathSegments().size() > 0) {
            videoId = uri.getPathSegments().get(0);
        }
        if (!TextUtils.isEmpty(videoId)) {
            Video video = Video.from(videoId);
            video.title = getString(R.string.shared_video);
            startActivity(WatchActivity.createIntent(this, video));
        }
    }

    private void runUi(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            runOnUiThread(action);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(@ColorRes int resourceId) {
        return ContextCompat.getColor(this, resourceId);
    }
}
