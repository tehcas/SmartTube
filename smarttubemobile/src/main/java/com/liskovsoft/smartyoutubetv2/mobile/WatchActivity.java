package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ui.PlayerView;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.Locale;

public final class WatchActivity extends Activity implements MobilePlaybackService.Listener {
    private static final String EXTRA_VIDEO_ID = MobilePlaybackService.EXTRA_VIDEO_ID;
    private static final String EXTRA_TITLE = MobilePlaybackService.EXTRA_TITLE;
    private static final String EXTRA_AUTHOR = MobilePlaybackService.EXTRA_AUTHOR;
    private static final String EXTRA_IMAGE = MobilePlaybackService.EXTRA_IMAGE;

    private PlayerView playerView;
    private TextView titleView;
    private TextView authorView;
    private TextView idView;
    private TextView stateView;
    private TextView positionView;
    private TextView queueView;
    private Button previousButton;
    private Button playPauseButton;
    private Button nextButton;
    private SeekBar progress;
    private MobilePlaybackService playbackService;
    private boolean bound;
    private boolean userSeeking;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playbackService = ((MobilePlaybackService.LocalBinder) service).getService();
            bound = true;
            playerView.setPlayer(playbackService.getPlayer());
            playbackService.addListener(WatchActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playerView.setPlayer(null);
            playbackService = null;
            bound = false;
        }
    };

    public static Intent createIntent(Context context, Video video) {
        MobileSelectionStore.put(video);
        return populateIntent(new Intent(context, WatchActivity.class), video);
    }

    static Intent createResumeIntent(Context context, @Nullable Video video) {
        Intent intent = new Intent(context, WatchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return video != null ? populateIntent(intent, video) : intent;
    }

    private static Intent populateIntent(Intent intent, Video video) {
        return intent.putExtra(EXTRA_VIDEO_ID, video.videoId)
                .putExtra(EXTRA_TITLE, video.getTitle())
                .putExtra(EXTRA_AUTHOR, video.getAuthor())
                .putExtra(EXTRA_IMAGE, video.getCardImageUrl());
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch);
        bindViews();
        bindActions();
        renderIntent(getIntent());
        requestNotificationPermissionIfNeeded();
        if (savedInstanceState == null) {
            startRequestedPlayback(getIntent());
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 4104);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderIntent(intent);
        startRequestedPlayback(intent);
    }

    private void bindViews() {
        playerView = findViewById(R.id.watch_player);
        titleView = findViewById(R.id.watch_title);
        authorView = findViewById(R.id.watch_author);
        idView = findViewById(R.id.watch_video_id);
        stateView = findViewById(R.id.watch_state);
        positionView = findViewById(R.id.watch_position);
        queueView = findViewById(R.id.watch_queue);
        previousButton = findViewById(R.id.watch_previous);
        playPauseButton = findViewById(R.id.watch_play_pause);
        nextButton = findViewById(R.id.watch_next);
        progress = findViewById(R.id.watch_progress);
    }

    private void bindActions() {
        ImageButton back = findViewById(R.id.watch_back);
        Button copyLink = findViewById(R.id.copy_watch_link);
        back.setOnClickListener(view -> finish());
        previousButton.setOnClickListener(view -> {
            if (playbackService != null) playbackService.skipPrevious();
        });
        playPauseButton.setOnClickListener(view -> {
            if (playbackService == null) return;
            if (playbackService.isPlaying()) playbackService.pause(); else playbackService.play();
        });
        nextButton.setOnClickListener(view -> {
            if (playbackService != null) playbackService.skipNext();
        });
        progress.setMax(1_000);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                if (playbackService != null && playbackService.getDurationMs() > 0) {
                    playbackService.seekTo(playbackService.getDurationMs() * seekBar.getProgress() / seekBar.getMax());
                }
            }
        });
        copyLink.setOnClickListener(view -> {
            String videoId = currentVideoId();
            if (TextUtils.isEmpty(videoId)) return;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.video_link), "https://youtu.be/" + videoId));
            Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
        });
    }

    private void renderIntent(Intent intent) {
        String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String author = intent.getStringExtra(EXTRA_AUTHOR);
        titleView.setText(TextUtils.isEmpty(title) ? getString(R.string.untitled_video) : title);
        authorView.setText(TextUtils.isEmpty(author) ? getString(R.string.smarttube_video) : author);
        idView.setText(getString(R.string.video_id_value, videoId));
    }

    private void startRequestedPlayback(Intent intent) {
        String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        if (TextUtils.isEmpty(videoId)) return;
        Video selected = MobileSelectionStore.peek();
        if (selected == null || !TextUtils.equals(selected.videoId, videoId)) {
            selected = new Video();
            selected.videoId = videoId;
            selected.title = intent.getStringExtra(EXTRA_TITLE);
            selected.author = intent.getStringExtra(EXTRA_AUTHOR);
            selected.cardImageUrl = intent.getStringExtra(EXTRA_IMAGE);
        }
        MobilePlaybackService.load(this, selected);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MobilePlaybackService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        detachService();
        super.onStop();
    }

    private void detachService() {
        if (playbackService != null) playbackService.removeListener(this);
        playerView.setPlayer(null);
        playbackService = null;
        if (bound) unbindService(connection);
        bound = false;
    }

    @Override
    public void onPlaybackStateChanged() {
        runOnUiThread(this::renderPlaybackState);
    }

    private void renderPlaybackState() {
        if (playbackService == null) return;
        Video video = playbackService.getCurrentVideo();
        if (video != null) {
            titleView.setText(video.getTitle());
            authorView.setText(video.getAuthor());
            idView.setText(getString(R.string.video_id_value, video.videoId));
        }

        String error = playbackService.getPlaybackError();
        if (!TextUtils.isEmpty(error)) stateView.setText(getString(R.string.playback_error_value, error));
        else if (playbackService.getPlayer().getPlaybackState() == com.google.android.exoplayer2.Player.STATE_BUFFERING) {
            stateView.setText(R.string.playback_buffering);
        } else if (playbackService.isPlaying()) stateView.setText(R.string.playback_playing);
        else stateView.setText(R.string.playback_paused);

        playPauseButton.setText(playbackService.isPlaying() ? R.string.pause : R.string.play);
        previousButton.setEnabled(playbackService.hasPrevious() || playbackService.getPositionMs() > 5_000L);
        nextButton.setEnabled(playbackService.hasNext());
        queueView.setText(getString(R.string.queue_position,
                Math.max(0, playbackService.getQueueIndex() + 1), playbackService.getQueueSize()));

        long positionMs = playbackService.getPositionMs();
        long durationMs = playbackService.getDurationMs();
        positionView.setText(getString(R.string.playback_position,
                formatTime(positionMs), durationMs > 0 ? formatTime(durationMs) : "--:--"));
        if (!userSeeking && durationMs > 0) {
            progress.setProgress((int) Math.min(progress.getMax(), positionMs * progress.getMax() / durationMs));
        }
    }

    private String currentVideoId() {
        Video current = playbackService != null ? playbackService.getCurrentVideo() : null;
        return current != null ? current.videoId : getIntent().getStringExtra(EXTRA_VIDEO_ID);
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0, millis / 1_000L);
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
