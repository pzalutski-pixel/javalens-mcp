package org.javalens.core.exceptions;

import java.nio.file.Path;
import java.util.List;

/**
 * Raised when disk verification finds build-file changes (pom.xml,
 * build.gradle, ...): the classpath itself may have moved, which cannot be
 * repaired per-file — only a full {@code load_project} restores a trustworthy
 * model. Carriers of this exception must surface it as an explicit
 * instruction, never answer around it.
 */
public class ReloadRequiredException extends Exception {

    private final List<Path> changedBuildFiles;

    public ReloadRequiredException(List<Path> changedBuildFiles) {
        super("Build file(s) changed on disk: " + changedBuildFiles
            + ". The classpath may have changed - call load_project to rebuild the model.");
        this.changedBuildFiles = List.copyOf(changedBuildFiles);
    }

    public List<Path> getChangedBuildFiles() {
        return changedBuildFiles;
    }
}
