package org.javalens.core.project;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Renders paths relative to the project root for module ids and load warnings, so every
 * path the build model reports reads the same way.
 */
final class ProjectPaths {

    private ProjectPaths() {
    }

    /**
     * {@code path} relative to {@code projectRoot}, normalized, with '/' separators: "." for
     * the root itself, and a relative form such as {@code ../sibling} for a path outside the
     * project on the same file-system root. A path on another file-system root (another
     * Windows drive) cannot be made relative and stays absolute, with '/' separators.
     */
    static String relativeToRoot(Path projectRoot, Path path) {
        Path normalized = path.normalize();
        Path shown = Objects.equals(projectRoot.getRoot(), normalized.getRoot())
            ? projectRoot.normalize().relativize(normalized)
            : normalized;
        String rendered = shown.toString().replace(File.separatorChar, '/');
        return rendered.isEmpty() ? "." : rendered;
    }
}
