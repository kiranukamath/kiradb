package io.kiradb.core.storage.tier;

import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link AccessTracker}. */
class AccessTrackerTest {

    @Test
    void untrackedKeyScoresZero() {
        AccessTracker tracker = new AccessTracker();
        assertEquals(0.0, tracker.score("missing".getBytes()));
    }

    @Test
    void singleAccessCreatesEntry() {
        AccessTracker tracker = new AccessTracker();
        tracker.recordAccess("key".getBytes(), 3);
        assertTrue(tracker.score("key".getBytes()) > 0.0);
    }

    @Test
    void moreAccessesHigherScore() {
        AccessTracker tracker = new AccessTracker();
        byte[] key1 = "hot".getBytes();
        byte[] key2 = "cold".getBytes();

        for (int i = 0; i < 100; i++) {
            tracker.recordAccess(key1, 3);
        }
        tracker.recordAccess(key2, 4);

        assertTrue(tracker.score(key1) > tracker.score(key2),
                "100 accesses should score higher than 1");
    }

    @Test
    void snapshotContainsCorrectCounts() {
        AccessTracker tracker = new AccessTracker();
        byte[] key = "user:123".getBytes();
        for (int i = 0; i < 5; i++) {
            tracker.recordAccess(key, 8);
        }
        AccessStats stats = tracker.snapshot(key);
        assertNotNull(stats);
        assertEquals(5, stats.accessCount());
        assertEquals(8, stats.keySizeBytes());
        assertTrue(stats.decayedScore() > 0.0);
    }

    @Test
    void snapshotReturnsNullForUntrackedKey() {
        AccessTracker tracker = new AccessTracker();
        assertNull(tracker.snapshot("ghost".getBytes()));
    }

    @Test
    void removeStopsTracking() {
        AccessTracker tracker = new AccessTracker();
        byte[] key = "temp".getBytes();
        tracker.recordAccess(key, 4);
        tracker.remove(key);

        assertEquals(0.0, tracker.score(key));
        assertNull(tracker.snapshot(key));
        assertEquals(0, tracker.size());
    }

    @Test
    void allSnapshotsReturnsAllTrackedKeys() {
        AccessTracker tracker = new AccessTracker();
        tracker.recordAccess("a".getBytes(), 1);
        tracker.recordAccess("b".getBytes(), 1);
        tracker.recordAccess("c".getBytes(), 1);

        Collection<AccessStats> all = tracker.allSnapshots();
        assertEquals(3, all.size());
    }

    @Test
    void scoreDecaysOverTime() throws InterruptedException {
        AccessTracker tracker = new AccessTracker();
        byte[] key = "key".getBytes();
        tracker.recordAccess(key, 3);
        double scoreNow = tracker.score(key);

        // Wait 100ms — score should be slightly lower now
        Thread.sleep(100);
        double scoreLater = tracker.score(key);

        assertTrue(scoreNow > scoreLater,
                "Score should decay over time: scoreNow=" + scoreNow + " scoreLater=" + scoreLater);
    }

    @Test
    void sizeTracksEntryCount() {
        AccessTracker tracker = new AccessTracker();
        assertEquals(0, tracker.size());
        tracker.recordAccess("k1".getBytes(), 2);
        assertEquals(1, tracker.size());
        tracker.recordAccess("k2".getBytes(), 2);
        assertEquals(2, tracker.size());
        tracker.remove("k1".getBytes());
        assertEquals(1, tracker.size());
    }
}
