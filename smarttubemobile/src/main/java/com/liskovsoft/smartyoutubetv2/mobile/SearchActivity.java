package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.MediaServiceSearchTagProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;

import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SearchActivity extends Activity implements SearchView {
    private static final int VOICE_REQUEST = 301;
    private static final long SUGGESTION_DELAY_MS = 250;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Tag> visibleTags = new ArrayList<>();
    private final Runnable suggestionRequest = this::requestSuggestions;

    private SearchPresenter presenter;
    private MediaServiceSearchTagProvider tagsProvider;
    private EditText queryInput;
    private LinearLayout suggestionRow;
    private LinearLayout resultsContainer;
    private TextView stateText;
    private ProgressBar progressBar;
    private ScrollView resultsScroll;
    private Button loadMoreButton;
    private Video paginationVideo;
    private boolean searchSubmitted;
    private boolean paginationRequested;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        bindViews();
        configureInput();

        presenter = SearchPresenter.instance(this);
        presenter.setView(this);
        presenter.onViewInitialized();
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
        handler.removeCallbacks(suggestionRequest);
        presenter.onViewDestroyed();
        super.onDestroy();
    }

    private void bindViews() {
        queryInput = findViewById(R.id.search_query);
        suggestionRow = findViewById(R.id.search_suggestions);
        resultsContainer = findViewById(R.id.search_results);
        stateText = findViewById(R.id.search_state);
        progressBar = findViewById(R.id.search_progress);
        resultsScroll = findViewById(R.id.search_scroll);
        loadMoreButton = findViewById(R.id.search_load_more);

        findViewById(R.id.search_back).setOnClickListener(view -> finish());
        findViewById(R.id.search_submit).setOnClickListener(view -> submitSearch());
        findViewById(R.id.search_voice).setOnClickListener(view -> startVoiceRecognition());
        findViewById(R.id.search_filter).setOnClickListener(view -> showTypeFilter());
        loadMoreButton.setOnClickListener(view -> requestMore());

        resultsScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            View child = resultsScroll.getChildAt(0);
            if (child != null && paginationVideo != null
                    && resultsScroll.getScrollY() + resultsScroll.getHeight() >= child.getHeight() - dp(160)
                    && !paginationRequested) {
                requestMore();
            }
        });
    }

    private void configureInput() {
        queryInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submitSearch();
                return true;
            }
            return false;
        });
        queryInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                handler.removeCallbacks(suggestionRequest);
                handler.postDelayed(suggestionRequest, SUGGESTION_DELAY_MS);
            }
            @Override public void afterTextChanged(Editable text) { }
        });
    }

    private void submitSearch() {
        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) {
            stateText.setText(R.string.enter_search_query);
            stateText.setVisibility(View.VISIBLE);
            return;
        }
        searchSubmitted = true;
        stateText.setVisibility(View.GONE);
        presenter.onSearch(query);
        hideKeyboard();
    }

    private void requestSuggestions() {
        if (tagsProvider == null) {
            return;
        }
        String query = queryInput.getText().toString().trim();
        tagsProvider.search(query, tags -> runOnUiThread(() -> renderSuggestions(tags)));
    }

    private void renderSuggestions(@Nullable List<Tag> tags) {
        visibleTags.clear();
        suggestionRow.removeAllViews();
        if (tags == null) {
            return;
        }
        visibleTags.addAll(tags);
        for (Tag tag : tags) {
            TextView chip = new TextView(this);
            chip.setText(tag.tag);
            chip.setTextColor(color(R.color.smarttube_text_primary));
            chip.setTextSize(14);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setPadding(dp(16), dp(10), dp(16), dp(10));
            chip.setBackground(chipBackground());
            chip.setOnClickListener(view -> {
                queryInput.setText(tag.tag);
                queryInput.setSelection(queryInput.length());
                submitSearch();
            });
            chip.setOnLongClickListener(view -> {
                MediaServiceManager.instance().removeSearchTag(tag.tag);
                removeSearchTag(tag);
                Toast.makeText(this, R.string.search_history_removed, Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(5), dp(4), dp(5), dp(4));
            suggestionRow.addView(chip, params);
        }
        if (queryInput.getText().toString().trim().isEmpty() && !tags.isEmpty()) {
            TextView clear = new TextView(this);
            clear.setText(R.string.clear_search_history);
            clear.setTextColor(color(R.color.smarttube_accent));
            clear.setTextSize(14);
            clear.setPadding(dp(16), dp(10), dp(16), dp(10));
            clear.setBackground(chipBackground());
            clear.setOnClickListener(view -> new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.clear_search_history)
                    .setMessage(R.string.clear_search_history_confirm)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        MediaServiceManager.instance().clearSearchHistory();
                        clearSearchTags();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(5), dp(4), dp(5), dp(4));
            suggestionRow.addView(clear, params);
        }
    }

    private GradientDrawable chipBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(R.color.smarttube_surface));
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    @Override
    public void updateSearch(VideoGroup group) {
        runOnUiThread(() -> {
            if (group == null || group.isEmpty()) {
                return;
            }
            TextView heading = new TextView(this);
            heading.setText(group.getTitle());
            heading.setTextColor(color(R.color.smarttube_text_primary));
            heading.setTextSize(20);
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            heading.setPadding(dp(18), dp(20), dp(18), dp(10));
            resultsContainer.addView(heading);

            for (Video video : group.getVideos()) {
                resultsContainer.addView(createResultCard(video));
            }
            if (!TextUtils.isEmpty(group.getNextPageKey()) && !group.getVideos().isEmpty()) {
                paginationVideo = group.getVideos().get(group.getVideos().size() - 1);
            }
            loadMoreButton.setVisibility(paginationVideo != null ? View.VISIBLE : View.GONE);
            paginationRequested = false;
            stateText.setVisibility(View.GONE);
        });
    }

    private void requestMore() {
        if (paginationVideo == null || paginationRequested) return;
        paginationRequested = true;
        loadMoreButton.setVisibility(View.GONE);
        Video requestedPage = paginationVideo;
        paginationVideo = null;
        presenter.onScrollEnd(requestedPage);
    }

    private View createResultCard(Video video) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(chipBackground());
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(resultDescription(video));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new LinearLayout.LayoutParams(dp(156), dp(92)));
        Glide.with(getApplicationContext()).load(video.getCardImageUrl())
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_placeholder)
                .centerCrop().into(image);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, dp(8), 0);
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView type = new TextView(this);
        type.setText(resultType(video));
        type.setTextColor(color(R.color.smarttube_accent));
        type.setTextSize(11);
        type.setAllCaps(true);
        text.addView(type);

        TextView title = new TextView(this);
        title.setText(TextUtils.isEmpty(video.getTitle()) ? getString(R.string.untitled_video) : video.getTitle());
        title.setTextColor(color(R.color.smarttube_text_primary));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);

        TextView metadata = new TextView(this);
        metadata.setText(video.getAuthor());
        metadata.setTextColor(color(R.color.smarttube_text_secondary));
        metadata.setTextSize(13);
        metadata.setMaxLines(1);
        text.addView(metadata);

        TextView overflow = new TextView(this);
        overflow.setText("⋮");
        overflow.setTextColor(color(R.color.smarttube_text_primary));
        overflow.setTextSize(25);
        overflow.setGravity(Gravity.CENTER);
        overflow.setContentDescription(getString(R.string.more_actions_for, video.getTitle()));
        overflow.setOnClickListener(view -> showResultActions(video));
        card.addView(overflow, new LinearLayout.LayoutParams(dp(44), dp(56)));

        card.setOnClickListener(view -> openResult(video));
        card.setOnLongClickListener(view -> {
            showResultActions(video);
            return true;
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(12), dp(5), dp(12), dp(5));
        card.setLayoutParams(params);
        return card;
    }

    private String resultType(Video video) {
        if (video.isPlaylistAsChannel() || (!video.hasVideo() && video.hasPlaylist())) return getString(R.string.result_playlist);
        if (video.isChannel()) return getString(R.string.result_channel);
        if (video.isShorts) return getString(R.string.result_shorts);
        if (video.isLive) return getString(R.string.result_live);
        return getString(R.string.result_video);
    }

    private String resultDescription(Video video) {
        return String.format(Locale.US, "%s, %s, %s", resultType(video), video.getTitle(), video.getAuthor());
    }

    private void openResult(Video video) {
        presenter.onVideoItemSelected(video);
        if (video.isPlaylistAsChannel() || (!video.hasVideo() && video.hasPlaylist())) {
            startActivity(CollectionActivity.createIntent(this, video));
        } else if (video.isChannel()) {
            startActivity(ChannelActivity.createIntent(this, video));
        } else if (video.hasVideo()) {
            startActivity(WatchActivity.createIntent(this, video));
        } else {
            Toast.makeText(this, R.string.route_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void showResultActions(Video video) {
        MobileActionDialog.show(this, video, this::openResult);
    }

    private void showTypeFilter() {
        String[] labels = {
                getString(R.string.filter_any),
                getString(R.string.filter_videos),
                getString(R.string.filter_channels),
                getString(R.string.filter_playlists)
        };
        int[] values = {0, SearchOptions.TYPE_VIDEO, SearchOptions.TYPE_CHANNEL, SearchOptions.TYPE_PLAYLIST};
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.filter_result_type)
                .setItems(labels, (dialog, which) -> presenter.setTypeOptions(values[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void clearSearch() {
        runOnUiThread(() -> {
            resultsContainer.removeAllViews();
            paginationVideo = null;
            paginationRequested = false;
            loadMoreButton.setVisibility(View.GONE);
            stateText.setText(R.string.searching);
            stateText.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void clearSearchTags() {
        runOnUiThread(() -> renderSuggestions(null));
    }

    @Override
    public void removeSearchTag(Tag tag) {
        runOnUiThread(() -> {
            visibleTags.remove(tag);
            renderSuggestions(new ArrayList<>(visibleTags));
        });
    }

    @Override
    public void setTagsProvider(MediaServiceSearchTagProvider provider) {
        tagsProvider = provider;
        requestSuggestions();
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show && resultsContainer.getChildCount() == 0 && searchSubmitted) {
                stateText.setText(R.string.no_search_results);
                stateText.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void startSearch(String searchText) {
        if (!TextUtils.isEmpty(searchText)) {
            queryInput.setText(searchText);
            queryInput.setSelection(queryInput.length());
            submitSearch();
        } else {
            queryInput.requestFocus();
            queryInput.post(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(queryInput, InputMethodManager.SHOW_IMPLICIT));
        }
    }

    @Override
    public String getSearchText() {
        return queryInput.getText().toString().trim();
    }

    @Override
    public void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search_prompt));
        try {
            startActivityForResult(intent, VOICE_REQUEST);
        } catch (Exception error) {
            Toast.makeText(this, R.string.voice_search_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                queryInput.setText(results.get(0));
                submitSearch();
            }
        }
    }

    @Override
    public void finishReally() {
        finish();
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(queryInput.getWindowToken(), 0);
        queryInput.clearFocus();
    }

    private int color(int resourceId) {
        return ContextCompat.getColor(this, resourceId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
