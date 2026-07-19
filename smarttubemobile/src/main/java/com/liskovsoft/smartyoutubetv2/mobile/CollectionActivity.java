package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.disposables.Disposable;

public final class CollectionActivity extends Activity {
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_PLAYLIST_ID = "playlist_id";
    private static final String EXTRA_RELOAD_KEY = "reload_key";
    private static final String EXTRA_PARAMS = "params";
    private static final String EXTRA_IMAGE = "image";
    private static final String EXTRA_TYPE = "type";

    private LinearLayout results;
    private TextView state;
    private ProgressBar progress;
    private ScrollView scroll;
    private Disposable loadAction;
    private Disposable continuationAction;
    private MediaGroup currentGroup;
    private boolean hasContent;

    static Intent createIntent(Context context, Video video) {
        MobileSelectionStore.put(video);
        return new Intent(context, CollectionActivity.class)
                .putExtra(EXTRA_TITLE, video.getTitle())
                .putExtra(EXTRA_PLAYLIST_ID, video.playlistId)
                .putExtra(EXTRA_RELOAD_KEY, video.reloadPageKey)
                .putExtra(EXTRA_PARAMS, video.playlistParams)
                .putExtra(EXTRA_IMAGE, video.getCardImageUrl())
                .putExtra(EXTRA_TYPE, video.itemType);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        results = findViewById(R.id.detail_results);
        state = findViewById(R.id.detail_state);
        progress = findViewById(R.id.detail_progress);
        scroll = findViewById(R.id.detail_scroll);
        findViewById(R.id.detail_back).setOnClickListener(view -> finish());

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        ((TextView) findViewById(R.id.detail_title)).setText(
                TextUtils.isEmpty(title) ? getString(R.string.playlist) : title);
        ((TextView) findViewById(R.id.detail_subtitle)).setText(R.string.real_playlist_content);

        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            View child = scroll.getChildAt(0);
            if (child != null && currentGroup != null
                    && scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - dp(160)) {
                loadContinuation();
            }
        });
        loadCollection();
    }

    @Override
    protected void onDestroy() {
        dispose(loadAction);
        dispose(continuationAction);
        super.onDestroy();
    }

    private void loadCollection() {
        Video video = MobileSelectionStore.peek();
        if (video == null) {
            video = new Video();
            video.title = getIntent().getStringExtra(EXTRA_TITLE);
            video.playlistId = getIntent().getStringExtra(EXTRA_PLAYLIST_ID);
            video.reloadPageKey = getIntent().getStringExtra(EXTRA_RELOAD_KEY);
            video.playlistParams = getIntent().getStringExtra(EXTRA_PARAMS);
            video.cardImageUrl = getIntent().getStringExtra(EXTRA_IMAGE);
            video.itemType = getIntent().getIntExtra(EXTRA_TYPE, -1);
        }

        progress.setVisibility(View.VISIBLE);
        state.setText(R.string.loading_playlist);
        state.setVisibility(View.VISIBLE);
        loadAction = YouTubeServiceManager.instance().getContentService()
                .getGroupObserve(video.mediaItem != null ? video.mediaItem : SimpleMediaItem.from(video))
                .subscribe(
                        group -> runOnUiThread(() -> renderGroup(group)),
                        error -> runOnUiThread(() -> showError(error.getMessage())));
    }

    private void renderGroup(MediaGroup mediaGroup) {
        progress.setVisibility(View.GONE);
        if (mediaGroup == null || mediaGroup.isEmpty()) {
            if (!hasContent) showError(getString(R.string.playlist_empty));
            return;
        }
        currentGroup = mediaGroup;
        hasContent = true;
        state.setVisibility(View.GONE);
        VideoGroup group = VideoGroup.from(mediaGroup);
        for (Video video : group.getVideos()) {
            results.addView(MobileResultCardFactory.create(this, video, new MobileResultCardFactory.Listener() {
                @Override public void onOpen(Video item) { openItem(item); }
                @Override public void onMore(Video item) { showActions(item); }
            }));
        }
    }

    private void loadContinuation() {
        if (currentGroup == null || (continuationAction != null && !continuationAction.isDisposed())) return;
        continuationAction = YouTubeServiceManager.instance().getContentService()
                .continueGroupObserve(currentGroup)
                .subscribe(
                        group -> runOnUiThread(() -> renderGroup(group)),
                        error -> runOnUiThread(() -> showError(error.getMessage())));
    }

    private void openItem(Video video) {
        if (video.hasVideo()) startActivity(WatchActivity.createIntent(this, video));
        else if (video.isPlaylistAsChannel() || video.hasPlaylist()) startActivity(createIntent(this, video));
        else if (video.isChannel()) startActivity(ChannelActivity.createIntent(this, video));
    }

    private void showActions(Video video) {
        MobileActionDialog.show(this, video, this::openItem);
    }

    private void showError(String message) {
        progress.setVisibility(View.GONE);
        if (!hasContent) {
            state.setText(TextUtils.isEmpty(message) ? getString(R.string.playlist_empty) : message);
            state.setVisibility(View.VISIBLE);
        }
    }

    private void dispose(@Nullable Disposable disposable) {
        if (disposable != null && !disposable.isDisposed()) disposable.dispose();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
