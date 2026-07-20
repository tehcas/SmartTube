package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.Rational;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextOutput;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

public final class WatchActivity extends Activity implements MobilePlaybackService.Listener, TextOutput {

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
    private Button rewindButton;
    private Button playPauseButton;
    private Button forwardButton;
    private Button nextButton;
    private Button qualityButton;
    private Button speedButton;
    private Button captionsButton;
    private Button chaptersButton;
    private Button pipButton;
    private Button actionsButton;
    private SeekBar progress;
    private StoryboardPreviewView storyboardPreview;
    private View playerContainer;
    private View controlsOverlay;
    private ImageButton backButton;
    private MobilePlaybackService playbackService;
    private boolean bound;
    private boolean userSeeking;
    private boolean isLandscape;
    private boolean controlsHideScheduled;
    private CustomTarget<Bitmap> storyboardTarget;
    private String loadedStoryboardUrl;
    private Bitmap loadedStoryboardBitmap;
    private int pendingStoryboardFrame;
    private int pendingStoryboardRows = 1;
    private int pendingStoryboardColumns = 1;
    private long pendingStoryboardPositionMs;
    private int seekIncrementMs;
    private int normalPlayerContainerHeight;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideStoryboard = () -> storyboardPreview.setVisibility(View.GONE);
    private final Runnable hideControls = () -> {
        controlsHideScheduled = false;
        if (isLandscape && playbackService != null && playbackService.isPlaying()) {
            controlsOverlay.setVisibility(View.INVISIBLE);
            backButton.setVisibility(View.INVISIBLE);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playbackService = ((MobilePlaybackService.LocalBinder) service).getService();
            bound = true;
            playerView.setPlayer(playbackService.getPlayer());
            playbackService.getPlayer().addTextOutput(WatchActivity.this);
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
        isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        applySystemUi();
        setContentView(R.layout.activity_watch);
        bindViews();
        playerView.setResizeMode(PlayerData.instance(this).getResizeMode());
        bindActions();
        renderIntent(getIntent());
        requestNotificationPermissionIfNeeded();
        if (savedInstanceState == null) {
            startRequestedPlayback(getIntent());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemUi();
    }

    private void applySystemUi() {
        if (isLandscape) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void requestPictureInPicture() {
        if (Build.VERSION.SDK_INT < 26
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                || playbackService == null) {
            Toast.makeText(this, R.string.picture_in_picture_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            enterPictureInPictureMode(params);
        } catch (IllegalStateException error) {
            Toast.makeText(this, R.string.picture_in_picture_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && playbackService != null
                && playbackService.isPlaying() && !isInPictureInPictureMode()) {
            requestPictureInPicture();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPictureInPictureMode) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode);
        backButton.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        controlsOverlay.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        storyboardPreview.setVisibility(View.GONE);
        if (!isLandscape) {
            ViewGroup.LayoutParams params = playerContainer.getLayoutParams();
            params.height = inPictureInPictureMode
                    ? ViewGroup.LayoutParams.MATCH_PARENT : normalPlayerContainerHeight;
            playerContainer.setLayoutParams(params);
        }
        if (!inPictureInPictureMode) {
            applySystemUi();
            renderPlaybackState();
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
        rewindButton = findViewById(R.id.watch_rewind);
        playPauseButton = findViewById(R.id.watch_play_pause);
        forwardButton = findViewById(R.id.watch_forward);
        nextButton = findViewById(R.id.watch_next);
        qualityButton = findViewById(R.id.watch_quality);
        speedButton = findViewById(R.id.watch_speed);
        captionsButton = findViewById(R.id.watch_captions);
        chaptersButton = findViewById(R.id.watch_chapters);
        pipButton = findViewById(R.id.watch_pip);
        actionsButton = findViewById(R.id.watch_actions);
        progress = findViewById(R.id.watch_progress);
        storyboardPreview = findViewById(R.id.watch_storyboard_preview);
        playerContainer = findViewById(R.id.watch_player_container);
        normalPlayerContainerHeight = playerContainer.getLayoutParams().height;
        controlsOverlay = findViewById(R.id.watch_controls_overlay);
        backButton = findViewById(R.id.watch_back);
        seekIncrementMs = PlayerData.instance(this).getSeekIncrementMs();
        updateSeekLabels();
    }

    private void bindActions() {
        backButton.setOnClickListener(view -> finish());
        previousButton.setOnClickListener(view -> {
            if (playbackService != null) playbackService.skipPrevious();
            showAndScheduleControls();
        });
        rewindButton.setOnClickListener(view -> {
            seekBy(-seekIncrementMs);
            showAndScheduleControls();
        });
        playPauseButton.setOnClickListener(view -> {
            if (playbackService == null) return;
            if (playbackService.isPlaying()) playbackService.pause(); else playbackService.play();
            showAndScheduleControls();
        });
        forwardButton.setOnClickListener(view -> {
            seekBy(seekIncrementMs);
            showAndScheduleControls();
        });
        nextButton.setOnClickListener(view -> {
            if (playbackService != null) playbackService.skipNext();
            showAndScheduleControls();
        });
        qualityButton.setOnClickListener(view -> showQualityDialog());
        speedButton.setOnClickListener(view -> showSpeedDialog());
        captionsButton.setOnClickListener(view -> showCaptionsDialog());
        captionsButton.setOnLongClickListener(view -> showCaptionSizeDialog());
        chaptersButton.setOnClickListener(view -> showChaptersDialog());
        pipButton.setOnClickListener(view -> requestPictureInPicture());
        rewindButton.setOnLongClickListener(view -> showSeekIncrementDialog());
        forwardButton.setOnLongClickListener(view -> showSeekIncrementDialog());
        playerView.setOnClickListener(view -> toggleLandscapeControls());
        configureSubtitleView();
        playerView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
            return isLandscape;
        });
        progress.setMax(1_000);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (!fromUser || playbackService == null || playbackService.getDurationMs() <= 0) return;
                long target = playbackService.getDurationMs() * value / seekBar.getMax();
                positionView.setText(getString(R.string.playback_position,
                        formatTime(target), formatTime(playbackService.getDurationMs())));
                showStoryboardPreview(target);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
                uiHandler.removeCallbacks(hideStoryboard);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                if (playbackService != null && playbackService.getDurationMs() > 0) {
                    playbackService.seekTo(playbackService.getDurationMs() * seekBar.getProgress() / seekBar.getMax());
                }
                uiHandler.postDelayed(hideStoryboard, 1_000L);
                showAndScheduleControls();
            }
        });
        actionsButton.setOnClickListener(view -> showActionsDialog());
    }

    private void showActionsDialog() {
        if (playbackService == null || playbackService.getCurrentVideo() == null) return;
        String[] actions = {
                getString(R.string.playback_queue),
                getString(R.string.copy_video_link),
                getString(R.string.share_video),
                getString(R.string.video_information),
                getString(R.string.display_mode),
                getString(R.string.sleep_timer)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_actions)
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0: showQueueDialog(); break;
                        case 1: copyCurrentLink(); break;
                        case 2: shareCurrentLink(); break;
                        case 3: showVideoInformationDialog(); break;
                        case 4: showDisplayModeDialog(); break;
                        case 5: showSleepTimerDialog(); break;
                        default: break;
                    }
                })
                .show();
    }

    private void showQueueDialog() {
        if (playbackService == null) return;
        List<MobilePlaybackService.QueueEntry> queue = playbackService.getQueue();
        String[] labels = new String[queue.size()];
        for (int i = 0; i < queue.size(); i++) {
            MobilePlaybackService.QueueEntry item = queue.get(i);
            String label = getString(R.string.queue_item, i + 1, item.title, item.author);
            labels[i] = i == playbackService.getQueueIndex()
                    ? getString(R.string.queue_current, label) : label;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.playback_queue)
                .setSingleChoiceItems(labels, playbackService.getQueueIndex(), (dialog, which) -> {
                    playbackService.selectQueueItem(which);
                    dialog.dismiss();
                })
                .show();
    }

    private void copyCurrentLink() {
        String videoId = currentVideoId();
        if (TextUtils.isEmpty(videoId)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.video_link), "https://youtu.be/" + videoId));
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareCurrentLink() {
        String videoId = currentVideoId();
        if (TextUtils.isEmpty(videoId)) return;
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/" + videoId);
        startActivity(Intent.createChooser(share, getString(R.string.share_video_title)));
    }

    private void showVideoInformationDialog() {
        if (playbackService == null) return;
        Video video = playbackService.getCurrentVideo();
        if (video == null) return;
        String quality = selectedTrackLabel(
                playbackService.getVideoTrackOptions(), getString(R.string.quality_auto));
        String captions = selectedTrackLabel(
                playbackService.getSubtitleTrackOptions(), getString(R.string.captions_off));
        String message = getString(R.string.video_information_value,
                video.getTitle(), video.getAuthor(), video.videoId,
                formatTime(playbackService.getPositionMs()), formatTime(playbackService.getDurationMs()),
                playbackService.getQueueIndex() + 1, playbackService.getQueueSize(),
                quality, formatSpeed(playbackService.getPlaybackSpeed()), captions);
        new AlertDialog.Builder(this)
                .setTitle(R.string.video_information)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showDisplayModeDialog() {
        PlayerData data = PlayerData.instance(this);
        int[] modes = {
                PlayerData.RESIZE_MODE_DEFAULT,
                PlayerData.RESIZE_MODE_FIT_WIDTH,
                PlayerData.RESIZE_MODE_FIT_HEIGHT,
                PlayerData.RESIZE_MODE_FIT_BOTH,
                PlayerData.RESIZE_MODE_STRETCH
        };
        String[] labels = {
                getString(R.string.display_fit),
                getString(R.string.display_fit_width),
                getString(R.string.display_fit_height),
                getString(R.string.display_zoom),
                getString(R.string.display_stretch)
        };
        int selected = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == data.getResizeMode()) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.display_mode)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    data.setResizeMode(modes[which]);
                    playerView.setResizeMode(modes[which]);
                    dialog.dismiss();
                })
                .show();
    }

    private void showSleepTimerDialog() {
        if (playbackService == null) return;
        int[] minutes = { 0, 1, 15, 30, 60 };
        String[] labels = {
                getString(R.string.sleep_timer_off),
                getResources().getQuantityString(R.plurals.sleep_timer_minutes, 1, 1),
                getResources().getQuantityString(R.plurals.sleep_timer_minutes, 15, 15),
                getResources().getQuantityString(R.plurals.sleep_timer_minutes, 30, 30),
                getResources().getQuantityString(R.plurals.sleep_timer_minutes, 60, 60)
        };
        long remainingMinutes = (playbackService.getSleepTimerRemainingMs() + 59_999L) / 60_000L;
        int selected = 0;
        for (int i = 1; i < minutes.length; i++) {
            if (remainingMinutes == minutes[i]) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sleep_timer)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    playbackService.setSleepTimerMinutes(minutes[which]);
                    dialog.dismiss();
                })
                .show();
    }

    private void seekBy(long deltaMs) {
        if (playbackService == null) return;
        long duration = playbackService.getDurationMs();
        long target = Math.max(0, playbackService.getPositionMs() + deltaMs);
        if (duration > 0) target = Math.min(duration, target);
        playbackService.seekTo(target);
    }

    private boolean showSeekIncrementDialog() {
        int[] increments = { 5_000, 10_000, 15_000, 30_000, 60_000 };
        String[] labels = new String[increments.length];
        int selected = 0;
        for (int i = 0; i < increments.length; i++) {
            int seconds = increments[i] / 1_000;
            labels[i] = getResources().getQuantityString(R.plurals.seek_seconds, seconds, seconds);
            if (increments[i] == seekIncrementMs) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.seek_interval)
                .setSingleChoiceItems(labels, selected, (target, which) -> {
                    seekIncrementMs = increments[which];
                    PlayerData.instance(this).setSeekIncrementMs(seekIncrementMs);
                    updateSeekLabels();
                    target.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return true;
    }

    private void updateSeekLabels() {
        int seconds = Math.max(1, seekIncrementMs / 1_000);
        rewindButton.setText(getString(R.string.seek_back_seconds, seconds));
        forwardButton.setText(getString(R.string.seek_forward_seconds, seconds));
    }

    private void showQualityDialog() {
        if (playbackService == null) return;
        List<MobilePlaybackService.TrackOption> options = playbackService.getVideoTrackOptions();
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.tracks_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[options.size()];
        int selected = 0;
        for (int i = 0; i < options.size(); i++) {
            labels[i] = options.get(i).label;
            if (options.get(i).selected) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.quality)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    playbackService.selectVideoTrack(options.get(which));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSpeedDialog() {
        if (playbackService == null) return;
        float[] speeds = { 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f };
        String[] labels = new String[speeds.length];
        int selected = 3;
        float current = playbackService.getPlaybackSpeed();
        for (int i = 0; i < speeds.length; i++) {
            labels[i] = formatSpeed(speeds[i]) + "×";
            if (Math.abs(current - speeds[i]) < 0.001f) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.speed)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    playbackService.setPlaybackSpeed(speeds[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCaptionsDialog() {
        if (playbackService == null) return;
        List<MobilePlaybackService.TrackOption> options = playbackService.getSubtitleTrackOptions();
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.tracks_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[options.size()];
        int selected = 0;
        for (int i = 0; i < options.size(); i++) {
            labels[i] = options.get(i).label;
            if (options.get(i).selected) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.captions)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    playbackService.selectSubtitleTrack(options.get(which));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showChaptersDialog() {
        if (playbackService == null) return;
        List<MobilePlaybackService.ChapterOption> chapters = playbackService.getChapters();
        if (chapters.isEmpty()) return;
        String[] labels = new String[chapters.size()];
        int selected = 0;
        long position = playbackService.getPositionMs();
        for (int i = 0; i < chapters.size(); i++) {
            MobilePlaybackService.ChapterOption chapter = chapters.get(i);
            labels[i] = formatTime(chapter.startTimeMs) + " — " + chapter.title;
            if (chapter.startTimeMs <= position) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.chapters)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    playbackService.seekTo(chapters.get(which).startTimeMs);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean showCaptionSizeDialog() {
        PlayerData data = PlayerData.instance(this);
        float[] scales = { 0.8f, 1f, 1.25f };
        String[] labels = {
                getString(R.string.caption_size_small),
                getString(R.string.caption_size_normal),
                getString(R.string.caption_size_large)
        };
        int selected = 1;
        for (int i = 0; i < scales.length; i++) {
            if (Math.abs(data.getSubtitleScale() - scales[i]) < 0.01f) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.caption_size)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    data.setSubtitleScale(scales[which]);
                    configureSubtitleView();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return true;
    }

    private void configureSubtitleView() {
        if (playerView == null || playerView.getSubtitleView() == null) return;
        float scale = PlayerData.instance(this).getSubtitleScale();
        playerView.getSubtitleView().setApplyEmbeddedStyles(false);
        playerView.getSubtitleView().setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * scale);
        playerView.getSubtitleView().setBottomPaddingFraction(isLandscape ? 0.20f : 0.08f);
    }

    @Override
    public void onCues(List<Cue> cues) {
        if (playerView == null || playerView.getSubtitleView() == null) return;
        List<Cue> centered = new ArrayList<>();
        for (Cue cue : cues) {
            if (cue != null && cue.text != null) centered.add(new Cue(cue.text));
        }
        playerView.getSubtitleView().setCues(centered);
    }

    private void showStoryboardPreview(long positionMs) {
        pendingStoryboardPositionMs = positionMs;
        MediaItemStoryboard storyboard = playbackService != null ? playbackService.getStoryboard() : null;
        if (storyboard == null || storyboard.getGroupSize() == null
                || storyboard.getGroupDurationMS() <= 0) {
            storyboardPreview.setVisibility(View.GONE);
            return;
        }
        MediaItemStoryboard.Size size = storyboard.getGroupSize();
        int frameDuration = Math.max(1, size.getDurationEachMS());
        int groupNumber = (int) (positionMs / storyboard.getGroupDurationMS());
        pendingStoryboardFrame = (int) ((positionMs % storyboard.getGroupDurationMS()) / frameDuration);
        pendingStoryboardRows = Math.max(1, size.getRowCount());
        pendingStoryboardColumns = Math.max(1, size.getColCount());
        String url = storyboard.getGroupUrl(groupNumber);
        if (TextUtils.isEmpty(url)) {
            storyboardPreview.setVisibility(View.GONE);
            return;
        }
        storyboardPreview.setVisibility(View.VISIBLE);
        storyboardPreview.setContentDescription(getString(R.string.seek_preview_at, formatTime(positionMs)));
        if (url.equals(loadedStoryboardUrl) && loadedStoryboardBitmap != null) {
            storyboardPreview.setStoryboardFrame(loadedStoryboardBitmap, pendingStoryboardFrame,
                    pendingStoryboardRows, pendingStoryboardColumns);
            return;
        }
        if (storyboardTarget != null) return;
        final int requestedFrame = pendingStoryboardFrame;
        final int requestedRows = pendingStoryboardRows;
        final int requestedColumns = pendingStoryboardColumns;
        storyboardTarget = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                storyboardTarget = null;
                loadedStoryboardUrl = url;
                loadedStoryboardBitmap = resource;
                storyboardPreview.setStoryboardFrame(resource, requestedFrame,
                        requestedRows, requestedColumns);
                if (storyboardPreview.getVisibility() == View.VISIBLE
                        && pendingStoryboardPositionMs != positionMs) {
                    showStoryboardPreview(pendingStoryboardPositionMs);
                }
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                storyboardTarget = null;
            }
        };
        Glide.with(this).asBitmap().load(url).into(storyboardTarget);
    }

    private void toggleLandscapeControls() {
        if (!isLandscape) return;
        if (controlsOverlay.getVisibility() == View.VISIBLE) {
            uiHandler.removeCallbacks(hideControls);
            controlsHideScheduled = false;
            controlsOverlay.setVisibility(View.INVISIBLE);
            backButton.setVisibility(View.INVISIBLE);
        } else {
            showAndScheduleControls();
        }
    }

    private void showAndScheduleControls() {
        controlsOverlay.setVisibility(View.VISIBLE);
        backButton.setVisibility(View.VISIBLE);
        if (!isLandscape || playbackService == null || !playbackService.isPlaying()) {
            uiHandler.removeCallbacks(hideControls);
            controlsHideScheduled = false;
            return;
        }
        if (!controlsHideScheduled) {
            controlsHideScheduled = true;
            uiHandler.postDelayed(hideControls, 3_000L);
        }
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
        uiHandler.removeCallbacks(hideControls);
        controlsHideScheduled = false;
        detachService();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (storyboardTarget != null) Glide.with(this).clear(storyboardTarget);
        storyboardTarget = null;
        loadedStoryboardBitmap = null;
        super.onDestroy();
    }

    private void detachService() {
        if (playbackService != null) {
            playbackService.removeListener(this);
            playbackService.getPlayer().removeTextOutput(this);
        }
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
        renderPlaybackOptions();
        queueView.setText(getString(R.string.queue_position,
                Math.max(0, playbackService.getQueueIndex() + 1), playbackService.getQueueSize()));

        long positionMs = playbackService.getPositionMs();
        long durationMs = playbackService.getDurationMs();
        positionView.setText(getString(R.string.playback_position,
                formatTime(positionMs), durationMs > 0 ? formatTime(durationMs) : "--:--"));
        if (!userSeeking && durationMs > 0) {
            progress.setProgress((int) Math.min(progress.getMax(), positionMs * progress.getMax() / durationMs));
        }
        if (!playbackService.isPlaying()) showAndScheduleControls();
        else if (isLandscape && controlsOverlay.getVisibility() == View.VISIBLE && !controlsHideScheduled) {
            showAndScheduleControls();
        }
    }

    private void renderPlaybackOptions() {
        List<MobilePlaybackService.TrackOption> videoOptions = playbackService.getVideoTrackOptions();
        String quality = selectedTrackLabel(videoOptions, getString(R.string.quality_auto));
        qualityButton.setText(shortTrackLabel(quality));
        qualityButton.setContentDescription(getString(R.string.quality_button, quality));
        qualityButton.setEnabled(!videoOptions.isEmpty());

        String speed = formatSpeed(playbackService.getPlaybackSpeed());
        speedButton.setText(getString(R.string.speed_short, speed));
        speedButton.setContentDescription(getString(R.string.speed_button, speed));

        List<MobilePlaybackService.TrackOption> subtitleOptions = playbackService.getSubtitleTrackOptions();
        String captions = selectedTrackLabel(subtitleOptions, getString(R.string.captions_off));
        boolean captionsEnabled = !captions.equals(getString(R.string.captions_off));
        captionsButton.setText(captionsEnabled ? R.string.captions_on : R.string.captions_short_off);
        captionsButton.setContentDescription(getString(R.string.captions_button, captions));
        captionsButton.setEnabled(!subtitleOptions.isEmpty());

        int chapterCount = playbackService.getChapters().size();
        chaptersButton.setText(chapterCount > 0
                ? getString(R.string.chapters_short_count, chapterCount) : getString(R.string.chapters));
        chaptersButton.setContentDescription(getString(R.string.chapters_count, chapterCount));
        chaptersButton.setEnabled(chapterCount > 0);

        long sleepRemaining = playbackService.getSleepTimerRemainingMs();
        actionsButton.setContentDescription(sleepRemaining > 0
                ? getString(R.string.sleep_timer_active, formatTime(sleepRemaining))
                : getString(R.string.player_actions));
    }

    private static String selectedTrackLabel(List<MobilePlaybackService.TrackOption> options, String fallback) {
        for (MobilePlaybackService.TrackOption option : options) {
            if (option.selected) return option.label;
        }
        return fallback;
    }

    private static String shortTrackLabel(String label) {
        int separator = label.indexOf(',');
        String shortLabel = separator > 0 ? label.substring(0, separator) : label;
        int presetSuffix = shortLabel.indexOf(") ");
        return presetSuffix >= 0 ? shortLabel.substring(presetSuffix + 2) : shortLabel;
    }

    private static String formatSpeed(float speed) {
        return speed == (int) speed ? Integer.toString((int) speed) : Float.toString(speed);
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
