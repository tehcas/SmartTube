package com.liskovsoft.smartyoutubetv2.mobile;

/** Chooses bounded recovery behavior for automotive playback failures. */
final class SourceRecoveryPolicy {
    enum Action {
        REFRESH_CURRENT,
        SKIP_NEXT,
        STOP
    }

    private SourceRecoveryPolicy() {
    }

    static Action decide(boolean carAudioOnly, boolean hasNext,
                         int refreshAttempts, boolean expiredSourceFailure) {
        if (!carAudioOnly) return Action.STOP;
        if (expiredSourceFailure && refreshAttempts == 0) return Action.REFRESH_CURRENT;
        return hasNext ? Action.SKIP_NEXT : Action.STOP;
    }
}
