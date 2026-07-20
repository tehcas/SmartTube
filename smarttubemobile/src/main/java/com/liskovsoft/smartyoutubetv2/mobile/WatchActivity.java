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
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment;
import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SponsorBlockData;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.disposables.Disposable;
import io.reactivex.Observable;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Arrays;

public final class WatchActivity extends Activity implements MobilePlaybackService.Listener, TextOutput {

    private static final String EXTRA_VIDEO_ID = MobilePlaybackService.EXTRA_VIDEO_ID;
    private static final String EXTRA_TITLE = MobilePlaybackService.EXTRA_TITLE;
    private static final String EXTRA_AUTHOR = MobilePlaybackService.EXTRA_AUTHOR;
    private static final String EXTRA_IMAGE = MobilePlaybackService.EXTRA_IMAGE;
    private static final String CONTROL_ORDER = "control_order";
    private static final String[] ORDERED_CONTROL_KEYS = {
            "quality", "speed", "captions", "chapters", "pip"
    };
    private static final String DEFAULT_CONTROL_ORDER = "quality,speed,captions,chapters,pip";
    private static final int READBACK_LIKE = 1;
    private static final int READBACK_SUBSCRIPTION = 2;
    private static final int READBACK_PLAYLIST = 3;

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
    private SponsorMarkerView sponsorMarkers;
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
    private Disposable commentsAction;
    private Disposable liveChatAction;
    private Disposable accountAction;
    private AlertDialog accountProgressDialog;
    private AlertDialog liveChatDialog;
    private AlertDialog sponsorDialog;
    private long lastSponsorEventSequence;
    private final List<String> liveChatLines = new ArrayList<>();
    private SharedPreferences controlPreferences;
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
        controlPreferences = getSharedPreferences("mobile_player_controls", MODE_PRIVATE);
        bindViews();
        playerView.setResizeMode(PlayerData.instance(this).getResizeMode());
        applyVideoTransform();
        applyControlVisibility();
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
        sponsorMarkers = findViewById(R.id.watch_sponsor_markers);
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
                getString(R.string.video_transform),
                getString(R.string.sleep_timer),
                getString(R.string.comments_and_chat),
                getString(R.string.customize_controls),
                getString(R.string.related_videos),
                getString(R.string.video_qr_code),
                getString(R.string.debug_statistics),
                getString(R.string.sponsorblock_settings),
                getString(R.string.account_actions)
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
                        case 5: showVideoTransformDialog(); break;
                        case 6: showSleepTimerDialog(); break;
                        case 7: showCommentsCapabilityDialog(); break;
                        case 8: showCustomizeControlsDialog(); break;
                        case 9: showSuggestionsDialog(); break;
                        case 10: showQrCodeDialog(); break;
                        case 11: showDebugStatisticsDialog(); break;
                        case 12: showSponsorBlockSettingsDialog(); break;
                        case 13: showAuthenticatedActionsDialog(); break;
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

    private void showSuggestionsDialog() {
        if (playbackService == null) return;
        List<MobilePlaybackService.SuggestionOption> suggestions = playbackService.getSuggestions();
        if (suggestions.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.related_videos)
                    .setMessage(R.string.related_videos_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String[] labels = new String[suggestions.size()];
        for (int i = 0; i < suggestions.size(); i++) {
            MobilePlaybackService.SuggestionOption item = suggestions.get(i);
            List<String> details = new ArrayList<>();
            if (!TextUtils.isEmpty(item.author)) details.add(item.author);
            if (!TextUtils.isEmpty(item.section)) details.add(item.section);
            labels[i] = fallback(item.title)
                    + (details.isEmpty() ? "" : "\n" + TextUtils.join(" • ", details));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.related_videos)
                .setItems(labels, (dialog, which) -> playbackService.selectSuggestion(which))
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

    private void showQrCodeDialog() {
        String videoId = currentVideoId();
        if (TextUtils.isEmpty(videoId)) return;
        String link = "https://youtu.be/" + videoId;
        try {
            int size = 640;
            BitMatrix matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            ImageView qrView = new ImageView(this);
            int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                    getResources().getDisplayMetrics());
            qrView.setPadding(padding, padding, padding, padding);
            qrView.setBackgroundColor(Color.WHITE);
            qrView.setImageBitmap(bitmap);
            qrView.setContentDescription(link);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.video_qr_code)
                    .setMessage(link)
                    .setView(qrView)
                    .setPositiveButton(R.string.copy_video_link, (dialog, which) -> copyCurrentLink())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (WriterException error) {
            Toast.makeText(this, R.string.qr_generation_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showDebugStatisticsDialog() {
        if (playbackService == null) return;
        MobilePlaybackService.DebugSnapshot stats = playbackService.getDebugSnapshot();
        String message = getString(R.string.debug_statistics_value,
                stats.playerState, getString(stats.playWhenReady ? R.string.state_yes : R.string.state_no),
                stats.windowIndex, stats.sourceType, formatTime(stats.positionMs), formatTime(stats.durationMs),
                formatTime(stats.bufferedMs), stats.bufferedPercent, stats.videoFormat, stats.audioFormat,
                stats.renderedVideoBuffers, stats.droppedVideoBuffers, stats.skippedVideoBuffers,
                stats.maxConsecutiveDropped, playbackService.getQueueIndex() + 1, playbackService.getQueueSize())
                + "\n\nSponsorBlock segments: " + playbackService.getSponsorSegments().size()
                + "\nLast SponsorBlock skip: " + fallback(playbackService.getLastSponsorSkipSummary());
        new AlertDialog.Builder(this)
                .setTitle(R.string.debug_statistics)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showSponsorBlockSettingsDialog() {
        if (playbackService == null) return;
        SponsorBlockData data = SponsorBlockData.instance(this);
        String channelId = playbackService.getChannelId();
        boolean excluded = !TextUtils.isEmpty(channelId) && data.isChannelExcluded(channelId);
        String[] choices = {
                getString(R.string.sponsorblock_enabled,
                        getString(data.isSponsorBlockEnabled() ? R.string.state_on : R.string.state_off)),
                getString(excluded ? R.string.sponsorblock_channel_excluded
                        : R.string.sponsorblock_channel_included),
                getString(R.string.sponsorblock_category_actions),
                getString(R.string.sponsorblock_marker_categories),
                getString(R.string.sponsorblock_segment_count, playbackService.getSponsorSegments().size())
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.sponsorblock_settings)
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        data.setSponsorBlockEnabled(!data.isSponsorBlockEnabled());
                        playbackService.reloadSponsorBlock();
                        showSponsorBlockSettingsDialog();
                    } else if (which == 1) {
                        if (TextUtils.isEmpty(channelId)) {
                            Toast.makeText(this, R.string.sponsorblock_channel_unavailable, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        data.toggleExcludeChannel(channelId);
                        playbackService.reloadSponsorBlock();
                        showSponsorBlockSettingsDialog();
                    } else if (which == 2) {
                        showSponsorCategoryActionsDialog();
                    } else if (which == 3) {
                        showSponsorMarkerCategoriesDialog();
                    } else {
                        showSponsorSegmentsDialog();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSponsorCategoryActionsDialog() {
        SponsorBlockData data = SponsorBlockData.instance(this);
        List<String> categories = new ArrayList<>(data.getAllCategories());
        String[] labels = new String[categories.size()];
        for (int i = 0; i < categories.size(); i++) {
            String category = categories.get(i);
            labels[i] = sponsorCategoryLabel(category) + " — " + sponsorActionLabel(data.getAction(category));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sponsorblock_category_actions)
                .setItems(labels, (dialog, which) -> showSponsorActionDialog(categories.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSponsorActionDialog(String category) {
        SponsorBlockData data = SponsorBlockData.instance(this);
        int[] values = {
                SponsorBlockData.ACTION_DO_NOTHING,
                SponsorBlockData.ACTION_SKIP_ONLY,
                SponsorBlockData.ACTION_SKIP_WITH_TOAST,
                SponsorBlockData.ACTION_SHOW_DIALOG
        };
        String[] labels = {
                getString(R.string.sponsorblock_action_none),
                getString(R.string.sponsorblock_action_skip),
                getString(R.string.sponsorblock_action_toast),
                getString(R.string.sponsorblock_action_confirm)
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == data.getAction(category)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle(sponsorCategoryLabel(category))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    data.setAction(category, values[which]);
                    playbackService.reloadSponsorBlock();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSponsorMarkerCategoriesDialog() {
        SponsorBlockData data = SponsorBlockData.instance(this);
        List<String> categories = new ArrayList<>(data.getAllCategories());
        String[] labels = new String[categories.size()];
        boolean[] selected = new boolean[categories.size()];
        for (int i = 0; i < categories.size(); i++) {
            labels[i] = sponsorCategoryLabel(categories.get(i));
            selected[i] = data.isColorMarkerEnabled(categories.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sponsorblock_marker_categories)
                .setMultiChoiceItems(labels, selected, (dialog, which, checked) -> {
                    if (checked) data.enableColorMarker(categories.get(which));
                    else data.disableColorMarker(categories.get(which));
                    playbackService.reloadSponsorBlock();
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showSponsorSegmentsDialog() {
        List<SponsorSegment> segments = playbackService.getSponsorSegments();
        if (segments.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.sponsorblock_settings)
                    .setMessage(R.string.sponsorblock_no_segments)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String[] labels = new String[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            SponsorSegment segment = segments.get(i);
            labels[i] = sponsorCategoryLabel(segment.getCategory()) + " • "
                    + formatTime(segment.getStartMs()) + "–" + formatTime(segment.getEndMs());
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.sponsorblock_segment_count, segments.size()))
                .setItems(labels, (dialog, which) -> playbackService.seekTo(segments.get(which).getStartMs()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String sponsorActionLabel(int action) {
        if (action == SponsorBlockData.ACTION_SKIP_ONLY) return getString(R.string.sponsorblock_action_skip);
        if (action == SponsorBlockData.ACTION_SKIP_WITH_TOAST) return getString(R.string.sponsorblock_action_toast);
        if (action == SponsorBlockData.ACTION_SHOW_DIALOG) return getString(R.string.sponsorblock_action_confirm);
        return getString(R.string.sponsorblock_action_none);
    }

    private void showAuthenticatedActionsDialog() {
        if (playbackService == null) return;
        if (!YouTubeServiceManager.instance().getSignInService().isSigned()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.account_actions)
                    .setMessage(R.string.account_required)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        Video video = playbackService.getCurrentVideo();
        if (video == null || TextUtils.isEmpty(video.videoId)) return;
        showAccountProgress(getString(R.string.loading_account_state));
        if (accountAction != null) accountAction.dispose();
        accountAction = YouTubeServiceManager.instance().getMediaItemService()
                .getPlaylistsInfoObserve(video.videoId)
                .subscribe(playlists -> runOnUiThread(() -> renderAuthenticatedActions(video, playlists)),
                        error -> runOnUiThread(() -> showMutationFailure(
                                getString(R.string.account_actions), error)));
    }

    private void renderAuthenticatedActions(Video video, List<PlaylistInfo> playlists) {
        dismissAccountProgress();
        boolean liked = playbackService.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_LIKE;
        boolean subscribed = playbackService.isSubscribed();
        String[] choices = {
                getString(liked ? R.string.remove_like : R.string.like_video),
                getString(subscribed ? R.string.unsubscribe_channel : R.string.subscribe_channel),
                getString(R.string.save_to_playlist)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.account_actions)
                .setItems(choices, (dialog, which) -> {
                    MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
                    if (which == 0) {
                        Observable<Void> action = liked ? service.removeLikeObserve(video.toMediaItem())
                                : service.setLikeObserve(video.toMediaItem());
                        runAuthenticatedMutation(choices[0], action, READBACK_LIKE,
                                liked ? MediaItemMetadata.LIKE_STATUS_INDIFFERENT : MediaItemMetadata.LIKE_STATUS_LIKE,
                                null, video.videoId);
                    } else if (which == 1) {
                        String channelId = playbackService.getChannelId();
                        if (TextUtils.isEmpty(channelId)) {
                            showMutationFailure(choices[1], new IllegalStateException("Channel ID unavailable"));
                            return;
                        }
                        Observable<Void> action = subscribed ? service.unsubscribeObserve(channelId)
                                : service.subscribeObserve(channelId);
                        runAuthenticatedMutation(choices[1], action, READBACK_SUBSCRIPTION,
                                subscribed ? 0 : 1, null, video.videoId);
                    } else {
                        showPlaylistActions(video, playlists);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPlaylistActions(Video video, List<PlaylistInfo> playlists) {
        if (playlists == null || playlists.isEmpty()) {
            showMutationFailure(getString(R.string.save_to_playlist),
                    new IllegalStateException("No editable playlists available"));
            return;
        }
        String[] labels = new String[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) {
            PlaylistInfo playlist = playlists.get(i);
            labels[i] = getString(playlist.isSelected()
                            ? R.string.playlist_selected : R.string.playlist_not_selected,
                    playlist.getTitle());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_to_playlist)
                .setItems(labels, (dialog, which) -> {
                    PlaylistInfo playlist = playlists.get(which);
                    boolean saved = playlist.isSelected();
                    String actionLabel = getString(saved
                                    ? R.string.remove_from_named_playlist : R.string.add_to_named_playlist,
                            playlist.getTitle());
                    MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
                    Observable<Void> action = saved
                            ? service.removeFromPlaylistObserve(playlist.getPlaylistId(), video.videoId)
                            : service.addToPlaylistObserve(playlist.getPlaylistId(), video.toMediaItem());
                    runAuthenticatedMutation(actionLabel, action, READBACK_PLAYLIST,
                            saved ? 0 : 1, playlist.getPlaylistId(), video.videoId);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runAuthenticatedMutation(String label, Observable<Void> action, int readbackType,
                                          int expectedState, @Nullable String playlistId, String videoId) {
        showAccountProgress(getString(R.string.mutation_in_progress, label));
        if (accountAction != null) accountAction.dispose();
        accountAction = action.subscribe(ignored -> { },
                error -> runOnUiThread(() -> showMutationFailure(label, error)),
                () -> uiHandler.postDelayed(
                        () -> verifyAuthenticatedMutation(label, readbackType, expectedState, playlistId, videoId, 0),
                        1_500L));
    }

    private void verifyAuthenticatedMutation(String label, int readbackType, int expectedState,
                                             @Nullable String playlistId, String videoId, int attempt) {
        if (isFinishing() || isDestroyed()) return;
        MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        if (accountAction != null) accountAction.dispose();
        if (readbackType == READBACK_PLAYLIST) {
            accountAction = service.getPlaylistsInfoObserve(videoId).subscribe(playlists -> {
                PlaylistInfo target = findPlaylist(playlists, playlistId);
                boolean matched = target != null && target.isSelected() == (expectedState == 1);
                runOnUiThread(() -> handleMutationReadback(label, readbackType, expectedState,
                        playlistId, videoId, attempt, matched, null));
            }, error -> runOnUiThread(() -> handleMutationReadback(label, readbackType, expectedState,
                    playlistId, videoId, attempt, false, error)));
        } else {
            accountAction = service.getMetadataObserve(videoId, null, 0, null).subscribe(metadata -> {
                boolean matched = readbackType == READBACK_LIKE
                        ? metadata.getLikeStatus() == expectedState
                        : metadata.isSubscribed() == (expectedState == 1);
                if (matched && playbackService != null) playbackService.applyMetadataReadback(metadata);
                runOnUiThread(() -> handleMutationReadback(label, readbackType, expectedState,
                        playlistId, videoId, attempt, matched, null));
            }, error -> runOnUiThread(() -> handleMutationReadback(label, readbackType, expectedState,
                    playlistId, videoId, attempt, false, error)));
        }
    }

    private void handleMutationReadback(String label, int readbackType, int expectedState,
                                        @Nullable String playlistId, String videoId, int attempt,
                                        boolean matched, @Nullable Throwable error) {
        if (matched) {
            dismissAccountProgress();
            new AlertDialog.Builder(this)
                    .setTitle(R.string.account_actions)
                    .setMessage(getString(R.string.mutation_readback_confirmed, label))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } else if (attempt < 12) {
            uiHandler.postDelayed(() -> verifyAuthenticatedMutation(label, readbackType, expectedState,
                    playlistId, videoId, attempt + 1), 1_500L);
        } else {
            showMutationFailure(label, error != null ? error : new IllegalStateException("State did not change"));
        }
    }

    @Nullable
    private PlaylistInfo findPlaylist(List<PlaylistInfo> playlists, @Nullable String playlistId) {
        if (playlists == null || TextUtils.isEmpty(playlistId)) return null;
        for (PlaylistInfo playlist : playlists) {
            if (playlist != null && TextUtils.equals(playlistId, playlist.getPlaylistId())) return playlist;
        }
        return null;
    }

    private void showAccountProgress(String message) {
        dismissAccountProgress();
        accountProgressDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.account_actions)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (accountAction != null) accountAction.dispose();
                    accountAction = null;
                })
                .create();
        accountProgressDialog.show();
    }

    private void dismissAccountProgress() {
        if (accountProgressDialog != null) accountProgressDialog.dismiss();
        accountProgressDialog = null;
    }

    private void showMutationFailure(String label, Throwable error) {
        dismissAccountProgress();
        new AlertDialog.Builder(this)
                .setTitle(R.string.account_actions)
                .setMessage(getString(R.string.mutation_readback_failed, label,
                        TextUtils.isEmpty(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
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
        PlayerData data = PlayerData.instance(this);
        message += "\n\n" + getString(R.string.video_transform_information,
                displayModeLabel(data.getResizeMode()), data.getRotationAngle(),
                getString(data.isVideoFlipEnabled() ? R.string.state_yes : R.string.state_no),
                TextUtils.isEmpty(playbackService.getCommentsKey()) ? "none" : "available",
                TextUtils.isEmpty(playbackService.getLiveChatKey()) ? "none" : "available");
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

    private void showVideoTransformDialog() {
        PlayerData data = PlayerData.instance(this);
        int[] rotations = { 0, 90, 180, 270 };
        String[] labels = new String[rotations.length + 1];
        for (int i = 0; i < rotations.length; i++) {
            labels[i] = getString(R.string.rotate_degrees, rotations[i]);
        }
        labels[rotations.length] = getString(data.isVideoFlipEnabled()
                ? R.string.mirror_horizontal_on : R.string.mirror_horizontal_off);
        new AlertDialog.Builder(this)
                .setTitle(R.string.video_transform)
                .setItems(labels, (dialog, which) -> {
                    if (which < rotations.length) data.setRotationAngle(rotations[which]);
                    else data.setVideoFlipEnabled(!data.isVideoFlipEnabled());
                    applyVideoTransform();
                })
                .show();
    }

    private void applyVideoTransform() {
        View surface = playerView.getVideoSurfaceView();
        if (surface == null) return;
        PlayerData data = PlayerData.instance(this);
        surface.setRotation(data.getRotationAngle());
        surface.setScaleX(data.isVideoFlipEnabled() ? -1f : 1f);
        surface.setScaleY(1f);
    }

    private void showCustomizeControlsDialog() {
        String[] keys = { "quality", "speed", "captions", "chapters", "pip", "queue" };
        String[] labels = {
                getString(R.string.control_quality), getString(R.string.control_speed),
                getString(R.string.control_captions), getString(R.string.control_chapters),
                getString(R.string.control_pip), getString(R.string.control_queue)
        };
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = controlPreferences.getBoolean(keys[i], true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.customize_controls)
                .setMultiChoiceItems(labels, checked, (dialog, which, enabled) -> {
                    controlPreferences.edit().putBoolean(keys[which], enabled).apply();
                    applyControlVisibility();
                })
                .setNeutralButton(R.string.reorder_controls,
                        (dialog, which) -> showReorderControlsDialog())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showReorderControlsDialog() {
        List<String> order = getControlOrder();
        String[] rows = new String[order.size()];
        for (int i = 0; i < order.size(); i++) {
            rows[i] = getString(R.string.control_order_row, i + 1, controlLabel(order.get(i)));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.reorder_controls)
                .setItems(rows, (dialog, which) -> showMoveControlDialog(which))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMoveControlDialog(int index) {
        String[] moves = {
                getString(R.string.move_earlier), getString(R.string.move_later),
                getString(R.string.move_to_start), getString(R.string.move_to_end)
        };
        new AlertDialog.Builder(this)
                .setTitle(controlLabel(getControlOrder().get(index)))
                .setItems(moves, (dialog, which) -> moveControl(index, which))
                .show();
    }

    private void moveControl(int index, int action) {
        List<String> order = getControlOrder();
        if (index < 0 || index >= order.size()) return;
        String key = order.remove(index);
        int destination;
        if (action == 0) destination = Math.max(0, index - 1);
        else if (action == 1) destination = Math.min(order.size(), index + 1);
        else if (action == 2) destination = 0;
        else destination = order.size();
        order.add(destination, key);
        controlPreferences.edit().putString(CONTROL_ORDER, TextUtils.join(",", order)).apply();
        applyControlOrder();
        showReorderControlsDialog();
    }

    private List<String> getControlOrder() {
        String saved = controlPreferences.getString(CONTROL_ORDER, DEFAULT_CONTROL_ORDER);
        List<String> result = new ArrayList<>();
        if (!TextUtils.isEmpty(saved)) {
            for (String key : saved.split(",")) {
                if (Arrays.asList(ORDERED_CONTROL_KEYS).contains(key) && !result.contains(key)) result.add(key);
            }
        }
        for (String key : ORDERED_CONTROL_KEYS) if (!result.contains(key)) result.add(key);
        return result;
    }

    private String controlLabel(String key) {
        if ("quality".equals(key)) return getString(R.string.control_quality);
        if ("speed".equals(key)) return getString(R.string.control_speed);
        if ("captions".equals(key)) return getString(R.string.control_captions);
        if ("chapters".equals(key)) return getString(R.string.control_chapters);
        return getString(R.string.control_pip);
    }

    private View controlView(String key) {
        if ("quality".equals(key)) return qualityButton;
        if ("speed".equals(key)) return speedButton;
        if ("captions".equals(key)) return captionsButton;
        if ("chapters".equals(key)) return chaptersButton;
        return pipButton;
    }

    private void applyControlOrder() {
        if (qualityButton == null || controlPreferences == null) return;
        ViewGroup parent = (ViewGroup) qualityButton.getParent();
        if (parent == null || speedButton.getParent() != parent || captionsButton.getParent() != parent
                || chaptersButton.getParent() != parent || pipButton.getParent() != parent) return;
        View[] controls = { qualityButton, speedButton, captionsButton, chaptersButton, pipButton };
        ViewGroup.LayoutParams[] params = new ViewGroup.LayoutParams[controls.length];
        for (int i = 0; i < controls.length; i++) params[i] = controls[i].getLayoutParams();
        for (View control : controls) parent.removeView(control);
        for (String key : getControlOrder()) {
            View control = controlView(key);
            int original = Arrays.asList(controls).indexOf(control);
            parent.addView(control, params[original]);
        }
    }

    private void applyControlVisibility() {
        if (controlPreferences == null) return;
        applyControlOrder();
        qualityButton.setVisibility(controlPreferences.getBoolean("quality", true) ? View.VISIBLE : View.GONE);
        speedButton.setVisibility(controlPreferences.getBoolean("speed", true) ? View.VISIBLE : View.GONE);
        captionsButton.setVisibility(controlPreferences.getBoolean("captions", true) ? View.VISIBLE : View.GONE);
        chaptersButton.setVisibility(controlPreferences.getBoolean("chapters", true) ? View.VISIBLE : View.GONE);
        pipButton.setVisibility(controlPreferences.getBoolean("pip", true) ? View.VISIBLE : View.GONE);
        queueView.setVisibility(controlPreferences.getBoolean("queue", true) ? View.VISIBLE : View.GONE);
        boolean pipAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        pipButton.setEnabled(pipAvailable);
    }

    private void showCommentsCapabilityDialog() {
        if (playbackService == null) return;
        String commentsKey = playbackService.getCommentsKey();
        String liveChatKey = playbackService.getLiveChatKey();
        boolean comments = !TextUtils.isEmpty(commentsKey);
        boolean liveChat = !TextUtils.isEmpty(liveChatKey);
        Video video = playbackService.getCurrentVideo();
        String title = video != null ? video.getTitle() : getString(R.string.comments_and_chat);
        if (comments && liveChat) {
            String[] choices = { getString(R.string.open_comments), getString(R.string.open_live_chat) };
            new AlertDialog.Builder(this)
                    .setTitle(R.string.comments_and_chat)
                    .setItems(choices, (dialog, which) -> {
                        if (which == 0) loadComments(commentsKey, title, new ArrayList<>());
                        else openLiveChat(liveChatKey);
                    })
                    .show();
        } else if (comments) {
            loadComments(commentsKey, title, new ArrayList<>());
        } else if (liveChat) {
            openLiveChat(liveChatKey);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.comments_and_chat)
                    .setMessage(R.string.comments_unavailable_provider)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void openLiveChat(String key) {
        if (TextUtils.isEmpty(key)) return;
        if (liveChatAction != null) liveChatAction.dispose();
        liveChatLines.clear();
        PlayerData.instance(this).setLiveChatEnabled(true);
        liveChatDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.open_live_chat)
                .setMessage(R.string.live_chat_connecting)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        liveChatDialog.setOnDismissListener(dialog -> {
            if (liveChatAction != null) liveChatAction.dispose();
            liveChatAction = null;
            PlayerData.instance(this).setLiveChatEnabled(false);
        });
        liveChatDialog.show();
        liveChatAction = YouTubeServiceManager.instance().getLiveChatService()
                .openLiveChatObserve(key)
                .subscribe(item -> runOnUiThread(() -> appendLiveChatItem(item)),
                        error -> runOnUiThread(() -> {
                            if (liveChatDialog != null && liveChatDialog.isShowing()) {
                                liveChatDialog.setMessage(getString(R.string.live_chat_error,
                                        TextUtils.isEmpty(error.getMessage())
                                                ? error.getClass().getSimpleName() : error.getMessage()));
                            }
                        }));
    }

    private void appendLiveChatItem(ChatItem item) {
        if (item == null || TextUtils.isEmpty(item.getAuthorName()) || TextUtils.isEmpty(item.getMessage())) return;
        liveChatLines.add(item.getAuthorName() + ": " + item.getMessage().trim());
        while (liveChatLines.size() > 30) liveChatLines.remove(0);
        if (liveChatDialog != null && liveChatDialog.isShowing()) {
            liveChatDialog.setMessage(TextUtils.join("\n\n", liveChatLines));
        }
    }

    private void loadComments(String key, String title, List<CommentItem> existing) {
        if (TextUtils.isEmpty(key)) return;
        if (commentsAction != null) commentsAction.dispose();
        Toast.makeText(this, R.string.comments_loading, Toast.LENGTH_SHORT).show();
        commentsAction = YouTubeServiceManager.instance().getCommentsService()
                .getCommentsObserve(key)
                .subscribe(group -> runOnUiThread(() -> showCommentsGroup(group, title, existing)),
                        error -> runOnUiThread(() -> Toast.makeText(this,
                                getString(R.string.comments_error,
                                        TextUtils.isEmpty(error.getMessage())
                                                ? error.getClass().getSimpleName() : error.getMessage()),
                                Toast.LENGTH_LONG).show()));
    }

    private void showCommentsGroup(CommentGroup group, String title, List<CommentItem> existing) {
        if (group == null || group.getComments() == null) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.comments_title, title))
                    .setMessage(R.string.comments_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        List<CommentItem> comments = new ArrayList<>(existing);
        for (CommentItem item : group.getComments()) {
            if (item != null && !item.isEmpty()) comments.add(item);
        }
        String nextKey = group.getNextCommentsKey();
        int extra = TextUtils.isEmpty(nextKey) ? 0 : 1;
        String[] labels = new String[comments.size() + extra];
        for (int i = 0; i < comments.size(); i++) {
            CommentItem item = comments.get(i);
            String replies = item.getReplyCount();
            String header = getString(R.string.comment_header,
                    fallback(item.getAuthorName()), fallback(item.getPublishedDate()),
                    fallback(item.getLikeCount()), TextUtils.isEmpty(replies) ? "" : " • " + replies);
            labels[i] = header + "\n" + trimComment(item.getMessage());
        }
        if (extra == 1) labels[labels.length - 1] = getString(R.string.comments_load_more);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.comments_title, title))
                .setItems(labels, (dialog, which) -> {
                    if (which == comments.size()) {
                        loadComments(nextKey, title, comments);
                        return;
                    }
                    CommentItem item = comments.get(which);
                    if (!TextUtils.isEmpty(item.getNestedCommentsKey())) {
                        loadComments(item.getNestedCommentsKey(),
                                fallback(item.getAuthorName()), new ArrayList<>());
                    }
                })
                .show();
    }

    private static String fallback(String value) {
        return TextUtils.isEmpty(value) ? "—" : value;
    }

    private static String trimComment(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() > 260 ? normalized.substring(0, 257) + "…" : normalized;
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
        if (commentsAction != null) commentsAction.dispose();
        if (accountAction != null) accountAction.dispose();
        accountAction = null;
        dismissAccountProgress();
        boolean hadLiveChat = liveChatAction != null;
        if (liveChatAction != null) liveChatAction.dispose();
        liveChatAction = null;
        if (liveChatDialog != null) liveChatDialog.setOnDismissListener(null);
        liveChatDialog = null;
        if (sponsorDialog != null) {
            sponsorDialog.setOnDismissListener(null);
            sponsorDialog.dismiss();
        }
        sponsorDialog = null;
        if (hadLiveChat) PlayerData.instance(this).setLiveChatEnabled(false);
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
        renderSponsorBlock(durationMs);
        if (!playbackService.isPlaying()) showAndScheduleControls();
        else if (isLandscape && controlsOverlay.getVisibility() == View.VISIBLE && !controlsHideScheduled) {
            showAndScheduleControls();
        }
    }

    private void renderSponsorBlock(long durationMs) {
        SponsorBlockData data = SponsorBlockData.instance(this);
        List<SponsorMarkerView.Marker> markers = new ArrayList<>();
        for (SponsorSegment segment : playbackService.getSponsorSegments()) {
            if (!data.isColorMarkerEnabled(segment.getCategory())) continue;
            Integer colorRes = data.getColorRes(segment.getCategory());
            if (colorRes != null) {
                markers.add(new SponsorMarkerView.Marker(segment.getStartMs(), segment.getEndMs(),
                        ContextCompat.getColor(this, colorRes)));
            }
        }
        sponsorMarkers.setMarkers(markers, durationMs);

        MobilePlaybackService.SponsorEvent event = playbackService.getSponsorEvent();
        if (event == null) {
            if (sponsorDialog != null) sponsorDialog.dismiss();
            return;
        }
        if (event.sequence == lastSponsorEventSequence) return;
        lastSponsorEventSequence = event.sequence;
        String category = sponsorCategoryLabel(event.category);
        if (!event.confirmationRequired) {
            Toast.makeText(this, getString(R.string.sponsor_skipped, category), Toast.LENGTH_LONG).show();
            playbackService.acknowledgeSponsorEvent(event.sequence);
            return;
        }
        if (sponsorDialog != null) sponsorDialog.dismiss();
        sponsorDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.content_block_provider)
                .setMessage(getString(R.string.sponsor_confirm_skip, category))
                .setPositiveButton(R.string.skip_segment,
                        (dialog, which) -> playbackService.confirmSponsorSkip(event.sequence))
                .setNegativeButton(android.R.string.cancel,
                        (dialog, which) -> playbackService.dismissSponsorSkip(event.sequence))
                .setOnCancelListener(dialog -> playbackService.dismissSponsorSkip(event.sequence))
                .create();
        sponsorDialog.setOnDismissListener(dialog -> sponsorDialog = null);
        sponsorDialog.show();
    }

    private String sponsorCategoryLabel(String category) {
        Integer labelRes = SponsorBlockData.instance(this).getLocalizedRes(category);
        return labelRes != null ? getString(labelRes) : fallback(category);
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

    private String displayModeLabel(int mode) {
        if (mode == PlayerData.RESIZE_MODE_FIT_WIDTH) return getString(R.string.display_fit_width);
        if (mode == PlayerData.RESIZE_MODE_FIT_HEIGHT) return getString(R.string.display_fit_height);
        if (mode == PlayerData.RESIZE_MODE_FIT_BOTH) return getString(R.string.display_zoom);
        if (mode == PlayerData.RESIZE_MODE_STRETCH) return getString(R.string.display_stretch);
        return getString(R.string.display_fit);
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
