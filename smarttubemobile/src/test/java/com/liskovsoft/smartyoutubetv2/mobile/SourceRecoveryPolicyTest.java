package com.liskovsoft.smartyoutubetv2.mobile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SourceRecoveryPolicyTest {
    @Test
    public void firstExpiredSourceFailureRefreshesCurrentTrack() {
        assertEquals(SourceRecoveryPolicy.Action.REFRESH_CURRENT,
                SourceRecoveryPolicy.decide(true, true, 0, true));
    }

    @Test
    public void repeatedExpiredSourceFailureSkipsToNextTrack() {
        assertEquals(SourceRecoveryPolicy.Action.SKIP_NEXT,
                SourceRecoveryPolicy.decide(true, true, 1, true));
    }

    @Test
    public void nonRefreshableCarFailureSkipsToNextTrack() {
        assertEquals(SourceRecoveryPolicy.Action.SKIP_NEXT,
                SourceRecoveryPolicy.decide(true, true, 0, false));
    }

    @Test
    public void finalTrackStopsAfterRefreshIsExhausted() {
        assertEquals(SourceRecoveryPolicy.Action.STOP,
                SourceRecoveryPolicy.decide(true, false, 1, true));
    }

    @Test
    public void phonePlaybackDoesNotAutoSkipOrRefresh() {
        assertEquals(SourceRecoveryPolicy.Action.STOP,
                SourceRecoveryPolicy.decide(false, true, 0, true));
    }
}
