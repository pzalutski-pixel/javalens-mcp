package org.javalens.core.fixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link LoadedFixture}'s compact-constructor contract. The happy-path
 * construction by {@link TestProjectHelper#loadFixture} is already exercised throughout
 * the test suite; this file pins the two branches that production callers never hit:
 *
 * <ul>
 *   <li>{@code warnings == null} → empty list, not NPE.</li>
 *   <li>{@code warnings != null} → the returned view is unmodifiable, even when the
 *       caller passes a mutable list.</li>
 * </ul>
 */
class LoadedFixtureTest {

    @Test
    @DisplayName("null warnings -> empty immutable list")
    void nullWarnings_normalizedToEmptyList() {
        LoadedFixture loaded = new LoadedFixture(null, null, null);
        assertNotNull(loaded.warnings(), "warnings() must never return null");
        assertTrue(loaded.warnings().isEmpty(), "null input must normalize to empty list");
        assertThrows(UnsupportedOperationException.class,
            () -> loaded.warnings().add("X"),
            "Empty list from null normalization must still be immutable");
    }

    @Test
    @DisplayName("non-null warnings -> unmodifiable view that survives caller mutation")
    void nonNullWarnings_unmodifiableView() {
        List<String> mutable = new ArrayList<>(List.of("MAVEN_SUBPROCESS_FAILED"));
        LoadedFixture loaded = new LoadedFixture(null, null, mutable);

        assertEquals(1, loaded.warnings().size());
        assertEquals("MAVEN_SUBPROCESS_FAILED", loaded.warnings().get(0));
        assertThrows(UnsupportedOperationException.class,
            () -> loaded.warnings().add("OTHER"),
            "warnings() view must reject mutation");

        // Defensive: the view should reflect the original (Collections.unmodifiableList
        // wraps without copying, so caller mutation IS visible). This test pins that
        // documented behavior so a future "deep copy" refactor surfaces intentionally.
        mutable.add("LATER_ADDITION");
        assertEquals(2, loaded.warnings().size(),
            "Collections.unmodifiableList wraps without copying; caller mutation IS "
                + "visible. If this assertion ever changes (e.g. moved to List.copyOf), "
                + "that's an intentional API change, not a passive regression.");
        assertFalse(loaded.warnings().isEmpty(),
            "Sanity: warnings remains non-empty after caller mutation");
    }
}
