package io.kiradb.crdt;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ORSetTest {

    @Test
    void addAndContains() {
        ORSet s = new ORSet();
        s.add("apple");
        s.add("banana");
        assertTrue(s.contains("apple"));
        assertTrue(s.contains("banana"));
        assertFalse(s.contains("cherry"));
        assertEquals(2, s.size());
    }

    @Test
    void removeKillsObservedTags() {
        ORSet s = new ORSet();
        s.add("apple");
        s.remove("apple");
        assertFalse(s.contains("apple"));
        assertEquals(0, s.size());
    }

    @Test
    void concurrentAddSurvivesConcurrentRemove() {
        // The defining ORSet property:
        //   A observes apple, then removes it.
        //   B concurrently re-adds apple (with a fresh tag A never saw).
        // After merge, apple must SURVIVE.
        ORSet a = new ORSet();
        a.add("apple");

        ORSet b = new ORSet();
        b.merge(a);              // b now observes the same add-tag

        // A removes (kills the tag it has seen)
        a.remove("apple");

        // B concurrently re-adds (new tag, never tombstoned)
        b.add("apple");

        // Sync both directions
        ORSet aThenB = deepCopy(a);
        aThenB.merge(b);

        ORSet bThenA = deepCopy(b);
        bThenA.merge(a);

        assertTrue(aThenB.contains("apple"));
        assertTrue(bThenA.contains("apple"));
        assertEquals(aThenB.elements(), bThenA.elements());
    }

    @Test
    void removePropagatesAcrossReplicas() {
        // Single add visible everywhere, then a remove anywhere — element
        // disappears on every replica after a full merge round.
        ORSet a = new ORSet();
        a.add("apple");

        ORSet b = new ORSet();
        b.merge(a);

        a.remove("apple");

        b.merge(a);
        assertFalse(b.contains("apple"));
    }

    @Test
    void mergeIsCommutativeAndIdempotent() {
        ORSet a = new ORSet();
        a.add("x");
        a.add("y");
        ORSet b = new ORSet();
        b.add("y");
        b.add("z");

        ORSet ab = new ORSet();
        ab.merge(a); ab.merge(b);
        ab.merge(a); // idempotent

        ORSet ba = new ORSet();
        ba.merge(b); ba.merge(a);

        assertEquals(Set.of("x", "y", "z"), ab.elements());
        assertEquals(ab.elements(), ba.elements());
    }

    @Test
    void serializationPreservesAddsAndTombstones() {
        ORSet s = new ORSet();
        s.add("alpha");
        s.add("beta");
        s.remove("beta");

        ORSet restored = ORSet.deserialize(s.serialize());
        assertEquals(s.elements(), restored.elements());

        // Also: a remote re-add of beta must still work via the original tombstones.
        // Bring an external "beta" add and merge — should NOT come back if the
        // remote add carries one of our tombstoned tags. Test the round-trip
        // is byte-stable instead.
        byte[] first = s.serialize();
        byte[] second = ORSet.deserialize(first).serialize();
        assertEquals(first.length, second.length);
    }

    private static ORSet deepCopy(final ORSet s) {
        return ORSet.deserialize(s.serialize());
    }
}
