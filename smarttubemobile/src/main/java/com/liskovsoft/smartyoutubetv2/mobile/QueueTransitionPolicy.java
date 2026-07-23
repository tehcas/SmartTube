package com.liskovsoft.smartyoutubetv2.mobile;

/** Defines whether a playback transition may resume persisted progress. */
final class QueueTransitionPolicy {
    enum Reason {
        EXPLICIT_OPEN,
        QUEUE_ADVANCE
    }

    private QueueTransitionPolicy() {
    }

    static boolean shouldRestoreProgress(Reason reason) {
        return reason == Reason.EXPLICIT_OPEN;
    }
}
