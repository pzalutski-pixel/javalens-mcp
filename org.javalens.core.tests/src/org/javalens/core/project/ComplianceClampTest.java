package org.javalens.core.project;

import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the compiler-level clamp contract: any declared level outside JDT's
 * supported range is pulled inside it (floor 1.8, ceiling the newest level
 * this JDT knows), because JDT's response to an unsupported compliance value
 * is silent no-analysis, not an error. Covers the whole class: legacy floors,
 * future ceilings, and unparseable values - for every spelling the three
 * build-system parsers can surface.
 */
class ComplianceClampTest {

    private static final String LATEST = JavaCore.latestSupportedJavaVersion();

    @Test
    @DisplayName("legacy levels below 1.8 clamp to the floor, in both spellings")
    void legacyLevels_clampToFloor() {
        assertEquals("1.8", ProjectImporter.clampToSupportedRange("1.5"));
        assertEquals("1.8", ProjectImporter.clampToSupportedRange("1.6"));
        assertEquals("1.8", ProjectImporter.clampToSupportedRange("1.7"));
        assertEquals("1.8", ProjectImporter.clampToSupportedRange("5"));
        assertEquals("1.8", ProjectImporter.clampToSupportedRange("6"));
        assertEquals("1.8", ProjectImporter.clampToSupportedRange("7"));
    }

    @Test
    @DisplayName("levels above this JDT's newest clamp to the ceiling")
    void futureLevels_clampToCeiling() {
        int beyond = Integer.parseInt(LATEST) + 1;
        assertEquals(LATEST, ProjectImporter.clampToSupportedRange(String.valueOf(beyond)));
        assertEquals(LATEST, ProjectImporter.clampToSupportedRange("99"));
    }

    @Test
    @DisplayName("unparseable levels fall to the floor - working analysis beats silent none")
    void unparseableLevels_fallToFloor() {
        assertEquals("1.8", ProjectImporter.clampToSupportedRange("${java.version}"));
        assertEquals("1.8", ProjectImporter.clampToSupportedRange("default"));
        assertEquals("1.8", ProjectImporter.clampToSupportedRange(""));
    }

    @Test
    @DisplayName("supported levels pass through untouched, in both spellings")
    void supportedLevels_passThrough() {
        assertEquals("1.8", ProjectImporter.clampToSupportedRange("1.8"));
        assertEquals("8", ProjectImporter.clampToSupportedRange("8"));
        assertEquals("11", ProjectImporter.clampToSupportedRange("11"));
        assertEquals("17", ProjectImporter.clampToSupportedRange("17"));
        assertEquals("21", ProjectImporter.clampToSupportedRange("21"));
        assertEquals(LATEST, ProjectImporter.clampToSupportedRange(LATEST));
    }
}
