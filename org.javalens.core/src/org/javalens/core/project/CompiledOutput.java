package org.javalens.core.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Counts the classes in a module's compiled output that have no linked source. The
 * orchestrator keeps an output on the classpath only when the count is above zero or the
 * output cannot be read: source-less classes (other JVM languages, bytecode generators,
 * sources in folders the build does not declare) are visible nowhere else, while output
 * whose every class has a linked source only adds compiled duplicates of linked types that
 * reflect the last build.
 */
final class CompiledOutput {

    private static final Logger log = LoggerFactory.getLogger(CompiledOutput.class);

    private static final String CLASS_SUFFIX = ".class";

    /** Descriptor class files, which declare no class and are skipped at any depth. */
    private static final Set<String> DESCRIPTORS = Set.of("module-info.class", "package-info.class");

    private CompiledOutput() {
    }

    /**
     * The number of top-level classes in {@code output} that have no source in
     * {@code sourceRoots}, or empty when {@code output} cannot be read.
     *
     * <p>{@code output} is a class directory or a jar. A top-level class is an entry ending
     * in {@code .class} whose simple name contains no {@code $}; {@code module-info.class}
     * and {@code package-info.class} are skipped at any depth. A class {@code a/b/C.class}
     * has a source when some root in {@code sourceRoots} holds the file {@code a/b/C.java}.
     * A jar class whose name is not a valid path on this file system has no source.
     *
     * <p>Class-file contents are never read, so the count is conservative: a non-public
     * top-level class declared in a {@code .java} file of another name counts as
     * source-less. Its output is then kept, so the class stays visible, and results for its
     * module may include compiled duplicates of linked sources.
     *
     * <p>An output that cannot be read is logged at warn and has no count: its classes
     * cannot be compared with the sources, so the caller keeps it.
     */
    static OptionalInt sourcelessClassCount(Path output, List<Path> sourceRoots) {
        List<String> entries;
        try {
            entries = Files.isDirectory(output) ? directoryEntries(output) : jarEntries(output);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not read compiled output {}; keeping it on the classpath", output, e);
            return OptionalInt.empty();
        }
        int count = 0;
        for (String entry : entries) {
            if (isTopLevelClass(entry) && !hasSource(entry, sourceRoots)) {
                count++;
            }
        }
        return OptionalInt.of(count);
    }

    /** Every file under {@code directory}, relative to it with '/' separators. */
    private static List<String> directoryEntries(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                .map(file -> directory.relativize(file).toString().replace(File.separatorChar, '/'))
                .toList();
        }
    }

    /** The name of every file entry in {@code jar}. */
    private static List<String> jarEntries(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream()
                .filter(entry -> !entry.isDirectory())
                .map(ZipEntry::getName)
                .toList();
        }
    }

    private static boolean isTopLevelClass(String entry) {
        String fileName = entry.substring(entry.lastIndexOf('/') + 1);
        return fileName.endsWith(CLASS_SUFFIX)
            && fileName.indexOf('$') < 0
            && !DESCRIPTORS.contains(fileName);
    }

    private static boolean hasSource(String classEntry, List<Path> sourceRoots) {
        String source = classEntry.substring(0, classEntry.length() - CLASS_SUFFIX.length()) + ".java";
        for (Path root : sourceRoots) {
            Path candidate;
            try {
                candidate = root.resolve(source);
            } catch (InvalidPathException e) {
                // No file on this file system can carry the name, so no source does.
                log.debug("Class {} cannot name a source file: {}", classEntry, e.getMessage());
                return false;
            }
            if (Files.isRegularFile(candidate)) {
                return true;
            }
        }
        return false;
    }
}
