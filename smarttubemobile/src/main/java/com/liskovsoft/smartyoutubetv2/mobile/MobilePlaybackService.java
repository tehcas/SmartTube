package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector.Parameters;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector.SelectionOverride;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.liskovsoft.mediaserviceinterfaces.data.ChapterItem;
import com.liskovsoft.mediaserviceinterfaces.data.DeArrowData;
import com.liskovsoft.mediaserviceinterfaces.data.DislikeData;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.ExoPlayerInitializer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SponsorBlockData;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.disposables.Disposable;

/** Owns the mobile ExoPlayer, queue, MediaSession and foreground notification. */
public final class MobilePlaybackService extends Service implements Player.EventListener,
        MediaServiceManager.AccountChangeListener {
    static final String ACTION_LOAD = "org.smarttube.mobile.action.LOAD";
    static final String ACTION_PLAY = "org.smarttube.mobile.action.PLAY";
    static final String ACTION_PAUSE = "org.smarttube.mobile.action.PAUSE";
    static final String ACTION_TOGGLE = "org.smarttube.mobile.action.TOGGLE";
    static final String ACTION_NEXT = "org.smarttube.mobile.action.NEXT";
    static final String ACTION_PREVIOUS = "org.smarttube.mobile.action.PREVIOUS";

    static final String EXTRA_VIDEO_ID = "video_id";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_AUTHOR = "author";
    static final String EXTRA_IMAGE = "image";

    private static final String CHANNEL_ID = "mobile_playback";
    private static final int NOTIFICATION_ID = 4104;
    private static final long STATE_SAVE_INTERVAL_MS = 10_000L;
    private static final Pattern CHAPTER_LINE = Pattern.compile(
            "(?m)^\\s*(?:[-•]\\s*)?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\s+(.+?)\\s*$");

    interface Listener {
        void onPlaybackStateChanged();
    }

    static final class QueueEntry {
        final String videoId;
        final String originalTitle;
        final String author;
        final String originalImage;
        String title;
        String image;

        QueueEntry(String videoId, String title, String author, String image) {
            this.videoId = videoId;
            originalTitle = title;
            this.title = title;
            this.author = author;
            originalImage = image;
            this.image = image;
        }

        static QueueEntry from(Video video) {
            return new QueueEntry(video.videoId,
                    !TextUtils.isEmpty(video.title) ? video.title : video.getTitle(),
                    video.getAuthor(),
                    !TextUtils.isEmpty(video.cardImageUrl) ? video.cardImageUrl : video.getCardImageUrl());
        }

        void restoreOriginalBranding() {
            title = originalTitle;
            image = originalImage;
        }
    }

    static final class TrackOption {
        final String label;
        final MediaTrack track;
        final boolean selected;

        TrackOption(String label, MediaTrack track, boolean selected) {
            this.label = label;
            this.track = track;
            this.selected = selected;
        }
    }

    static final class ChapterOption {
        final String title;
        final long startTimeMs;

        ChapterOption(String title, long startTimeMs) {
            this.title = title;
            this.startTimeMs = startTimeMs;
        }
    }

    static final class SponsorEvent {
        final long sequence;
        final String category;
        final long endMs;
        final boolean confirmationRequired;

        SponsorEvent(long sequence, String category, long endMs, boolean confirmationRequired) {
            this.sequence = sequence;
            this.category = category;
            this.endMs = endMs;
            this.confirmationRequired = confirmationRequired;
        }
    }

    static final class SuggestionOption {
        final String section;
        final String videoId;
        final String originalTitle;
        final String author;
        final String originalImage;
        String title;
        String image;

        SuggestionOption(String section, MediaItem item) {
            this.section = section;
            videoId = item.getVideoId();
            originalTitle = title = item.getTitle();
            author = !TextUtils.isEmpty(item.getAuthor()) ? item.getAuthor()
                    : item.getSecondTitle() != null ? item.getSecondTitle().toString() : null;
            originalImage = image = item.getCardImageUrl();
        }

        QueueEntry toQueueEntry() {
            QueueEntry entry = new QueueEntry(videoId, originalTitle, author, originalImage);
            entry.title = title;
            entry.image = image;
            return entry;
        }

        void restoreOriginalBranding() {
            title = originalTitle;
            image = originalImage;
        }
    }

    static final class DebugSnapshot {
        final String playerState;
        final boolean playWhenReady;
        final int windowIndex;
        final long positionMs;
        final long bufferedMs;
        final long durationMs;
        final int bufferedPercent;
        final String sourceType;
        final String videoFormat;
        final String audioFormat;
        final int renderedVideoBuffers;
        final int droppedVideoBuffers;
        final int skippedVideoBuffers;
        final int maxConsecutiveDropped;

        DebugSnapshot(String playerState, boolean playWhenReady, int windowIndex,
                      long positionMs, long bufferedMs, long durationMs, int bufferedPercent,
                      String sourceType, String videoFormat, String audioFormat,
                      int renderedVideoBuffers, int droppedVideoBuffers,
                      int skippedVideoBuffers, int maxConsecutiveDropped) {
            this.playerState = playerState;
            this.playWhenReady = playWhenReady;
            this.windowIndex = windowIndex;
            this.positionMs = positionMs;
            this.bufferedMs = bufferedMs;
            this.durationMs = durationMs;
            this.bufferedPercent = bufferedPercent;
            this.sourceType = sourceType;
            this.videoFormat = videoFormat;
            this.audioFormat = audioFormat;
            this.renderedVideoBuffers = renderedVideoBuffers;
            this.droppedVideoBuffers = droppedVideoBuffers;
            this.skippedVideoBuffers = skippedVideoBuffers;
            this.maxConsecutiveDropped = maxConsecutiveDropped;
        }
    }

    final class LocalBinder extends Binder {
        MobilePlaybackService getService() {
            return MobilePlaybackService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final List<QueueEntry> queue = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            persistProgress(false);
            checkSponsorSegment();
            notifyListeners();
            handler.postDelayed(this, 1_000L);
        }
    };
    private final Runnable sleepTimerElapsed = () -> {
        sleepTimerEndRealtimeMs = 0;
        PlayerData.instance(this).setSleepTimerHours(0);
        pause();
    };

    private SimpleExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private TrackSelectorManager trackSelectorManager;
    private ExoPlayerInitializer playerInitializer;
    private ExoMediaSourceFactory mediaSourceFactory;
    private MediaSessionCompat mediaSession;
    private Disposable formatInfoAction;
    private Disposable metadataAction;
    private Disposable sponsorSegmentsAction;
    private Disposable deArrowAction;
    private Disposable suggestionDeArrowAction;
    private Disposable dislikeDataAction;
    private int queueIndex = -1;
    private Video currentVideo;
    private String playbackError;
    private long lastSavedPosition = -STATE_SAVE_INTERVAL_MS;
    private volatile String historySyncState = "not_started";
    private volatile int historySyncRequests;
    private volatile long lastHistorySyncPositionMs;
    private volatile long historySyncSequence;
    private boolean restoredTrackPreferences;
    private List<TrackOption> videoTrackOptions = Collections.emptyList();
    private List<TrackOption> subtitleTrackOptions = Collections.emptyList();
    private MediaItemStoryboard storyboard;
    private List<ChapterOption> chapters = Collections.emptyList();
    private String commentsKey;
    private String liveChatKey;
    private List<SuggestionOption> suggestions = Collections.emptyList();
    private String sourceType = "unresolved";
    private int likeStatus = MediaItemMetadata.LIKE_STATUS_INDIFFERENT;
    private boolean subscribed;
    private String channelId;
    private List<SponsorSegment> sponsorSegments = Collections.emptyList();
    private SponsorSegment pendingSponsorSegment;
    private SponsorEvent sponsorEvent;
    private long sponsorEventSequence;
    private String handledSponsorKey;
    private String lastSponsorSkipSummary;
    private String appliedDeArrowTitle;
    private String appliedDeArrowThumbnail;
    private int deArrowSuggestionCount;
    private final Set<String> deArrowSuggestionIds = new HashSet<>();
    private String publicLikeCount;
    private String publicDislikeCount;
    private long publicViewCount;
    private String publicRatingState = "disabled";
    private long dislikeRequestSequence;
    private long sleepTimerEndRealtimeMs;

    static void load(Context context, Video video) {
        MobileSelectionStore.put(video);
        Intent intent = new Intent(context, MobilePlaybackService.class)
                .setAction(ACTION_LOAD)
                .putExtra(EXTRA_VIDEO_ID, video.videoId)
                .putExtra(EXTRA_TITLE, video.getTitle())
                .putExtra(EXTRA_AUTHOR, video.getAuthor())
                .putExtra(EXTRA_IMAGE, video.getCardImageUrl());
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initializePlayer();
        initializeMediaSession();
        MediaServiceManager.instance().addAccountListener(this);
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.post(progressTick);
    }

    private void initializePlayer() {
        trackSelector = new DefaultTrackSelector(new AdaptiveTrackSelection.Factory());
        trackSelectorManager = new TrackSelectorManager(this);
        trackSelectorManager.setTrackSelector(trackSelector);
        playerInitializer = new ExoPlayerInitializer(this);
        player = playerInitializer.createPlayer(this, new DefaultRenderersFactory(this), trackSelector);
        player.addListener(this);
        mediaSourceFactory = new ExoMediaSourceFactory(this);
    }

    private void initializeMediaSession() {
        mediaSession = new MediaSessionCompat(getApplicationContext(), "SmartTubeMobilePlayback");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { skipNext(); }
            @Override public void onSkipToPrevious() { skipPrevious(); }
            @Override public void onSkipToQueueItem(long id) { selectQueueItem((int) id); }
            @Override public void onSeekTo(long position) { seekTo(position); }
        });
        mediaSession.setActive(true);
        updateSessionState();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            updateForegroundNotification();
            return START_STICKY;
        }

        switch (intent.getAction()) {
            case ACTION_LOAD:
                handleLoad(intent);
                break;
            case ACTION_PLAY:
                play();
                break;
            case ACTION_PAUSE:
                pause();
                break;
            case ACTION_TOGGLE:
                if (isPlaying()) pause(); else play();
                break;
            case ACTION_NEXT:
                skipNext();
                break;
            case ACTION_PREVIOUS:
                skipPrevious();
                break;
            default:
                break;
        }
        return START_STICKY;
    }

    private void handleLoad(Intent intent) {
        String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        if (TextUtils.isEmpty(videoId)) return;

        if (currentVideo != null && TextUtils.equals(currentVideo.videoId, videoId)
                && player.getPlaybackState() != Player.STATE_IDLE) {
            notifyListeners();
            return;
        }

        Video selected = MobileSelectionStore.peek();
        if (selected == null || !TextUtils.equals(selected.videoId, videoId)) {
            selected = new Video();
            selected.videoId = videoId;
            selected.title = intent.getStringExtra(EXTRA_TITLE);
            selected.author = intent.getStringExtra(EXTRA_AUTHOR);
            selected.cardImageUrl = intent.getStringExtra(EXTRA_IMAGE);
        }
        buildQueue(selected);
        loadQueueIndex(queueIndex, true);
    }

    private void buildQueue(Video selected) {
        queue.clear();
        queueIndex = -1;
        VideoGroup group = selected.getGroup();
        if (group != null) {
            for (Video candidate : group.getVideos()) {
                if (candidate != null && !TextUtils.isEmpty(candidate.videoId)) {
                    if (TextUtils.equals(candidate.videoId, selected.videoId)) queueIndex = queue.size();
                    queue.add(QueueEntry.from(candidate));
                }
            }
        }
        if (queueIndex < 0) {
            queue.clear();
            queue.add(QueueEntry.from(selected));
            queueIndex = 0;
        }
        updateSessionQueue();
    }

    private void loadQueueIndex(int index, boolean restoreProgress) {
        if (index < 0 || index >= queue.size()) return;
        persistProgress(true);
        queueIndex = index;
        QueueEntry entry = queue.get(index);
        currentVideo = new Video();
        currentVideo.videoId = entry.videoId;
        currentVideo.title = entry.title;
        currentVideo.author = entry.author;
        currentVideo.cardImageUrl = entry.image;
        playbackError = null;
        lastSavedPosition = -STATE_SAVE_INTERVAL_MS;
        restoredTrackPreferences = false;
        videoTrackOptions = Collections.emptyList();
        subtitleTrackOptions = Collections.emptyList();
        storyboard = null;
        chapters = Collections.emptyList();
        commentsKey = null;
        liveChatKey = null;
        suggestions = Collections.emptyList();
        sponsorSegments = Collections.emptyList();
        pendingSponsorSegment = null;
        sponsorEvent = null;
        handledSponsorKey = null;
        lastSponsorSkipSummary = null;
        appliedDeArrowTitle = null;
        appliedDeArrowThumbnail = null;
        deArrowSuggestionCount = 0;
        deArrowSuggestionIds.clear();
        publicLikeCount = null;
        publicDislikeCount = null;
        publicViewCount = 0;
        publicRatingState = PlayerTweaksData.instance(this).isLikesCounterEnabled() ? "loading" : "disabled";
        sourceType = "resolving";
        likeStatus = MediaItemMetadata.LIKE_STATUS_INDIFFERENT;
        subscribed = false;
        channelId = null;
        resetHistorySyncDiagnostics();
        updateSessionMetadata();
        updateForegroundNotification();
        notifyListeners();

        RxHelper.disposeActions(formatInfoAction);
        RxHelper.disposeActions(metadataAction);
        RxHelper.disposeActions(sponsorSegmentsAction);
        RxHelper.disposeActions(deArrowAction);
        RxHelper.disposeActions(suggestionDeArrowAction);
        RxHelper.disposeActions(dislikeDataAction);
        formatInfoAction = YouTubeServiceManager.instance()
                .getMediaItemService()
                .getFormatInfoObserve(entry.videoId)
                .subscribe(info -> openFormatInfo(info, restoreProgress), this::onFormatError);
        metadataAction = YouTubeServiceManager.instance()
                .getMediaItemService()
                .getMetadataObserve(entry.videoId, null, 0, null)
                .subscribe(this::onMetadataLoaded, error -> { });
        loadCurrentDeArrow();
        loadDislikeData();
    }

    private void openFormatInfo(MediaItemFormatInfo info, boolean restoreProgress) {
        currentVideo.sync(info);
        storyboard = info.createStoryboard();
        trackSelectorManager.setMergedSource(info.containsDashFormats() && info.hasExtendedHlsFormats());
        MediaSource source = null;
        if (info.containsDashFormats()) {
            sourceType = info.hasExtendedHlsFormats() ? "DASH + HLS merged" : "DASH formats";
            source = mediaSourceFactory.fromDashFormatInfo(info);
            if (info.hasExtendedHlsFormats()) {
                source = new MergingMediaSource(source, mediaSourceFactory.fromHlsPlaylist(info.getHlsManifestUrl()));
            }
        } else if (info.containsSabrFormats()) {
            sourceType = "SABR formats";
            source = mediaSourceFactory.fromSabrFormatInfo(info);
        } else if (info.isLive() && info.containsDashUrl()) {
            sourceType = "Live DASH manifest";
            source = mediaSourceFactory.fromDashManifestUrl(info.getDashManifestUrl());
        } else if (info.isLive() && info.containsHlsUrl()) {
            sourceType = "Live HLS manifest";
            source = mediaSourceFactory.fromHlsPlaylist(info.getHlsManifestUrl());
        } else if (info.containsUrlFormats()) {
            sourceType = "Resolved URL list";
            source = mediaSourceFactory.fromUrlList(info.createUrlList());
        }

        if (source == null) {
            sourceType = "unavailable";
            onFormatError(new IllegalStateException(TextUtils.isEmpty(info.getPlayabilityReason())
                    ? getString(R.string.playback_source_unavailable)
                    : info.getPlayabilityReason()));
            return;
        }

        applyPlaybackSpeed(PlayerData.instance(this).getSpeed(currentVideo.channelId), false);
        player.prepare(source);
        if (restoreProgress) {
            VideoStateService.State state = VideoStateService.instance(this).getByVideoId(currentVideo.videoId);
            if (state != null && state.positionMs > 0) player.seekTo(state.positionMs);
        }
        player.setPlayWhenReady(true);
        updateSessionMetadata();
        updateForegroundNotification();
    }

    private void onFormatError(Throwable error) {
        playbackError = error != null && !TextUtils.isEmpty(error.getMessage())
                ? error.getMessage() : getString(R.string.playback_source_unavailable);
        player.setPlayWhenReady(false);
        updateSessionState();
        updateForegroundNotification();
        notifyListeners();
    }

    void play() {
        if (player != null) player.setPlayWhenReady(true);
        updateSessionState();
        updateForegroundNotification();
        notifyListeners();
    }

    void pause() {
        if (player != null) player.setPlayWhenReady(false);
        persistProgress(true);
        updateSessionState();
        updateForegroundNotification();
        notifyListeners();
    }

    void seekTo(long positionMs) {
        if (player != null && positionMs >= 0) player.seekTo(positionMs);
        updateSessionState();
        notifyListeners();
    }

    void skipNext() {
        if (queueIndex + 1 < queue.size()) loadQueueIndex(queueIndex + 1, true);
    }

    void skipPrevious() {
        if (player != null && player.getCurrentPosition() > 5_000L) {
            seekTo(0);
        } else if (queueIndex > 0) {
            loadQueueIndex(queueIndex - 1, true);
        }
    }

    void selectQueueItem(int index) {
        if (index >= 0 && index < queue.size() && index != queueIndex) {
            loadQueueIndex(index, true);
        }
    }

    void setSleepTimerMinutes(int minutes) {
        handler.removeCallbacks(sleepTimerElapsed);
        if (minutes <= 0) {
            sleepTimerEndRealtimeMs = 0;
            PlayerData.instance(this).setSleepTimerHours(0);
        } else {
            long durationMs = minutes * 60_000L;
            sleepTimerEndRealtimeMs = SystemClock.elapsedRealtime() + durationMs;
            PlayerData.instance(this).setSleepTimerHours(minutes / 60f);
            handler.postDelayed(sleepTimerElapsed, durationMs);
        }
        notifyListeners();
    }

    long getSleepTimerRemainingMs() {
        return sleepTimerEndRealtimeMs > 0
                ? Math.max(0, sleepTimerEndRealtimeMs - SystemClock.elapsedRealtime()) : 0;
    }

    SimpleExoPlayer getPlayer() { return player; }
    boolean isPlaying() { return player != null && player.getPlayWhenReady(); }
    boolean hasNext() { return queueIndex >= 0 && queueIndex + 1 < queue.size(); }
    boolean hasPrevious() { return queueIndex > 0; }
    int getQueueIndex() { return queueIndex; }
    int getQueueSize() { return queue.size(); }
    long getPositionMs() { return player != null ? player.getCurrentPosition() : 0; }
    long getDurationMs() { return player != null && player.getDuration() > 0 ? player.getDuration() : 0; }
    float getPlaybackSpeed() { return player != null ? player.getPlaybackParameters().speed : 1f; }
    @Nullable Video getCurrentVideo() { return currentVideo; }
    @Nullable String getPlaybackError() { return playbackError; }
    List<QueueEntry> getQueue() { return Collections.unmodifiableList(queue); }
    List<TrackOption> getVideoTrackOptions() { return videoTrackOptions; }
    List<TrackOption> getSubtitleTrackOptions() { return subtitleTrackOptions; }
    @Nullable MediaItemStoryboard getStoryboard() { return storyboard; }
    List<ChapterOption> getChapters() { return chapters; }
    @Nullable String getCommentsKey() { return commentsKey; }
    @Nullable String getLiveChatKey() { return liveChatKey; }
    List<SuggestionOption> getSuggestions() { return suggestions; }
    int getLikeStatus() { return likeStatus; }
    boolean isSubscribed() { return subscribed; }
    @Nullable String getChannelId() { return channelId; }
    List<SponsorSegment> getSponsorSegments() { return sponsorSegments; }
    @Nullable SponsorEvent getSponsorEvent() { return sponsorEvent; }
    @Nullable String getLastSponsorSkipSummary() { return lastSponsorSkipSummary; }
    @Nullable String getAppliedDeArrowTitle() { return appliedDeArrowTitle; }
    @Nullable String getAppliedDeArrowThumbnail() { return appliedDeArrowThumbnail; }
    int getDeArrowSuggestionCount() { return deArrowSuggestionCount; }
    @Nullable String getPublicLikeCount() { return publicLikeCount; }
    @Nullable String getPublicDislikeCount() { return publicDislikeCount; }
    long getPublicViewCount() { return publicViewCount; }
    String getPublicRatingState() { return publicRatingState; }
    String getHistorySyncState() { return historySyncState; }
    int getHistorySyncRequests() { return historySyncRequests; }
    long getLastHistorySyncPositionMs() { return lastHistorySyncPositionMs; }

    @Override
    public void onAccountChanged(Account account) {
        resetHistorySyncDiagnostics();
    }

    private void resetHistorySyncDiagnostics() {
        historySyncSequence++;
        historySyncState = "not_started";
        historySyncRequests = 0;
        lastHistorySyncPositionMs = 0;
    }

    void acknowledgeSponsorEvent(long sequence) {
        if (sponsorEvent != null && sponsorEvent.sequence == sequence && !sponsorEvent.confirmationRequired) {
            sponsorEvent = null;
        }
    }

    void confirmSponsorSkip(long sequence) {
        if (sponsorEvent == null || sponsorEvent.sequence != sequence || pendingSponsorSegment == null) return;
        lastSponsorSkipSummary = pendingSponsorSegment.getCategory() + " confirmed→"
                + pendingSponsorSegment.getEndMs() + " ms";
        seekTo(Math.min(pendingSponsorSegment.getEndMs(), getDurationMs()));
        pendingSponsorSegment = null;
        sponsorEvent = null;
    }

    void dismissSponsorSkip(long sequence) {
        if (sponsorEvent == null || sponsorEvent.sequence != sequence) return;
        pendingSponsorSegment = null;
        sponsorEvent = null;
    }

    void reloadSponsorBlock() {
        pendingSponsorSegment = null;
        sponsorEvent = null;
        handledSponsorKey = null;
        loadSponsorSegments();
    }

    void reloadDeArrow() {
        RxHelper.disposeActions(deArrowAction);
        RxHelper.disposeActions(suggestionDeArrowAction);
        for (QueueEntry entry : queue) entry.restoreOriginalBranding();
        for (SuggestionOption suggestion : suggestions) suggestion.restoreOriginalBranding();
        appliedDeArrowTitle = null;
        appliedDeArrowThumbnail = null;
        deArrowSuggestionCount = 0;
        deArrowSuggestionIds.clear();
        if (currentVideo != null && queueIndex >= 0 && queueIndex < queue.size()) {
            QueueEntry entry = queue.get(queueIndex);
            currentVideo.title = entry.originalTitle;
            currentVideo.deArrowTitle = null;
            currentVideo.cardImageUrl = entry.originalImage;
            currentVideo.altCardImageUrl = null;
        }
        updateSessionQueue();
        updateSessionMetadata();
        updateForegroundNotification();
        notifyListeners();
        loadCurrentDeArrow();
        loadSuggestionDeArrow();
    }

    void reloadDislikeData() {
        RxHelper.disposeActions(dislikeDataAction);
        publicLikeCount = null;
        publicDislikeCount = null;
        publicViewCount = 0;
        publicRatingState = PlayerTweaksData.instance(this).isLikesCounterEnabled() ? "loading" : "disabled";
        notifyListeners();
        loadDislikeData();
    }

    void applyMetadataReadback(MediaItemMetadata metadata) {
        if (metadata != null && currentVideo != null
                && TextUtils.equals(currentVideo.videoId, metadata.getVideoId())) {
            onMetadataLoaded(metadata);
        }
    }

    DebugSnapshot getDebugSnapshot() {
        Format video = player.getVideoFormat();
        Format audio = player.getAudioFormat();
        DecoderCounters counters = player.getVideoDecoderCounters();
        int rendered = 0;
        int dropped = 0;
        int skipped = 0;
        int maxDropped = 0;
        if (counters != null) {
            counters.ensureUpdated();
            rendered = counters.renderedOutputBufferCount;
            dropped = counters.droppedBufferCount;
            skipped = counters.skippedOutputBufferCount;
            maxDropped = counters.maxConsecutiveDroppedBufferCount;
        }
        return new DebugSnapshot(playerStateLabel(player.getPlaybackState()), player.getPlayWhenReady(),
                player.getCurrentWindowIndex(), player.getCurrentPosition(), player.getBufferedPosition(),
                player.getDuration(), player.getBufferedPercentage(), sourceType,
                videoFormatLabel(video), audioFormatLabel(audio), rendered, dropped, skipped, maxDropped);
    }

    private static String playerStateLabel(int state) {
        if (state == Player.STATE_BUFFERING) return "BUFFERING";
        if (state == Player.STATE_READY) return "READY";
        if (state == Player.STATE_ENDED) return "ENDED";
        return "IDLE";
    }

    private static String videoFormatLabel(@Nullable Format format) {
        if (format == null) return "unavailable";
        StringBuilder value = new StringBuilder();
        value.append(format.sampleMimeType).append(" • ").append(format.width).append('×').append(format.height);
        if (format.frameRate > 0) value.append(" • ").append(format.frameRate).append(" fps");
        if (format.bitrate > 0) value.append(" • ").append(format.bitrate / 1_000_000f).append(" Mbps");
        if (!TextUtils.isEmpty(format.codecs)) value.append(" • ").append(format.codecs);
        if (format.pixelWidthHeightRatio > 0 && format.pixelWidthHeightRatio != 1f) {
            value.append(" • PAR ").append(format.pixelWidthHeightRatio);
        }
        return value.toString();
    }

    private static String audioFormatLabel(@Nullable Format format) {
        if (format == null) return "unavailable";
        StringBuilder value = new StringBuilder();
        value.append(format.sampleMimeType);
        if (format.sampleRate > 0) value.append(" • ").append(format.sampleRate).append(" Hz");
        if (format.channelCount > 0) value.append(" • ").append(format.channelCount).append(" ch");
        if (format.bitrate > 0) value.append(" • ").append(format.bitrate / 1_000).append(" kbps");
        if (!TextUtils.isEmpty(format.codecs)) value.append(" • ").append(format.codecs);
        return value.toString();
    }

    void selectSuggestion(int index) {
        if (index < 0 || index >= suggestions.size()) return;
        queue.clear();
        for (SuggestionOption option : suggestions) queue.add(option.toQueueEntry());
        queueIndex = index;
        updateSessionQueue();
        loadQueueIndex(index, false);
    }

    private void onMetadataLoaded(MediaItemMetadata metadata) {
        if (currentVideo != null) currentVideo.sync(metadata);
        likeStatus = metadata.getLikeStatus();
        subscribed = metadata.isSubscribed();
        channelId = metadata.getChannelId();
        commentsKey = metadata.getCommentsKey();
        liveChatKey = metadata.getLiveChatKey();
        List<SuggestionOption> suggestionResult = new ArrayList<>();
        List<MediaGroup> suggestionGroups = metadata.getSuggestions();
        if (suggestionGroups != null) {
            for (MediaGroup group : suggestionGroups) {
                if (group == null || group.getMediaItems() == null) continue;
                for (MediaItem item : group.getMediaItems()) {
                    if (item != null && item.getType() == MediaItem.TYPE_VIDEO
                            && !TextUtils.isEmpty(item.getVideoId())
                            && (currentVideo == null || !TextUtils.equals(item.getVideoId(), currentVideo.videoId))) {
                        suggestionResult.add(new SuggestionOption(group.getTitle(), item));
                    }
                }
            }
        }
        suggestions = suggestionResult;
        loadSuggestionDeArrow();
        List<ChapterOption> result = new ArrayList<>();
        List<ChapterItem> serviceChapters = metadata.getChapters();
        if (serviceChapters != null) {
            for (ChapterItem chapter : serviceChapters) {
                if (chapter != null && !TextUtils.isEmpty(chapter.getTitle())) {
                    result.add(new ChapterOption(chapter.getTitle(), chapter.getStartTimeMs()));
                }
            }
        }
        boolean parsedDescription = result.isEmpty();
        if (parsedDescription && !TextUtils.isEmpty(metadata.getDescription())) {
            Matcher matcher = CHAPTER_LINE.matcher(metadata.getDescription());
            while (matcher.find()) {
                long startTime = parseChapterTime(matcher.group(1));
                if (startTime >= 0 && (getDurationMs() <= 0 || startTime < getDurationMs())) {
                    result.add(new ChapterOption(matcher.group(2).trim(), startTime));
                }
            }
        }
        Collections.sort(result, (first, second) -> Long.compare(first.startTimeMs, second.startTimeMs));
        if (parsedDescription && (result.size() < 3 || result.get(0).startTimeMs > 1_000L)) {
            result.clear();
        }
        List<ChapterOption> unique = new ArrayList<>();
        long previousStart = -1;
        for (ChapterOption chapter : result) {
            if (chapter.startTimeMs != previousStart) {
                unique.add(chapter);
                previousStart = chapter.startTimeMs;
            }
        }
        chapters = Collections.unmodifiableList(unique);
        loadSponsorSegments();
        notifyListeners();
    }

    private void loadDislikeData() {
        RxHelper.disposeActions(dislikeDataAction);
        final long requestSequence = ++dislikeRequestSequence;
        if (currentVideo == null || TextUtils.isEmpty(currentVideo.videoId)) return;
        if (!PlayerTweaksData.instance(this).isLikesCounterEnabled()) {
            publicRatingState = "disabled";
            notifyListeners();
            return;
        }
        final String requestedVideoId = currentVideo.videoId;
        publicRatingState = "loading";
        dislikeDataAction = YouTubeServiceManager.instance().getMediaItemService()
                .getDislikeDataObserve(requestedVideoId)
                .subscribe(data -> handler.post(() -> applyDislikeData(requestedVideoId, requestSequence, data)),
                        error -> handler.post(() -> markDislikeDataUnavailable(requestedVideoId, requestSequence)));
        handler.postDelayed(() -> {
            if (currentVideo != null && TextUtils.equals(requestedVideoId, currentVideo.videoId)
                    && requestSequence == dislikeRequestSequence
                    && TextUtils.equals("loading", publicRatingState)) {
                timeoutDislikeData(requestedVideoId, requestSequence);
            }
        }, 12_000L);
    }

    private void applyDislikeData(String requestedVideoId, long requestSequence, DislikeData data) {
        if (currentVideo == null || requestSequence != dislikeRequestSequence
                || !TextUtils.equals(requestedVideoId, currentVideo.videoId)) return;
        if (data == null || !TextUtils.equals(requestedVideoId, data.getVideoId())) {
            markDislikeDataUnavailable(requestedVideoId, requestSequence);
            return;
        }
        publicLikeCount = TextUtils.isEmpty(data.getLikeCount()) ? "0" : data.getLikeCount();
        publicDislikeCount = TextUtils.isEmpty(data.getDislikeCount()) ? "0" : data.getDislikeCount();
        publicViewCount = data.getViewCount();
        publicRatingState = "available";
        currentVideo.sync(data);
        notifyListeners();
    }

    private void markDislikeDataUnavailable(String requestedVideoId, long requestSequence) {
        if (currentVideo == null || requestSequence != dislikeRequestSequence
                || !TextUtils.equals(requestedVideoId, currentVideo.videoId)) return;
        publicLikeCount = null;
        publicDislikeCount = null;
        publicViewCount = 0;
        publicRatingState = "unavailable";
        notifyListeners();
    }

    private void timeoutDislikeData(String requestedVideoId, long requestSequence) {
        if (currentVideo == null || requestSequence != dislikeRequestSequence
                || !TextUtils.equals(requestedVideoId, currentVideo.videoId)) return;
        RxHelper.disposeActions(dislikeDataAction);
        dislikeRequestSequence++;
        publicLikeCount = null;
        publicDislikeCount = null;
        publicViewCount = 0;
        publicRatingState = "unavailable";
        notifyListeners();
    }

    private void loadCurrentDeArrow() {
        RxHelper.disposeActions(deArrowAction);
        com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData preferences =
                com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData.instance(this);
        if (currentVideo == null || TextUtils.isEmpty(currentVideo.videoId)
                || (!preferences.isReplaceTitlesEnabled() && !preferences.isReplaceThumbnailsEnabled())) return;
        final String requestedVideoId = currentVideo.videoId;
        deArrowAction = YouTubeServiceManager.instance().getMediaItemService()
                .getDeArrowDataObserve(requestedVideoId)
                .subscribe(data -> handler.post(() -> applyCurrentDeArrow(requestedVideoId, data)), error -> { });
    }

    private void applyCurrentDeArrow(String requestedVideoId, DeArrowData data) {
        if (data == null || currentVideo == null || !TextUtils.equals(requestedVideoId, currentVideo.videoId)) return;
        com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData preferences =
                com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData.instance(this);
        QueueEntry entry = queueIndex >= 0 && queueIndex < queue.size() ? queue.get(queueIndex) : null;
        if (preferences.isReplaceTitlesEnabled() && !TextUtils.isEmpty(data.getTitle())) {
            currentVideo.deArrowTitle = data.getTitle();
            appliedDeArrowTitle = data.getTitle();
            if (entry != null) entry.title = data.getTitle();
        }
        if (preferences.isReplaceThumbnailsEnabled() && !TextUtils.isEmpty(data.getThumbnailUrl())) {
            currentVideo.altCardImageUrl = data.getThumbnailUrl();
            appliedDeArrowThumbnail = data.getThumbnailUrl();
            if (entry != null) entry.image = data.getThumbnailUrl();
        }
        updateSessionQueue();
        updateSessionMetadata();
        updateForegroundNotification();
        notifyListeners();
    }

    private void loadSuggestionDeArrow() {
        RxHelper.disposeActions(suggestionDeArrowAction);
        deArrowSuggestionCount = 0;
        deArrowSuggestionIds.clear();
        com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData preferences =
                com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData.instance(this);
        if (suggestions.isEmpty()
                || (!preferences.isReplaceTitlesEnabled() && !preferences.isReplaceThumbnailsEnabled())) return;
        List<String> videoIds = new ArrayList<>();
        for (SuggestionOption suggestion : suggestions) videoIds.add(suggestion.videoId);
        final String requestedVideoId = currentVideo != null ? currentVideo.videoId : null;
        suggestionDeArrowAction = YouTubeServiceManager.instance().getMediaItemService()
                .getDeArrowDataObserve(videoIds)
                .subscribe(data -> handler.post(() -> {
                    if (data == null || currentVideo == null
                            || !TextUtils.equals(requestedVideoId, currentVideo.videoId)) return;
                    for (SuggestionOption suggestion : suggestions) {
                        if (!TextUtils.equals(suggestion.videoId, data.getVideoId())) continue;
                        boolean applied = false;
                        if (preferences.isReplaceTitlesEnabled() && !TextUtils.isEmpty(data.getTitle())) {
                            suggestion.title = data.getTitle();
                            applied = true;
                        }
                        if (preferences.isReplaceThumbnailsEnabled() && !TextUtils.isEmpty(data.getThumbnailUrl())) {
                            suggestion.image = data.getThumbnailUrl();
                            applied = true;
                        }
                        if (applied && deArrowSuggestionIds.add(suggestion.videoId)) deArrowSuggestionCount++;
                        notifyListeners();
                        break;
                    }
                }), error -> { });
    }

    private void loadSponsorSegments() {
        RxHelper.disposeActions(sponsorSegmentsAction);
        SponsorBlockData data = SponsorBlockData.instance(this);
        if (currentVideo == null || TextUtils.isEmpty(currentVideo.videoId)
                || currentVideo.isLive || !data.isSponsorBlockEnabled()
                || data.isChannelExcluded(channelId) || data.getEnabledCategories().isEmpty()) {
            sponsorSegments = Collections.emptyList();
            notifyListeners();
            return;
        }
        final String requestedVideoId = currentVideo.videoId;
        sponsorSegmentsAction = YouTubeServiceManager.instance().getMediaItemService()
                .getSponsorSegmentsObserve(requestedVideoId, data.getEnabledCategories())
                .subscribe(segments -> handler.post(() -> {
                    if (currentVideo == null || !TextUtils.equals(requestedVideoId, currentVideo.videoId)) return;
                    sponsorSegments = segments == null ? Collections.emptyList()
                            : Collections.unmodifiableList(new ArrayList<>(segments));
                    notifyListeners();
                }), error -> handler.post(() -> {
                    if (currentVideo != null && TextUtils.equals(requestedVideoId, currentVideo.videoId)) {
                        sponsorSegments = Collections.emptyList();
                        notifyListeners();
                    }
                }));
    }

    private void checkSponsorSegment() {
        if (player == null || !isPlaying() || sponsorSegments.isEmpty() || currentVideo == null) return;
        SponsorBlockData data = SponsorBlockData.instance(this);
        if (!data.isSponsorBlockEnabled() || data.isChannelExcluded(channelId)) return;
        long positionMs = getPositionMs();
        if (pendingSponsorSegment != null
                && (positionMs < pendingSponsorSegment.getStartMs() || positionMs > pendingSponsorSegment.getEndMs())) {
            pendingSponsorSegment = null;
            sponsorEvent = null;
        }
        SponsorSegment found = null;
        for (SponsorSegment segment : sponsorSegments) {
            if (!SponsorSegment.ACTION_SKIP.equals(segment.getAction())) continue;
            long entryWindowMs = (long) (2_000L * Math.max(1f, getPlaybackSpeed()));
            if (positionMs >= segment.getStartMs()
                    && positionMs <= Math.min(segment.getEndMs(), segment.getStartMs() + entryWindowMs)) {
                found = segment;
                break;
            }
        }
        if (found == null) {
            handledSponsorKey = null;
            return;
        }
        String key = found.getStartMs() + ":" + found.getEndMs() + ":" + found.getCategory();
        if (TextUtils.equals(key, handledSponsorKey)) return;
        handledSponsorKey = key;
        long skipDurationMs = Math.min(found.getEndMs(), getDurationMs()) - positionMs;
        if (skipDurationMs < data.getIgnoredDurationMs()) return;
        int action = data.getAction(found.getCategory());
        if (action == SponsorBlockData.ACTION_DO_NOTHING || action == SponsorBlockData.ACTION_UNDEFINED) return;
        if (action == SponsorBlockData.ACTION_SHOW_DIALOG) {
            pendingSponsorSegment = found;
            sponsorEvent = new SponsorEvent(++sponsorEventSequence, found.getCategory(), found.getEndMs(), true);
            return;
        }
        if (action == SponsorBlockData.ACTION_SKIP_ONLY || action == SponsorBlockData.ACTION_SKIP_WITH_TOAST) {
            lastSponsorSkipSummary = found.getCategory() + " " + positionMs + "→" + found.getEndMs() + " ms";
            seekTo(Math.min(found.getEndMs(), getDurationMs()));
            if (action == SponsorBlockData.ACTION_SKIP_WITH_TOAST) {
                sponsorEvent = new SponsorEvent(++sponsorEventSequence, found.getCategory(), found.getEndMs(), false);
            }
        }
    }

    private static long parseChapterTime(String value) {
        String[] parts = value.split(":");
        try {
            long seconds = 0;
            for (String part : parts) seconds = seconds * 60 + Long.parseLong(part);
            return seconds * 1_000L;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    void setPlaybackSpeed(float speed) {
        applyPlaybackSpeed(speed, true);
    }

    private void applyPlaybackSpeed(float speed, boolean persist) {
        if (player == null || speed <= 0) return;
        float pitch = PlayerTweaksData.instance(this).isAudioTimeStretchingEnabled()
                ? player.getPlaybackParameters().pitch : speed;
        player.setPlaybackParameters(new PlaybackParameters(speed, pitch));
        if (persist) {
            PlayerData.instance(this).setSpeed(currentVideo != null ? currentVideo.channelId : null, speed);
        }
        updateSessionState();
        updateForegroundNotification();
        notifyListeners();
    }

    void selectVideoTrack(TrackOption option) {
        selectTrack(option);
    }

    void selectSubtitleTrack(TrackOption option) {
        selectTrack(option);
    }

    private void selectTrack(TrackOption option) {
        if (option == null || option.track == null || trackSelectorManager == null) return;
        trackSelectorManager.selectTrack(option.track);
        PlayerData.instance(this).setFormat(ExoFormatItem.from(option.track));
        refreshTrackOptions();
        notifyListeners();
    }

    private void refreshTrackOptions() {
        if (trackSelectorManager == null || trackSelector == null
                || trackSelector.getCurrentMappedTrackInfo() == null) return;
        trackSelectorManager.invalidate();
        videoTrackOptions = buildTrackOptions(trackSelectorManager.getVideoTracks(), getString(R.string.quality_auto));
        subtitleTrackOptions = buildTrackOptions(trackSelectorManager.getSubtitleTracks(), getString(R.string.captions_off));
    }

    private List<TrackOption> buildTrackOptions(@Nullable Set<MediaTrack> tracks, String defaultLabel) {
        if (tracks == null || tracks.isEmpty()) return Collections.emptyList();
        List<TrackOption> result = new ArrayList<>();
        for (MediaTrack track : tracks) {
            String label = track.format == null ? defaultLabel : TrackSelectorUtil.buildTrackNameShort(track.format).toString();
            result.add(new TrackOption(label, track, isTrackSelected(track)));
        }
        return Collections.unmodifiableList(result);
    }

    private boolean isTrackSelected(MediaTrack track) {
        int renderer = track.rendererIndex;
        TrackGroupArray groups = trackSelector.getCurrentMappedTrackInfo().getTrackGroups(renderer);
        Parameters parameters = trackSelector.getParameters();
        boolean disabled = parameters.getRendererDisabled(renderer);
        SelectionOverride override = parameters.getSelectionOverride(renderer, groups);
        if (track.format == null) {
            return renderer == TrackSelectorManager.RENDERER_INDEX_SUBTITLE
                    ? disabled : !disabled && override == null;
        }
        if (disabled || override == null || override.groupIndex != track.groupIndex) return false;
        for (int selectedTrack : override.tracks) {
            if (selectedTrack == track.trackIndex) return true;
        }
        return false;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
        listener.onPlaybackStateChanged();
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Listener listener : listeners) listener.onPlaybackStateChanged();
    }

    private void persistProgress(boolean force) {
        if (player == null || currentVideo == null || TextUtils.isEmpty(currentVideo.videoId)) return;
        long position = player.getCurrentPosition();
        if (!force && Math.abs(position - lastSavedPosition) < STATE_SAVE_INTERVAL_MS) return;
        long duration = player.getDuration();
        VideoStateService stateService = VideoStateService.instance(this);
        stateService.save(new VideoStateService.State(currentVideo, position, duration));
        if (GeneralData.instance(this).getHistoryState() == GeneralData.HISTORY_DISABLED) {
            historySyncState = "paused_on_mobile";
        } else if (!YouTubeServiceManager.instance().getSignInService().isSigned()) {
            historySyncState = "signed_out";
        } else {
            long syncPosition = Math.max(position, 3_000L);
            long requestSequence = ++historySyncSequence;
            boolean started = MediaServiceManager.instance().updateHistory(currentVideo, syncPosition,
                    () -> {
                        if (requestSequence == historySyncSequence) historySyncState = "verified";
                    },
                    () -> {
                        if (requestSequence == historySyncSequence) historySyncState = "failed";
                    });
            if (started) {
                historySyncState = "pending";
                historySyncRequests++;
                lastHistorySyncPositionMs = syncPosition;
            } else {
                historySyncState = "busy";
            }
        }
        if (force) stateService.persistNow();
        lastSavedPosition = position;
    }

    private void updateSessionMetadata() {
        if (currentVideo == null) return;
        MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentVideo.videoId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentVideo.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentVideo.getAuthor())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, currentVideo.getCardImageUrl())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDurationMs())
                .build();
        mediaSession.setMetadata(metadata);
        updateSessionState();
    }

    private void updateSessionQueue() {
        List<MediaSessionCompat.QueueItem> sessionQueue = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            QueueEntry entry = queue.get(i);
            MediaDescriptionCompat.Builder builder = new MediaDescriptionCompat.Builder()
                    .setMediaId(entry.videoId)
                    .setTitle(entry.title)
                    .setSubtitle(entry.author);
            if (!TextUtils.isEmpty(entry.image)) builder.setIconUri(Uri.parse(entry.image));
            MediaDescriptionCompat description = builder.build();
            sessionQueue.add(new MediaSessionCompat.QueueItem(description, i));
        }
        mediaSession.setQueue(sessionQueue);
        mediaSession.setQueueTitle(getString(R.string.playback_queue));
    }

    private void updateSessionState() {
        if (mediaSession == null) return;
        int state;
        if (playbackError != null) state = PlaybackStateCompat.STATE_ERROR;
        else if (player == null || player.getPlaybackState() == Player.STATE_IDLE) state = PlaybackStateCompat.STATE_NONE;
        else if (player.getPlaybackState() == Player.STATE_BUFFERING) state = PlaybackStateCompat.STATE_BUFFERING;
        else if (isPlaying()) state = PlaybackStateCompat.STATE_PLAYING;
        else state = PlaybackStateCompat.STATE_PAUSED;

        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM;
        if (hasNext()) actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        if (currentVideo != null) actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, getPositionMs(), getPlaybackSpeed())
                .setActiveQueueItemId(queueIndex >= 0
                        ? queueIndex : MediaSessionCompat.QueueItem.UNKNOWN_ID);
        if (playbackError != null) builder.setErrorMessage(playbackError);
        mediaSession.setPlaybackState(builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.playback_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.playback_channel_description));
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                WatchActivity.createResumeIntent(this, currentVideo), pendingIntentFlags());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_video_placeholder)
                .setContentTitle(currentVideo != null ? currentVideo.getTitle() : getString(R.string.playback_service_starting))
                .setContentText(playbackError != null ? playbackError
                        : currentVideo != null ? currentVideo.getAuthor() : getString(R.string.playback_service_ready))
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, getString(R.string.previous), serviceAction(ACTION_PREVIOUS, 1))
                .addAction(isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying() ? getString(R.string.pause) : getString(R.string.play), serviceAction(ACTION_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, getString(R.string.next), serviceAction(ACTION_NEXT, 3));
        if (mediaSession != null) {
            builder.setStyle(new MediaStyle().setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, MobilePlaybackService.class).setAction(action), pendingIntentFlags());
    }

    private int pendingIntentFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void updateForegroundNotification() {
        updateSessionState();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (playbackState == Player.STATE_ENDED && hasNext()) skipNext();
        updateSessionMetadata();
        updateForegroundNotification();
        notifyListeners();
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        if (trackSelectorManager == null || trackSelector == null
                || trackSelector.getCurrentMappedTrackInfo() == null) return;
        trackSelectorManager.invalidate();
        if (!restoredTrackPreferences) {
            restoredTrackPreferences = true;
            PlayerData data = PlayerData.instance(this);
            FormatItem video = data.getFormat(FormatItem.TYPE_VIDEO);
            FormatItem subtitle = data.getFormat(FormatItem.TYPE_SUBTITLE);
            if (video != null) trackSelectorManager.selectTrack(FormatItem.toMediaTrack(video));
            if (subtitle != null) trackSelectorManager.selectTrack(FormatItem.toMediaTrack(subtitle));
        }
        refreshTrackOptions();
        notifyListeners();
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
        updateSessionState();
        updateForegroundNotification();
        notifyListeners();
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        onFormatError(error);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        persistProgress(true);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        MediaServiceManager.instance().removeAccountListener(this);
        handler.removeCallbacksAndMessages(null);
        if (sleepTimerEndRealtimeMs > 0) PlayerData.instance(this).setSleepTimerHours(0);
        sleepTimerEndRealtimeMs = 0;
        persistProgress(true);
        RxHelper.disposeActions(formatInfoAction);
        RxHelper.disposeActions(metadataAction);
        RxHelper.disposeActions(sponsorSegmentsAction);
        RxHelper.disposeActions(deArrowAction);
        RxHelper.disposeActions(suggestionDeArrowAction);
        RxHelper.disposeActions(dislikeDataAction);
        listeners.clear();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        if (trackSelectorManager != null) {
            trackSelectorManager.release();
            trackSelectorManager = null;
        }
        if (player != null) {
            player.removeListener(this);
            player.release();
        }
        if (mediaSourceFactory != null) mediaSourceFactory.release();
        if (playerInitializer != null) playerInitializer.release();
        super.onDestroy();
    }
}
