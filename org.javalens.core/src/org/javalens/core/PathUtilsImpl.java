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
        this(projectRoot, "true".equalsIgnoreCase(System.getenv("JAVALENS_ABSOLUTE_PATHS")));
    }

    /**
     * Test-friendly constructor: bypasses the env-var read so the
     * {@code useAbsolutePaths=true} branch can be exercised in unit tests.
     * Production callers use the single-argument constructor.
     */
    PathUtilsImpl(Path projectRoot, boolean useAbsolutePaths) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.useAbsolutePaths = useAbsolutePaths;
    }

    @Override
    public String formatPath(String path) {
        return formatPath(Path.of(path));
    }

    @Override
    public String formatPath(Path path) {
        Path normalizedPath = resolveAgainstRoot(path);

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
    public Path resolve(String path) {
        return resolveAgainstRoot(projectRoot.getFileSystem().getPath(path));
    }

    @Override
    public boolean isWithinProject(Path path) {
        return resolveAgainstRoot(path).startsWith(projectRoot);
    }

    /**
     * The normalized path an input names: a relative input is relative to the project root,
     * never to the working directory; an absolute input names itself.
     */
    private Path resolveAgainstRoot(Path path) {
        return projectRoot.resolve(path).normalize();
    }

    /**
     * Check if running on Windows.
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
