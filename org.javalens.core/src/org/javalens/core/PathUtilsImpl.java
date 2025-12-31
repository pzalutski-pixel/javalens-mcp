package org.javalens.core;

import java.nio.file.Path;

/**
 * Implementation of path utilities for JavaLens.
 * Provides consistent path formatting across platforms.
 */
public class PathUtilsImpl implements IPathUtils {

    private final Path projectRoot;
    private final boolean useAbsolutePaths;

    public PathUtilsImpl(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.useAbsolutePaths = "true".equalsIgnoreCase(System.getenv("JAVALENS_ABSOLUTE_PATHS"));
    }

    @Override
    public String formatPath(String absolutePath) {
        return formatPath(Path.of(absolutePath));
    }

    @Override
    public String formatPath(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();

        String result;
        if (useAbsolutePaths) {
            result = normalizedPath.toString();
        } else {
            // Try to make relative to project root
            if (normalizedPath.startsWith(projectRoot)) {
                result = projectRoot.relativize(normalizedPath).toString();
            } else {
                result = normalizedPath.toString();
            }
        }

        // Use forward slashes for consistency
        return result.replace('\\', '/');
    }

    @Override
    public Path getProjectRoot() {
        return projectRoot;
    }

    @Override
    public boolean isUsingAbsolutePaths() {
        return useAbsolutePaths;
    }

    @Override
    public Path resolve(String relativePath) {
        return projectRoot.resolve(relativePath).normalize();
    }

    @Override
    public boolean isWithinProject(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        return normalizedPath.startsWith(projectRoot);
    }

    /**
     * Check if running on Windows.
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
