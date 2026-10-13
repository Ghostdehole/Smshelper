package com.ghost.smshelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultipartSendTrackerTest {
    @Test fun waitsForEveryDistinctPartAndReportsSuccessOnlyOnce() {
        val tracker = MultipartSendTracker()
        assertNull(tracker.record("first", 1, 3, true))
        assertNull(tracker.record("first", 1, 3, true))
        assertNull(tracker.record("first", -1, 3, true))
        assertNull(tracker.record("first", 3, 3, true))
        assertNull(tracker.record("first", 0, 4, true))
        assertNull(tracker.record("first", 0, 3, true))
        assertEquals(SendOutcome.SENT, tracker.record("first", 2, 3, true))
        assertNull(tracker.record("first", 2, 3, true))
        assertEquals(SendOutcome.SENT, tracker.record("second", 0, 1, true))
    }

    @Test fun oneFailedPartWinsAndDoesNotLeakIntoOtherRequests() {
        val tracker = MultipartSendTracker()
        assertNull(tracker.record("failed", 0, 3, true))
        assertEquals(SendOutcome.FAILED, tracker.record("failed", 2, 3, false))
        assertNull(tracker.record("failed", 2, 3, false))
        assertNull(tracker.record("failed", 1, 3, true))
        assertNull(tracker.record("next", 0, 2, true))
        assertEquals(SendOutcome.SENT, tracker.record("next", 1, 2, true))
        assertNull(tracker.record("invalid", 0, 0, false))
    }
}
