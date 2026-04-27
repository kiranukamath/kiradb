package io.kiradb.crdt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VectorClockTest {

    @Test
    void emptyClocksAreEqual() {
        assertEquals(VectorClock.Ordering.EQUAL, new VectorClock().compare(new VectorClock()));
    }

    @Test
    void sameSlotIncrementIsBefore() {
        VectorClock a = new VectorClock();
        a.increment("n1");
        VectorClock b = a.copy();
        b.increment("n1");
        assertEquals(VectorClock.Ordering.BEFORE, a.compare(b));
        assertEquals(VectorClock.Ordering.AFTER, b.compare(a));
    }

    @Test
    void differentSlotsAreConcurrent() {
        VectorClock a = new VectorClock();
        a.increment("n1");
        VectorClock b = new VectorClock();
        b.increment("n2");
        assertEquals(VectorClock.Ordering.CONCURRENT, a.compare(b));
        assertEquals(VectorClock.Ordering.CONCURRENT, b.compare(a));
    }

    @Test
    void mergeMaxThenIncrementProducesAfter() {
        // The full Lamport receive rule: take element-wise max, then bump own slot.
        VectorClock a = new VectorClock();
        a.increment("n1");                // {n1:1}
        VectorClock b = new VectorClock();
        b.increment("n2");                // {n2:1}

        b.mergeMax(a);                    // {n1:1, n2:1}
        b.increment("n2");                // {n1:1, n2:2}

        assertEquals(VectorClock.Ordering.AFTER, b.compare(a));
    }

    @Test
    void worked3NodeExample() {
        // From CLAUDE.md teaching example: A writes, B writes, B receives A.
        VectorClock a = new VectorClock();
        a.increment("A");                 // A's clock after local write
        VectorClock writeAvc = a.copy();  // {A:1}

        VectorClock b = new VectorClock();
        b.increment("B");                 // B's clock after concurrent write
        VectorClock writeBvc = b.copy();  // {B:1}

        // Concurrent
        assertEquals(VectorClock.Ordering.CONCURRENT, writeAvc.compare(writeBvc));

        // B then receives A's message and does another local event
        b.mergeMax(writeAvc);             // {A:1, B:1}
        b.increment("B");                 // {A:1, B:2}

        // B's new clock is AFTER both prior writes
        assertEquals(VectorClock.Ordering.AFTER, b.compare(writeAvc));
        assertEquals(VectorClock.Ordering.AFTER, b.compare(writeBvc));
    }

    @Test
    void serializationRoundTrip() {
        VectorClock vc = new VectorClock();
        vc.increment("a");
        vc.increment("a");
        vc.increment("b");
        VectorClock restored = VectorClock.deserialize(vc.serialize());
        assertEquals(VectorClock.Ordering.EQUAL, vc.compare(restored));
        assertEquals(vc, restored);
    }
}
