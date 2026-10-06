package org.javalens.core.project;

import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link DeclaredLevels}: the analysis level is the highest level the modules declare,
 * compared by Java feature number, and modules that declare different levels are named by
 * one {@link LoadWarning#COMPLIANCE_LEVEL_MIXED}.
 */
class DeclaredLevelsTest {

    private static final Path DIR = Path.of("project").toAbsolutePath();

    @Test
    @DisplayName("No module declares a level: no analysis level and no warning")
    void noDeclaredLevel() {
        List<LoadWarning> warnings = new ArrayList<>();

        String level = DeclaredLevels.analysisLevel(List.of(module(":", null), module(":app", null)), warnings);

        assertAll(
            () -> assertNull(level, "analysis level"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("One module declares a level: it is the analysis level, with no warning")
    void oneDeclaredLevel() {
        List<LoadWarning> warnings = new ArrayList<>();

        String level = DeclaredLevels.analysisLevel(List.of(module(":", null), module(":app", "17")), warnings);

        assertAll(
            () -> assertEquals("17", level, "analysis level"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("Modules declaring the same level: that level, with no warning")
    void sameDeclaredLevel() {
        List<LoadWarning> warnings = new ArrayList<>();

        String level = DeclaredLevels.analysisLevel(List.of(module(":app", "21"), module(":lib", "21")), warnings);

        assertAll(
            () -> assertEquals("21", level, "analysis level"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("Different levels: the highest by feature number, and one COMPLIANCE_LEVEL_MIXED listing the declaring modules in order")
    void differentDeclaredLevels() {
        // Compared as text, "9" would beat "17" and "1.8" would beat "11"; by feature
        // number 17 is the highest.
        List<LoadWarning> warnings = new ArrayList<>();

        String level = DeclaredLevels.analysisLevel(List.of(
            module(":a", "9"), module(":b", "17"), module(":c", null), module(":d", "1.8")), warnings);

        assertAll(
            () -> assertEquals("17", level, "analysis level"),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_MIXED,
                    "Modules declare different Java levels (:a 9, :b 17, :d 1.8); "
                        + "the project is analyzed at 17, the highest",
                    "Code valid only at a lower level is analyzed at the highest level, "
                        + "so a module's own level is not enforced.")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("Two spellings of one level, '1.8' then '8', are the same level: no warning, and the analysis level keeps the first spelling")
    void spellingsOfOneLevelAreTheSameLevel() {
        List<LoadWarning> warnings = new ArrayList<>();

        String level = DeclaredLevels.analysisLevel(List.of(module(":a", "1.8"), module(":b", "8")), warnings);

        assertAll(
            () -> assertEquals("1.8", level, "analysis level"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("Two spellings of one level, '8' then '1.8': no warning, and the analysis level is '8'")
    void spellingsOfOneLevelInTheOtherOrder() {
        List<LoadWarning> warnings = new ArrayList<>();

        String level = DeclaredLevels.analysisLevel(List.of(module(":a", "8"), module(":b", "1.8")), warnings);

        assertAll(
            () -> assertEquals("8", level, "analysis level"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("Levels 17, 21 and 21: one COMPLIANCE_LEVEL_MIXED listing all three, analysis level 21")
    void repeatedHighestLevelStillMixed() {
        List<LoadWarning> warnings = new ArrayList<>();

        String level = DeclaredLevels.analysisLevel(
            List.of(module(":a", "17"), module(":b", "21"), module(":c", "21")), warnings);

        assertAll(
            () -> assertEquals("21", level, "analysis level"),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_MIXED,
                    "Modules declare different Java levels (:a 17, :b 21, :c 21); "
                        + "the project is analyzed at 21, the highest",
                    "Code valid only at a lower level is analyzed at the highest level, "
                        + "so a module's own level is not enforced.")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("featureNumber reads '1.x' as x, a plain number as itself, and has no value for anything else")
    void featureNumbers() {
        assertAll(
            () -> assertEquals(OptionalInt.of(8), DeclaredLevels.featureNumber("1.8"), "1.8"),
            () -> assertEquals(OptionalInt.of(17), DeclaredLevels.featureNumber("17"), "17"),
            () -> assertEquals(OptionalInt.of(11), DeclaredLevels.featureNumber(" 11 "), "padded 11"),
            () -> assertEquals(OptionalInt.empty(), DeclaredLevels.featureNumber("VERSION_HIGHER"), "word"),
            () -> assertEquals(OptionalInt.empty(), DeclaredLevels.featureNumber("21.0.1"), "three parts"));
    }

    private static BuildModule module(String id, String declaredLevel) {
        return new BuildModule(id, DIR, List.of(), List.of(), List.of(), declaredLevel, true);
    }
}
