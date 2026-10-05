package org.javalens.core.project;

import org.javalens.core.project.model.LoadWarning;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds module directories from the module names that build files declare. Every path
 * built from a declared name goes through here, so no declaration can abort a load: one
 * that is not a valid path, or whose directory does not exist, yields no directory and
 * exactly one {@link LoadWarning#MODULE_NOT_LOADED} naming the declaring file.
 *
 * <p>A name holding an ISO control character is rejected before any path is built.
 * Windows rejects such names as paths while Linux and macOS accept most of them, so
 * checking first gives the same outcome on every OS.
 *
 * <p>Warnings name the declaring file and the module directory relative to the project
 * root, with '/' separators, and quote the declared name with its control characters
 * escaped: {@code \r}, {@code \n}, {@code \t} and {@code \0}, and any other as a
 * backslash, 'u' and four uppercase hex digits.
 */
final class DeclaredPaths {

    private DeclaredPaths() {
    }

    /**
     * The directory of the Gradle project that {@code settings} includes as
     * {@code declared}. Gradle's colon notation is mapped onto directories: leading colons
     * are dropped and the remaining ones separate directories, so {@code :app:core} is
     * {@code app/core} under the settings file's directory.
     */
    static Optional<Path> gradleProjectDir(Path projectRoot, Path settings, String declared,
            List<LoadWarning> warnings) {
        String relativePath = declared.replaceAll("^:+", "").replace(':', '/');
        return moduleDir(projectRoot, settings, declared, relativePath, warnings);
    }

    /**
     * The directory of the Maven module that {@code pom} declares as
     * {@code <module>declared</module>}, relative to the pom's directory.
     */
    static Optional<Path> mavenModuleDir(Path projectRoot, Path pom, String declared,
            List<LoadWarning> warnings) {
        return moduleDir(projectRoot, pom, declared, declared, warnings);
    }

    private static Optional<Path> moduleDir(Path projectRoot, Path declaringFile, String declared,
            String relativePath, List<LoadWarning> warnings) {
        String file = relativeToRoot(projectRoot, declaringFile);
        String name = escapeControlCharacters(declared);

        for (int i = 0; i < declared.length(); i++) {
            char c = declared.charAt(i);
            if (Character.isISOControl(c)) {
                warnings.add(invalid(file, name,
                    String.format(Locale.ROOT, "control character U+%04X", (int) c)));
                return Optional.empty();
            }
        }

        Path dir;
        try {
            dir = declaringFile.resolveSibling(relativePath);
        } catch (InvalidPathException e) {
            warnings.add(invalid(file, name, e.getReason()));
            return Optional.empty();
        }

        if (!Files.isDirectory(dir)) {
            warnings.add(new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
                file + " declares '" + name + "', but its directory "
                    + relativeToRoot(projectRoot, dir) + " does not exist",
                "Create the directory or remove the declaration from " + file + ".",
                name));
            return Optional.empty();
        }
        return Optional.of(dir);
    }

    private static LoadWarning invalid(String file, String name, String reason) {
        return new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
            file + " declares '" + name + "', which is not a valid path: " + reason,
            "Correct the declaration in " + file + ".",
            name);
    }

    /**
     * {@code path} relative to the project root with '/' separators. A path on another
     * root (another drive) stays absolute.
     */
    private static String relativeToRoot(Path projectRoot, Path path) {
        Path normalized = path.normalize();
        Path shown = Objects.equals(projectRoot.getRoot(), normalized.getRoot())
            ? projectRoot.normalize().relativize(normalized)
            : normalized;
        return shown.toString().replace(File.separatorChar, '/');
    }

    private static String escapeControlCharacters(String declared) {
        StringBuilder escaped = new StringBuilder(declared.length());
        for (int i = 0; i < declared.length(); i++) {
            char c = declared.charAt(i);
            switch (c) {
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                case '\0' -> escaped.append("\\0");
                default -> {
                    if (Character.isISOControl(c)) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
