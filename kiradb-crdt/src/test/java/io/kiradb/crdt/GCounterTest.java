package io.kiradb.crdt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GCounterTest {

    @Test
    void incrementAndValue() {
        GCounter c = new GCounter("a");
        c.increment();
        c.increment(5);
        assertEquals(6, c.value());
    }

    @Test
    void rejectsNonPositiveDelta() {
        GCounter c = new GCounter("a");
        assertThrows(IllegalArgumentException.class, () -> c.increment(0));
        assertThrows(IllegalArgumentException.class, () -> c.increment(-1));
    }

    @Test
    void mergeIsCommutative() {
        GCounter a = new GCounter("a");
        GCounter b = new GCounter("b");
        a.increment(3);
        b.increment(7);

        GCounter ab = new GCounter("a");
        ab.merge(a);
        ab.merge(b);

        GCounter ba = new GCounter("b");
        ba.merge(b);
        ba.merge(a);

        assertEquals(10, ab.value());
        assertEquals(10, ba.value());
        assertEquals(ab.slotsSnapshot(), ba.slotsSnapshot());
    }

    @Test
    void mergeIsIdempotent() {
        GCounter a = new GCounter("a");
        a.increment(4);
        GCounter target = new GCounter("b");
        target.merge(a);
        target.merge(a);
        target.merge(a);
        assertEquals(4, target.value());
    }

    @Test
    void mergeIsAssociative() {
        GCounter a = new GCounter("a");
        a.increment(1);
        GCounter b = new GCounter("b");
        b.increment(2);
        GCounter c = new GCounter("c");
        c.increment(4);

        GCounter left = new GCounter("x");
        left.merge(a);
        left.merge(b);
        left.merge(c);

        GCounter right = new GCounter("y");
        GCounter bc = new GCounter("y");
        bc.merge(b);
        bc.merge(c);
        right.merge(a);
        right.merge(bc);

        assertEquals(7, left.value());
        assertEquals(7, right.value());
        assertEquals(left.slotsSnapshot(), right.slotsSnapshot());
    }

    @Test
    void convergenceUnderShuffledArrival() {
        // Three nodes, each makes some increments locally; all peers gossip
        // to each other in arbitrary orders — every replica must converge.
        GCounter a = new GCounter("a");
        GCounter b = new GCounter("b");
        GCounter c = new GCounter("c");
        a.increment(2);
        a.increment(3);
        b.increment(7);
        c.increment(1);
        c.increment(1);

        // Order 1: a <- b <- c
        GCounter r1 = new GCounter("r1");
        r1.merge(a); r1.merge(b); r1.merge(c);

        // Order 2: c <- a <- b
        GCounter r2 = new GCounter("r2");
        r2.merge(c); r2.merge(a); r2.merge(b);

        // Order 3: with duplicates
        GCounter r3 = new GCounter("r3");
        r3.merge(b); r3.merge(b); r3.merge(c); r3.merge(a); r3.merge(c);

        assertEquals(14, r1.value());
        assertEquals(14, r2.value());
        assertEquals(14, r3.value());
        assertEquals(r1.slotsSnapshot(), r2.slotsSnapshot());
        assertEquals(r1.slotsSnapshot(), r3.slotsSnapshot());
    }

    @Test
    void serializationRoundTrip() {
        GCounter c = new GCounter("a");
        c.increment(42);
        GCounter peer = new GCounter("b");
        peer.increment(7);
        c.merge(peer);

        byte[] bytes = c.serialize();
        GCounter restored = GCounter.deserialize("a", bytes);
        assertEquals(c.value(), restored.value());
        assertEquals(c.slotsSnapshot(), restored.slotsSnapshot());
    }
}
