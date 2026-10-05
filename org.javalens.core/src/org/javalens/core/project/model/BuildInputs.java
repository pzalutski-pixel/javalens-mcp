package org.javalens.core.project.model;

import java.nio.file.Path;
import java.util.List;

/**
 * The files and directories a build reads, so a change to any of them can be detected as
 * requiring a reload.
 *
 * @param files individual files to stamp, including candidates that do not exist yet
 * @param directories directories watched recursively for additions, edits and deletions
 */
public record BuildInputs(List<Path> files, List<Path> directories) {

    public BuildInputs {
        files = List.copyOf(files);
        directories = List.copyOf(directories);
    }
}
