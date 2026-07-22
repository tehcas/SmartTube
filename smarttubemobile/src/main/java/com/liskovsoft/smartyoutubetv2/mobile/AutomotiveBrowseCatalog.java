package com.liskovsoft.smartyoutubetv2.mobile;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;

/** Bounded catalog that exposes only provider-classified music to Android Auto. */
final class AutomotiveBrowseCatalog {
    static final String ROOT_ID = "auto:root";
    static final String MUSIC_ID = "auto:music";

    private static final int MAX_ITEMS_PER_NODE = 40;
    private static final int MAX_RESOLVED_ITEMS = 256;
    private static final int MAX_SEARCH_QUERY_LENGTH = 200;

    interface ResultCallback {
        void onResult(List<MediaBrowserCompat.MediaItem> items);
    }

    static final class Playable {
        final String videoId;
        final String title;
        final String author;
        final String image;

        Playable(String videoId, String title, String author, String image) {
            this.videoId = videoId;
            this.title = title;
            this.author = author;
            this.image = image;
        }
    }

    static final class PlaybackSelection {
        final List<Playable> queue;
        final int selectedIndex;

        PlaybackSelection(List<Playable> queue, int selectedIndex) {
            this.queue = queue;
            this.selectedIndex = selectedIndex;
        }

        Playable selected() {
            return selectedIndex >= 0 && selectedIndex < queue.size()
                    ? queue.get(selectedIndex) : null;
        }
    }

    private final Context context;
    private final ContentService contentService;
    private final String artworkUri;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CompositeDisposable actions = new CompositeDisposable();
    private final AtomicLong itemSequence = new AtomicLong();
    private final Map<String, MediaItem> browsableItems = boundedMap();
    private final Map<String, PlaybackSelection> playableItems = boundedMap();

    AutomotiveBrowseCatalog(Context context) {
        this.context = context.getApplicationContext();
        contentService = YouTubeServiceManager.instance().getContentService();
        artworkUri = "android.resource://" + this.context.getPackageName()
                + "/" + R.drawable.ic_launcher_smarttube;
    }

    List<MediaBrowserCompat.MediaItem> getRootItems() {
        return Collections.singletonList(root(MUSIC_ID, R.string.auto_music));
    }

    void load(String parentId, ResultCallback callback) {
        if (MUSIC_ID.equals(parentId)) {
            loadGroups(contentService.getMusicObserve(), true, callback);
            return;
        }
        MediaItem source = browsableItems.get(parentId);
        if (source == null) dispatch(callback, Collections.emptyList());
        else loadGroup(contentService.getGroupObserve(source), callback);
    }

    void search(String query, ResultCallback callback) {
        if (TextUtils.isEmpty(query)) {
            loadGroups(contentService.getMusicObserve(), true, callback);
            return;
        }
        String boundedQuery = query.length() > MAX_SEARCH_QUERY_LENGTH
                ? query.substring(0, MAX_SEARCH_QUERY_LENGTH) : query;
        loadGroups(contentService.getSearchObserve(boundedQuery), false, callback);
    }

    PlaybackSelection resolvePlayback(String mediaId) {
        return playableItems.get(mediaId);
    }

    void release() {
        actions.clear();
        browsableItems.clear();
        playableItems.clear();
    }

    private void loadGroup(Observable<MediaGroup> observable, ResultCallback callback) {
        actions.add(observable.take(1)
                .map(group -> fromMusicItems(group.getMediaItems(), true))
                .defaultIfEmpty(Collections.emptyList())
                .subscribe(items -> dispatch(callback, items),
                        error -> dispatch(callback, null)));
    }

    private void loadGroups(Observable<List<MediaGroup>> observable, boolean trustedMusicSource,
                            ResultCallback callback) {
        actions.add(observable.take(1)
                .map(groups -> fromMusicGroups(groups, trustedMusicSource))
                .defaultIfEmpty(Collections.emptyList())
                .subscribe(items -> dispatch(callback, items),
                        error -> dispatch(callback, null)));
    }

    private List<MediaBrowserCompat.MediaItem> fromMusicGroups(List<MediaGroup> groups,
                                                                boolean trustedMusicSource) {
        List<MediaItem> items = new ArrayList<>();
        if (groups != null) {
            for (MediaGroup group : groups) {
                if (group != null && group.getMediaItems() != null) items.addAll(group.getMediaItems());
            }
        }
        return fromMusicItems(items, trustedMusicSource);
    }

    private List<MediaBrowserCompat.MediaItem> fromMusicItems(List<MediaItem> source,
                                                               boolean trustedMusicSource) {
        List<MediaBrowserCompat.MediaItem> result = new ArrayList<>();
        List<Integer> playableResultIndexes = new ArrayList<>();
        List<Playable> playableQueue = new ArrayList<>();
        Set<String> videoIds = new LinkedHashSet<>();
        if (source == null) return result;
        for (MediaItem item : source) {
            if (item == null) continue;
            String videoId = item.getVideoId();
            if (!TextUtils.isEmpty(videoId)
                    && (trustedMusicSource || item.getType() == MediaItem.TYPE_MUSIC)) {
                if (!videoIds.add(videoId)) continue;
                String author = !TextUtils.isEmpty(item.getAuthor()) ? item.getAuthor()
                        : item.getSecondTitle() != null ? item.getSecondTitle().toString() : null;
                playableResultIndexes.add(result.size());
                playableQueue.add(new Playable(videoId, item.getTitle(), author, artworkUri));
                result.add(null);
            } else if (trustedMusicSource && !TextUtils.isEmpty(item.getPlaylistId())) {
                result.add(browsable(item));
            }
            if (result.size() >= MAX_ITEMS_PER_NODE) break;
        }
        List<Playable> queueSnapshot = Collections.unmodifiableList(
                new ArrayList<>(playableQueue));
        for (int i = 0; i < playableQueue.size(); i++) {
            result.set(playableResultIndexes.get(i),
                    playable(playableQueue.get(i), queueSnapshot, i));
        }
        return result;
    }

    private MediaBrowserCompat.MediaItem playable(Playable item, List<Playable> queue,
                                                   int selectedIndex) {
        String id = nextId("play");
        playableItems.put(id, new PlaybackSelection(queue, selectedIndex));
        return new MediaBrowserCompat.MediaItem(description(id, item.title, item.author),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private MediaBrowserCompat.MediaItem browsable(MediaItem item) {
        String id = nextId("browse");
        browsableItems.put(id, item);
        String subtitle = item.getSecondTitle() != null ? item.getSecondTitle().toString() : null;
        return new MediaBrowserCompat.MediaItem(description(id, item.getTitle(), subtitle),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem root(String id, int titleRes) {
        return new MediaBrowserCompat.MediaItem(
                description(id, context.getString(titleRes), null),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaDescriptionCompat description(String id, String title, String subtitle) {
        return new MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconUri(Uri.parse(artworkUri))
                .build();
    }

    private String nextId(String type) {
        return "auto:" + type + ":" + itemSequence.incrementAndGet();
    }

    private void dispatch(ResultCallback callback, List<MediaBrowserCompat.MediaItem> result) {
        mainHandler.post(() -> callback.onResult(result));
    }

    private static <T> Map<String, T> boundedMap() {
        return Collections.synchronizedMap(
                new LinkedHashMap<String, T>(MAX_RESOLVED_ITEMS + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, T> eldest) {
                        return size() > MAX_RESOLVED_ITEMS;
                    }
                });
    }
}
