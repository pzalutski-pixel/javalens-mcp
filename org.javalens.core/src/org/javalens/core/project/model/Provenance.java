package org.javalens.core.project.model;

/**
 * Where a {@link BuildModel}'s structure came from.
 *
 * <ul>
 *   <li>{@link #BUILD_TOOL}: the build tool's own evaluated model (Maven, Gradle, Bazel).</li>
 *   <li>{@link #STATIC_READ}: JavaLens read the build files itself, without the tool
 *       evaluating them, so anything computed at build time is invisible.</li>
 *   <li>{@link #CONVENTION}: no build file declares the structure; source roots come
 *       from layout conventions (plain Java projects).</li>
 * </ul>
 */
public enum Provenance {
    BUILD_TOOL,
    STATIC_READ,
    CONVENTION
}
