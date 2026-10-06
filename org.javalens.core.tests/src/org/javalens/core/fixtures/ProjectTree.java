package org.javalens.core.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lists what a project tree holds, so a test can pin that a load leaves it unchanged by
 * comparing the listing taken before the load with the one taken after.
 */
public final class ProjectTree {

    private ProjectTree() {
    }

    /**
     * Every file and directory under {@code root}, excluding {@code root} itself, relative
     * to it with '/' separators, in sorted order.
     */
    public static Set<String> relativePaths(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> !path.equals(root))
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
