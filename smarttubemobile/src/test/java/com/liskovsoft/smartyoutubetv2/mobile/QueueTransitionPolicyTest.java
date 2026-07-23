package com.liskovsoft.smartyoutubetv2.mobile;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QueueTransitionPolicyTest {
    @Test
    public void explicitOpenRestoresSavedProgress() {
        assertTrue(QueueTransitionPolicy.shouldRestoreProgress(
                QueueTransitionPolicy.Reason.EXPLICIT_OPEN));
    }

    @Test
    public void queueAdvanceStartsAtZero() {
        assertFalse(QueueTransitionPolicy.shouldRestoreProgress(
                QueueTransitionPolicy.Reason.QUEUE_ADVANCE));
    }
}
