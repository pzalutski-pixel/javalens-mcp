package org.javalens.mcp.fixtures;

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
 * Pins {@link LoadedFixture}'s compact-constructor contract. This is the mcp.tests
 * duplicate of {@code org.javalens.core.fixtures.LoadedFixture}; the same two branches
 * (null → empty list, non-null → unmodifiable view) need pinning here so the duplicates
 * stay observably equivalent.
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
    @DisplayName("non-null warnings -> unmodifiable view")
    void nonNullWarnings_unmodifiableView() {
        List<String> mutable = new ArrayList<>(List.of("BUILD_TOOL_FAILED"));
        LoadedFixture loaded = new LoadedFixture(null, null, mutable);

        assertEquals(1, loaded.warnings().size());
        assertEquals("BUILD_TOOL_FAILED", loaded.warnings().get(0));
        assertThrows(UnsupportedOperationException.class,
            () -> loaded.warnings().add("OTHER"),
            "warnings() view must reject mutation");

        // Pin the documented wrap-not-copy semantic. A future move to List.copyOf would
        // change this — this test will fail intentionally, prompting a deliberate decision.
        mutable.add("LATER_ADDITION");
        assertEquals(2, loaded.warnings().size(),
            "Collections.unmodifiableList wraps without copying; caller mutation IS "
                + "visible. Pin this so a deep-copy refactor would fail intentionally.");
        assertFalse(loaded.warnings().isEmpty());
    }
}
