package com.liskovsoft.smartyoutubetv2.mobile;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

/** Process-local handoff for rich shared models that are intentionally not Parcelable. */
final class MobileSelectionStore {
    private static Video selection;

    private MobileSelectionStore() { }

    static synchronized void put(Video video) {
        selection = video;
    }

    @Nullable
    static synchronized Video peek() {
        return selection;
    }
}
