package org.javalens.core.project;

import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * The Java level a project is analyzed at, from the levels its modules declare. All modules
 * are analyzed as one project, so the highest declared level applies to every module, and
 * modules that declare different levels are named by a
 * {@link LoadWarning#COMPLIANCE_LEVEL_MIXED}.
 */
final class DeclaredLevels {

    private DeclaredLevels() {
    }

    /**
     * The highest level the modules declare, compared by {@link #featureNumber}, or
     * {@code null} when no module declares one: the spelling of the first module that
     * declares the highest feature number. A level with no feature number ranks below every
     * level that has one. Levels are the same level when their feature numbers are equal, so
     * {@code "1.8"} and {@code "8"} are one level; a level with no feature number is the same
     * as another only when spelled the same. When the modules declare at least two different
     * levels, one {@link LoadWarning#COMPLIANCE_LEVEL_MIXED} listing each declaring module's
     * id and level as declared, in module order, is appended to {@code warnings}.
     */
    static String analysisLevel(List<BuildModule> modules, List<LoadWarning> warnings) {
        List<BuildModule> declaring = modules.stream()
            .filter(module -> module.declaredLevel() != null)
            .toList();
        if (declaring.isEmpty()) {
            return null;
        }
        String highest = declaring.stream()
            .map(BuildModule::declaredLevel)
            .max(Comparator.comparingInt(level -> featureNumber(level).orElse(-1)))
            .orElseThrow();
        long distinct = declaring.stream().map(module -> sameLevelKey(module.declaredLevel())).distinct().count();
        if (distinct > 1) {
            String levels = declaring.stream()
                .map(module -> module.id() + " " + module.declaredLevel())
                .collect(Collectors.joining(", "));
            warnings.add(new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_MIXED,
                "Modules declare different Java levels (" + levels + "); the project is analyzed at "
                    + highest + ", the highest",
                "Code valid only at a lower level is analyzed at the highest level, so a module's own level "
                    + "is not enforced."));
        }
        return highest;
    }

    /**
     * What two levels share when they are the same level: the feature number, or, for a
     * level with none, its spelling.
     */
    private static Object sameLevelKey(String level) {
        OptionalInt feature = featureNumber(level);
        return feature.isPresent() ? (Object) feature.getAsInt() : level;
    }

    /**
     * The Java feature number a level names: {@code "1.x"} is {@code x}, as in
     * {@code "1.8"}, and otherwise the level read as a number, truncated, after trimming, as
     * in {@code "17"}. Empty when the level is not a number in either form.
     */
    static OptionalInt featureNumber(String level) {
        String normalized = level.trim();
        if (normalized.startsWith("1.")) {
            normalized = normalized.substring(2);
        }
        try {
            return OptionalInt.of((int) Double.parseDouble(normalized));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
