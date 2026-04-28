package io.kiradb.crdt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class LWWRegisterTest {

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void higherTimestampWins() {
        LWWRegister a = new LWWRegister("a");
        a.set(b("v1"), 100);

        LWWRegister b = new LWWRegister("b");
        b.set(b("v2"), 200);

        a.merge(b);
        assertArrayEquals(b("v2"), a.get());
        assertEquals(200, a.timestamp());
    }

    @Test
    void tieBrokenByLargerNodeId() {
        LWWRegister a = new LWWRegister("a");
        a.set(b("vA"), 100);

        LWWRegister z = new LWWRegister("z");
        z.set(b("vZ"), 100);

        // Merge in both directions — both must converge to the same answer.
        LWWRegister copyA = LWWRegister.deserialize("a", a.serialize());
        copyA.merge(z);

        LWWRegister copyZ = LWWRegister.deserialize("z", z.serialize());
        copyZ.merge(a);

        assertArrayEquals(b("vZ"), copyA.get());
        assertArrayEquals(b("vZ"), copyZ.get());
    }

    @Test
    void mergeIsIdempotentAndCommutative() {
        LWWRegister a = new LWWRegister("a");
        a.set(b("hello"), 50);
        LWWRegister b = new LWWRegister("b");
        b.set(b("world"), 99);

        LWWRegister r1 = LWWRegister.deserialize("r1", new LWWRegister("r1").serialize());
        r1.merge(a); r1.merge(b); r1.merge(b);

        LWWRegister r2 = LWWRegister.deserialize("r2", new LWWRegister("r2").serialize());
        r2.merge(b); r2.merge(a);

        assertArrayEquals(r1.get(), r2.get());
        assertEquals(r1.timestamp(), r2.timestamp());
    }

    @Test
    void emptyRegisterReturnsNull() {
        assertNull(new LWWRegister("a").get());
    }

    @Test
    void rapidSameNodeRewritesAllSucceed() {
        // Regression: two set() calls within the same millisecond from the same
        // node both must win — the LWW tiebreaker applies between DIFFERENT
        // nodes, not between successive writes from the same node.
        LWWRegister r = new LWWRegister("only-node");
        r.set(b("first"));
        r.set(b("second"));
        r.set(b("third"));
        assertArrayEquals(b("third"), r.get());
    }

    @Test
    void serializationRoundTrip() {
        LWWRegister r = new LWWRegister("a");
        r.set(b("hi"), 42);
        LWWRegister back = LWWRegister.deserialize("a", r.serialize());
        assertArrayEquals(b("hi"), back.get());
        assertEquals(42, back.timestamp());
    }
}
