package org.javalens.core;

import java.nio.file.Path;

/**
 * Interface for path handling utilities.
 * Provides cross-platform path operations with consistent formatting.
 *
 * <p>All paths are formatted with forward slashes for consistency.
 * Relative paths are used by default to minimize token usage.
 */
public interface IPathUtils {

    /**
     * Format a path for output.
     * Returns relative path by default, absolute if JAVALENS_ABSOLUTE_PATHS=true.
     *
     * @param absolutePath The absolute path to format
     * @return Formatted path string with forward slashes
     */
    String formatPath(String absolutePath);

    /**
     * Format a Path object for output.
     *
     * @param path The path to format
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
     * Resolve a path relative to project root.
     *
     * @param relativePath Path relative to project root
     * @return Resolved absolute path
     */
    Path resolve(String relativePath);

    /**
     * Check if a path is within the project root.
     *
     * @param path The path to check
     * @return true if path is under project root
     */
    boolean isWithinProject(Path path);
}
