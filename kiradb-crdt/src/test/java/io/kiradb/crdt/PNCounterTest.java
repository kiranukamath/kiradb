package io.kiradb.crdt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PNCounterTest {

    @Test
    void supportsBothDirections() {
        PNCounter c = new PNCounter("a");
        c.increment();
        c.increment();
        c.decrement();
        c.add(5);
        c.add(-2);
        assertEquals(4, c.value());
    }

    @Test
    void mergeIsCommutativeAndIdempotent() {
        PNCounter a = new PNCounter("a");
        PNCounter b = new PNCounter("b");
        a.add(10);
        a.add(-3);
        b.add(7);
        b.add(-1);

        PNCounter ab = new PNCounter("ab");
        ab.merge(a);
        ab.merge(b);
        ab.merge(b);  // idempotent

        PNCounter ba = new PNCounter("ba");
        ba.merge(b);
        ba.merge(a);

        assertEquals(13, ab.value());
        assertEquals(13, ba.value());
    }

    @Test
    void serializationRoundTrip() {
        PNCounter c = new PNCounter("a");
        c.add(15);
        c.add(-4);
        byte[] bytes = c.serialize();
        PNCounter restored = PNCounter.deserialize("a", bytes);
        assertEquals(11, restored.value());
    }
}
