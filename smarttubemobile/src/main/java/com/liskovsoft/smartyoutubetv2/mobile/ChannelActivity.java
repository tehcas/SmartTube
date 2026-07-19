package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;

import io.reactivex.disposables.Disposable;

public final class ChannelActivity extends Activity {
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_CHANNEL_ID = "channel_id";

    private LinearLayout results;
    private TextView state;
    private ProgressBar progress;
    private Disposable loadAction;
    private boolean hasContent;

    static Intent createIntent(Context context, Video video) {
        return new Intent(context, ChannelActivity.class)
                .putExtra(EXTRA_TITLE, video.getTitle())
                .putExtra(EXTRA_CHANNEL_ID, video.channelId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        results = findViewById(R.id.detail_results);
        state = findViewById(R.id.detail_state);
        progress = findViewById(R.id.detail_progress);
        findViewById(R.id.detail_back).setOnClickListener(view -> finish());

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String channelId = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        ((TextView) findViewById(R.id.detail_title)).setText(
                TextUtils.isEmpty(title) ? getString(R.string.channel) : title);
        ((TextView) findViewById(R.id.detail_subtitle)).setText(R.string.real_channel_content);

        if (TextUtils.isEmpty(channelId)) {
            showError(getString(R.string.channel_id_missing));
        } else {
            loadChannel(channelId);
        }
    }

    @Override
    protected void onDestroy() {
        if (loadAction != null && !loadAction.isDisposed()) loadAction.dispose();
        super.onDestroy();
    }

    private void loadChannel(String channelId) {
        progress.setVisibility(View.VISIBLE);
        state.setText(R.string.loading_channel);
        state.setVisibility(View.VISIBLE);
        loadAction = YouTubeServiceManager.instance().getContentService()
                .getChannelObserve(channelId)
                .subscribe(
                        groups -> runOnUiThread(() -> renderGroups(groups)),
                        error -> runOnUiThread(() -> showError(error.getMessage())));
    }

    private void renderGroups(List<MediaGroup> mediaGroups) {
        progress.setVisibility(View.GONE);
        if (mediaGroups == null || mediaGroups.isEmpty()) {
            showError(getString(R.string.channel_empty));
            return;
        }
        for (MediaGroup mediaGroup : mediaGroups) {
            if (mediaGroup == null || mediaGroup.isEmpty()) continue;
            hasContent = true;
            state.setVisibility(View.GONE);
            VideoGroup group = VideoGroup.from(mediaGroup);

            TextView heading = new TextView(this);
            heading.setText(group.getTitle());
            heading.setTextColor(ContextCompat.getColor(this, R.color.smarttube_text_primary));
            heading.setTextSize(20);
            heading.setPadding(dp(18), dp(18), dp(18), dp(8));
            results.addView(heading);

            for (Video video : group.getVideos()) {
                results.addView(MobileResultCardFactory.create(this, video, new MobileResultCardFactory.Listener() {
                    @Override public void onOpen(Video item) { openItem(item); }
                    @Override public void onMore(Video item) { showActions(item); }
                }));
            }
        }
        if (!hasContent) showError(getString(R.string.channel_empty));
    }

    private void openItem(Video video) {
        if (video.hasVideo()) startActivity(WatchActivity.createIntent(this, video));
        else if (video.isPlaylistAsChannel() || video.hasPlaylist()) startActivity(CollectionActivity.createIntent(this, video));
        else if (video.isChannel()) startActivity(createIntent(this, video));
    }

    private void showActions(Video video) {
        MobileActionDialog.show(this, video, this::openItem);
    }

    private void showError(String message) {
        progress.setVisibility(View.GONE);
        if (!hasContent) {
            state.setText(TextUtils.isEmpty(message) ? getString(R.string.channel_empty) : message);
            state.setVisibility(View.VISIBLE);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
