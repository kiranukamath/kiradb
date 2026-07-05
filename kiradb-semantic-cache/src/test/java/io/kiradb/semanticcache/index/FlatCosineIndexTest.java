package io.kiradb.semanticcache.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FlatCosineIndex}: add/replace, search ordering,
 * top-k bounding, and removal.
 */
class FlatCosineIndexTest {

    private FlatCosineIndex index;

    @BeforeEach
    void setUp() {
        index = new FlatCosineIndex();
    }

    /** Unit vector along one of three axes, or a normalized blend. */
    private static float[] vec(final float x, final float y, final float z) {
        double norm = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        return new float[] {(float) (x / norm), (float) (y / norm), (float) (z / norm)};
    }

    @Test
    void searchReturnsBestFirst() {
        index.add("x", vec(1, 0, 0));
        index.add("y", vec(0, 1, 0));
        index.add("xy", vec(1, 1, 0));

        List<SearchResult> results = index.search(vec(1, 0.1f, 0), 3);
        assertEquals(3, results.size());
        assertEquals("x", results.get(0).id());
        assertEquals("xy", results.get(1).id());
        assertEquals("y", results.get(2).id());
        assertTrue(results.get(0).score() >= results.get(1).score());
        assertTrue(results.get(1).score() >= results.get(2).score());
    }

    @Test
    void searchRespectsTopK() {
        for (int i = 0; i < 10; i++) {
            index.add("v" + i, vec(1, i * 0.1f, 0));
        }
        assertEquals(3, index.search(vec(1, 0, 0), 3).size());
        assertEquals(10, index.search(vec(1, 0, 0), 50).size());
        assertTrue(index.search(vec(1, 0, 0), 0).isEmpty());
    }

    @Test
    void exactMatchScoresOne() {
        index.add("a", vec(3, 4, 0));
        SearchResult best = index.search(vec(3, 4, 0), 1).get(0);
        assertEquals("a", best.id());
        assertEquals(1.0f, best.score(), 1e-5f);
    }

    @Test
    void addReplacesExistingId() {
        index.add("a", vec(1, 0, 0));
        index.add("a", vec(0, 1, 0));
        assertEquals(1, index.size());
        SearchResult best = index.search(vec(0, 1, 0), 1).get(0);
        assertEquals(1.0f, best.score(), 1e-5f);
    }

    @Test
    void removeDeletesAndReportsPresence() {
        index.add("a", vec(1, 0, 0));
        assertTrue(index.remove("a"));
        assertFalse(index.remove("a"));
        assertEquals(0, index.size());
        assertTrue(index.search(vec(1, 0, 0), 1).isEmpty());
    }

    @Test
    void clearEmptiesTheIndex() {
        index.add("a", vec(1, 0, 0));
        index.add("b", vec(0, 1, 0));
        index.clear();
        assertEquals(0, index.size());
    }
}
