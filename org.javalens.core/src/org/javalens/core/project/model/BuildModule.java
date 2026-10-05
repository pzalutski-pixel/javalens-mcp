package org.javalens.core.project.model;

import java.nio.file.Path;
import java.util.List;

/**
 * One module of a {@link BuildModel}: a Maven module, Gradle project, Bazel package, or
 * the root of a plain Java project.
 *
 * @param id build-system identifier of the module
 * @param dir absolute module directory
 * @param sourceRoots hand-written source roots, in declaration order
 * @param generatedSourceRoots roots that tools such as annotation processors write into
 * @param outputDirs compiled-class directories of this module
 * @param declaredLevel Java level this module declares, or {@code null} when none is known
 * @param evaluated true when the build tool evaluated this module, false when its layout
 *     was read statically or assumed
 */
public record BuildModule(
        String id,
        Path dir,
        List<Path> sourceRoots,
        List<Path> generatedSourceRoots,
        List<Path> outputDirs,
        String declaredLevel,
        boolean evaluated) {

    public BuildModule {
        sourceRoots = List.copyOf(sourceRoots);
        generatedSourceRoots = List.copyOf(generatedSourceRoots);
        outputDirs = List.copyOf(outputDirs);
    }
}
