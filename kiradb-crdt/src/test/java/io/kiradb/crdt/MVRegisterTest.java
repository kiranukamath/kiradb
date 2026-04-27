package io.kiradb.crdt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MVRegisterTest {

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Set<String> stringValues(final MVRegister r) {
        Set<String> out = new HashSet<>();
        for (byte[] v : r.values()) {
            out.add(new String(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void singleWriteSurfacesSingleValue() {
        MVRegister r = new MVRegister("a");
        r.set(b("hello"));
        assertEquals(Set.of("hello"), stringValues(r));
    }

    @Test
    void concurrentWritesBothSurvive() {
        // Classic Dynamo shopping-cart story: A and B write independently,
        // then sync. Both values must be visible.
        MVRegister a = new MVRegister("A");
        a.set(b("socks"));
        MVRegister bReg = new MVRegister("B");
        bReg.set(b("shirt"));

        a.merge(bReg);
        bReg.merge(a);

        assertEquals(Set.of("socks", "shirt"), stringValues(a));
        assertEquals(Set.of("socks", "shirt"), stringValues(bReg));
    }

    @Test
    void laterCausalWriteSupersedesAllPriorConcurrent() {
        // After both replicas know about both concurrent writes, the next
        // write on either must dominate them, leaving a single value.
        MVRegister a = new MVRegister("A");
        a.set(b("v1"));
        MVRegister b = new MVRegister("B");
        b.set(b("v2"));

        a.merge(b);  // a sees both writes
        a.set(b("v3"));  // app resolves and writes new value

        assertEquals(Set.of("v3"), stringValues(a));

        // Now propagate to B
        b.merge(a);
        assertEquals(Set.of("v3"), stringValues(b));
    }

    @Test
    void mergeIsIdempotentForConcurrentSet() {
        MVRegister a = new MVRegister("A");
        a.set(b("v1"));
        MVRegister b = new MVRegister("B");
        b.set(b("v2"));

        MVRegister r = new MVRegister("R");
        r.merge(a);
        r.merge(b);
        r.merge(a);  // duplicates must not change state
        r.merge(b);

        assertEquals(Set.of("v1", "v2"), stringValues(r));
        assertEquals(2, r.size());
    }

    @Test
    void convergenceUnderShuffledArrival() {
        MVRegister a = new MVRegister("A");
        a.set(b("a-only"));
        MVRegister bReg = new MVRegister("B");
        bReg.set(b("b-only"));
        MVRegister c = new MVRegister("C");
        c.set(b("c-only"));

        MVRegister r1 = new MVRegister("R1");
        r1.merge(a); r1.merge(bReg); r1.merge(c);

        MVRegister r2 = new MVRegister("R2");
        r2.merge(c); r2.merge(a); r2.merge(bReg);

        MVRegister r3 = new MVRegister("R3");
        r3.merge(bReg); r3.merge(a); r3.merge(c); r3.merge(a);

        assertEquals(stringValues(r1), stringValues(r2));
        assertEquals(stringValues(r1), stringValues(r3));
        assertEquals(3, r1.size());
    }

    @Test
    void serializationRoundTrip() {
        MVRegister a = new MVRegister("A");
        a.set(b("hi"));
        MVRegister bReg = new MVRegister("B");
        bReg.set(b("there"));
        a.merge(bReg);

        MVRegister back = MVRegister.deserialize("A", a.serialize());
        assertEquals(stringValues(a), stringValues(back));
    }

    @Test
    void mvRegisterHonestlyExposesConflictUnlikeLWW() {
        // Same scenario, two contracts: LWW silently picks one; MV exposes both.
        MVRegister mv = new MVRegister("A");
        mv.set(b("important-edit-A"));
        MVRegister mvB = new MVRegister("B");
        mvB.set(b("important-edit-B"));
        mv.merge(mvB);

        Set<String> values = stringValues(mv);
        assertEquals(2, values.size(), "MV must surface both concurrent edits");
        assertTrue(values.contains("important-edit-A"));
        assertTrue(values.contains("important-edit-B"));
    }

    @Test
    void worked4StepDynamoExample() {
        // Mirror the worked example from the teaching doc:
        // A writes v1, B writes v2 (concurrent), they sync, A writes v3.
        MVRegister a = new MVRegister("A");
        a.set(b("v1"));
        MVRegister b = new MVRegister("B");
        b.set(b("v2"));

        // sync: both surface both
        a.merge(b);
        b.merge(a);
        assertEquals(2, a.size());
        assertEquals(2, b.size());

        // App on A resolves and writes v3 — this dominates everything A has seen
        a.set(b("v3"));
        assertEquals(Set.of("v3"), stringValues(a));

        // Propagating back to B drops v1 and v2
        b.merge(a);
        assertEquals(Set.of("v3"), stringValues(b));

        // Use list assertion to demonstrate values() returns one element here
        List<byte[]> finalValues = b.values();
        assertEquals(1, finalValues.size());
    }
}
