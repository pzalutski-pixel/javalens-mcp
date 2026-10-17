package org.javalens.core.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Disk-truth change detection for the loaded project: content-hash stamps
 * over the source roots plus the build files, compared on demand.
 *
 * <p>Stamps are session-local and never persisted; they are written only from
 * bytes actually read off disk, so a matching stamp proves the analyzed
 * content is byte-identical to the current file (chain of custody). Detection
 * never trusts metadata alone — size and mtime are recorded for diagnostics,
 * but the hash is the authority.
 *
 * <p>The hash is MD5: this is change detection over the user's own source
 * tree, not a security boundary — collision resistance against an adversary
 * is not a requirement, and MD5 is built-in and fast.
 *
 * <p>Scope: {@code *.java} files under the source roots (skipping build
 * output directories below a root), the explicit build-file list (a listed file
 * that does not exist at stamping is reported when it appears), and every file
 * under the build directories (such as Gradle's {@code buildSrc}), whose
 * additions, edits and deletions are build-file changes. A new module added
 * outside the known roots is not detectable here: that is a project
 * restructure, which is {@code load_project} territory.
 */
public final class DiskStampService {

    private static final Set<String> SKIP_DIR_NAMES = Set.of("target", "build", "bin", ".git");
    private static final String SKIP_DIR_PREFIX = "bazel-";
    private static final int HASH_BUFFER_BYTES = 64 * 1024;

    /** Size and mtime are diagnostics only; the hash decides. */
    record Stamp(long size, long mtimeNanos, String hash) {
    }

    /**
     * What changed on disk since the stamps were taken. Build files are
     * reported separately because they cannot be repaired per-file - they
     * require a full reload.
     */
    public record ChangeSet(List<Path> edited, List<Path> added, List<Path> deleted,
                            List<Path> buildFilesChanged) {

        public boolean isEmpty() {
            return edited.isEmpty() && added.isEmpty() && deleted.isEmpty()
                && buildFilesChanged.isEmpty();
        }
    }

    private final List<Path> sourceRoots;
    private final List<Path> buildFiles;
    private final List<Path> buildDirectories;
    private final Map<Path, Stamp> sourceStamps = new HashMap<>();
    private final Map<Path, Stamp> buildStamps = new HashMap<>();

    public DiskStampService(List<Path> sourceRoots, List<Path> buildFiles) {
        this(sourceRoots, buildFiles, List.of());
    }

    public DiskStampService(List<Path> sourceRoots, List<Path> buildFiles, List<Path> buildDirectories) {
        this.sourceRoots = sourceRoots.stream().map(DiskStampService::normalize).toList();
        this.buildFiles = buildFiles.stream().map(DiskStampService::normalize).distinct().toList();
        this.buildDirectories = buildDirectories.stream().map(DiskStampService::normalize).distinct().toList();
    }

    /** Build (or rebuild) all stamps from current disk content. */
    public synchronized void stampAll() throws IOException {
        sourceStamps.clear();
        buildStamps.clear();
        sourceStamps.putAll(stampInParallel(walkSources()));
        for (Path buildFile : buildFiles) {
            if (Files.isRegularFile(buildFile)) {
                buildStamps.put(buildFile, stampOf(buildFile));
            }
        }
        for (Path file : walkBuildDirectories()) {
            buildStamps.put(file, stampOf(file));
        }
    }

    /**
     * Hashing dominates verification cost and is embarrassingly parallel:
     * each file is hashed independently (still synchronous to the caller).
     * Measured at ~4x wall-time reduction on multi-core machines.
     */
    private static Map<Path, Stamp> stampInParallel(Set<Path> files) throws IOException {
        try {
            return files.parallelStream().collect(
                java.util.stream.Collectors.toConcurrentMap(file -> file, file -> {
                    try {
                        return stampOf(file);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }));
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Compare current disk content against the stamps. Throws on structural
     * failure (e.g. a vanished source root) - a missing root is not a change
     * set, it is a broken project that needs a full reload.
     */
    public synchronized ChangeSet verify() throws IOException {
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) {
                throw new IOException("Source root no longer exists: " + root);
            }
        }

        Set<Path> onDisk = walkSources();

        List<Path> added = new ArrayList<>();
        List<Path> deleted = new ArrayList<>();
        List<Path> knownOnDisk = new ArrayList<>();

        for (Path file : onDisk) {
            if (sourceStamps.containsKey(file)) {
                knownOnDisk.add(file);
            } else {
                added.add(file);
            }
        }

        List<Path> edited;
        try {
            edited = knownOnDisk.parallelStream()
                .filter(file -> {
                    try {
                        return !sourceStamps.get(file).hash().equals(hashOf(file));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path known : sourceStamps.keySet()) {
            if (!onDisk.contains(known)) {
                deleted.add(known);
            }
        }

        Set<Path> buildCandidates = new HashSet<>(buildFiles);
        Set<Path> inBuildDirectories = walkBuildDirectories();
        buildCandidates.addAll(inBuildDirectories);
        for (Path known : buildStamps.keySet()) {
            if (isInBuildDirectory(known)) {
                buildCandidates.add(known);
            }
        }
        List<Path> buildChanged = new ArrayList<>();
        for (Path buildFile : buildCandidates) {
            Stamp known = buildStamps.get(buildFile);
            boolean exists = Files.isRegularFile(buildFile);
            if (known == null) {
                if (exists) {
                    buildChanged.add(buildFile);
                }
            } else if (!exists || !known.hash().equals(hashOf(buildFile))) {
                buildChanged.add(buildFile);
            }
        }

        edited.sort(Comparator.comparing(Path::toString));
        added.sort(Comparator.comparing(Path::toString));
        deleted.sort(Comparator.comparing(Path::toString));
        buildChanged.sort(Comparator.comparing(Path::toString));
        return new ChangeSet(List.copyOf(edited), List.copyOf(added),
            List.copyOf(deleted), List.copyOf(buildChanged));
    }

    /**
     * Re-stamp the given files from current disk content after a repair:
     * existing files get fresh stamps, vanished files are forgotten.
     */
    public synchronized void restamp(Collection<Path> files) throws IOException {
        for (Path raw : files) {
            Path file = normalize(raw);
            Map<Path, Stamp> map = buildFiles.contains(file) || isInBuildDirectory(file) ? buildStamps : sourceStamps;
            if (Files.isRegularFile(file)) {
                map.put(file, stampOf(file));
            } else {
                map.remove(file);
            }
        }
    }

    public synchronized int stampedFileCount() {
        return sourceStamps.size() + buildStamps.size();
    }

    private Set<Path> walkSources() throws IOException {
        Set<Path> files = new HashSet<>();
        for (Path root : sourceRoots) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && isSkippedDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                        files.add(normalize(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return files;
    }

    /** Every regular file under the build directories that exist, skipping build output directories. */
    private Set<Path> walkBuildDirectories() throws IOException {
        Set<Path> files = new HashSet<>();
        for (Path root : buildDirectories) {
            if (!Files.isDirectory(root)) continue;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && (isSkippedDirectory(dir) || dir.getFileName().toString().equals(".gradle"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        files.add(normalize(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return files;
    }

    private boolean isInBuildDirectory(Path file) {
        return buildDirectories.stream().anyMatch(file::startsWith);
    }

    private static boolean isSkippedDirectory(Path dir) {
        String name = dir.getFileName().toString();
        return SKIP_DIR_NAMES.contains(name) || name.startsWith(SKIP_DIR_PREFIX);
    }

    private static Stamp stampOf(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new Stamp(attrs.size(), attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),
            hashOf(file));
    }

    private static String hashOf(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
