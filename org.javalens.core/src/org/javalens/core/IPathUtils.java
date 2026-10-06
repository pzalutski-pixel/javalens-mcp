package org.javalens.core;

import java.nio.file.Path;

/**
 * Interface for path handling utilities.
 * Provides cross-platform path operations with consistent formatting.
 *
 * <p>All paths are formatted with forward slashes for consistency.
 * Relative paths are used by default to minimize token usage.
 *
 * <p>Every method takes a relative input as relative to the project root, never to the
 * working directory; an absolute input names itself.
 */
public interface IPathUtils {

    /**
     * Format a path for output.
     * Returns the path relative to the project root by default, absolute if
     * JAVALENS_ABSOLUTE_PATHS=true; a path outside the project root is always absolute.
     *
     * @param path The path to format, absolute or relative to the project root
     * @return Formatted path string with forward slashes
     */
    String formatPath(String path);

    /**
     * Format a Path object for output, as {@link #formatPath(String)} does.
     *
     * @param path The path to format, absolute or relative to the project root
     * @return Formatted path string with forward slashes
     */
    String formatPath(Path path);

    /**
     * Get the project root path.
     *
     * @return The absolute project root path
     */
    Path getProjectRoot();

    /**
     * Check if using absolute paths.
     *
     * @return true if JAVALENS_ABSOLUTE_PATHS=true
     */
    boolean isUsingAbsolutePaths();

    /**
     * Resolve a path against the project root.
     *
     * @param path Path relative to the project root, or an absolute path
     * @return Resolved absolute normalized path
     */
    Path resolve(String path);

    /**
     * Check if a path is within the project root.
     *
     * @param path The path to check, absolute or relative to the project root
     * @return true if path is under project root
     */
    boolean isWithinProject(Path path);
}
