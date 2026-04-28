package io.kiradb.services.flags;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FeatureFlagTest {

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureFlag("", false, 0.5));
    }

    @Test
    void rejectsRolloutOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureFlag("f", false, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureFlag("f", false, 1.1));
    }

    @Test
    void killAndUnkillPreserveRollout() {
        FeatureFlag f = new FeatureFlag("dark-mode", false, 0.42);
        FeatureFlag killed = f.asKilled();
        assertTrue(killed.killed());
        assertEquals(0.42, killed.rolloutPercent());

        FeatureFlag back = killed.asUnkilled();
        assertFalse(back.killed());
        assertEquals(0.42, back.rolloutPercent());
    }

    @Test
    void serializationRoundTrip() {
        FeatureFlag original = new FeatureFlag("checkout-v2", true, 0.73);
        FeatureFlag back = FeatureFlag.deserialize(original.serialize());
        assertEquals(original, back);
    }

    @Test
    void factoriesProduceExpectedValues() {
        assertEquals(1.0, FeatureFlag.fullyEnabled("x").rolloutPercent());
        assertEquals(0.0, FeatureFlag.fullyDisabled("x").rolloutPercent());
        assertFalse(FeatureFlag.fullyEnabled("x").killed());
    }
}
