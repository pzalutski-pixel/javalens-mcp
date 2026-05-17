package org.javalens.core.project;

import org.javalens.core.project.model.LoadWarning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bazel build-system support for {@link ProjectImporter}.
 *
 * <p>Extracted from {@link ProjectImporter} as the second step of the 1.4.0 E-10
 * god-class split. The orchestrator owns build-system detection and dispatch; this
 * class owns the Bazel-specific surface:
 * <ul>
 *   <li>Source-path discovery from BUILD/BUILD.bazel packages, plus the legacy
 *       fallback for layouts that put .java files next to BUILD files directly.</li>
 *   <li>Classpath assembly by walking {@code bazel-bin}/{@code bazel-out} for jars,
 *       with symlink-aware deduplication.</li>
 *   <li>Compiler-level extraction from {@code javacopts} attributes in BUILD files.</li>
 * </ul>
 */
public class BazelImporter implements BuildSystemImporter {

    private static final Logger log = LoggerFactory.getLogger(BazelImporter.class);

    // Directories to skip during recursive source scanning.
    private static final List<String> IGNORED_DIRS = List.of(
        ".git", ".svn", ".mvn", ".gradle", ".settings", ".metadata",
        "node_modules", "target", "build", "bin", "out", "dist"
    );

    /**
     * Walk the project tree for every directory containing a {@code BUILD} or
     * {@code BUILD.bazel} file (a Bazel "package"). These are the natural roots from
     * which to probe for {@code src/main/java}-style layouts in multi-target Bazel
     * builds. Bazel output trees ({@code bazel-*}) are skipped.
     */
    public List<Path> getTargetPackages(Path projectPath) {
        List<Path> packages = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(projectPath)) {
            stream.filter(Files::isDirectory)
                  .filter(dir -> !IGNORED_DIRS.contains(dir.getFileName().toString()))
                  .filter(dir -> !isBazelOutputDirectory(projectPath, dir))
                  .filter(dir -> Files.exists(dir.resolve("BUILD"))
                              || Files.exists(dir.resolve("BUILD.bazel")))
                  .forEach(packages::add);
        } catch (IOException e) {
            log.debug("Failed to walk for Bazel packages: {}", e.getMessage());
        }
        return packages;
    }

    /**
     * Fallback for Bazel projects without standard source layout: walk for directories
     * that hold both a BUILD/BUILD.bazel file and .java files directly. Appends each
     * such directory to {@code sourcePaths}.
     */
    public void addFallbackSourcePaths(Path projectPath, List<Path> sourcePaths) {
        try (Stream<Path> stream = Files.walk(projectPath)) {
            stream.filter(Files::isDirectory)
                  .filter(dir -> !IGNORED_DIRS.contains(dir.getFileName().toString()))
                  .filter(dir -> !isBazelOutputDirectory(projectPath, dir))
                  .filter(this::isBazelJavaPackage)
                  .forEach(sourcePaths::add);
        } catch (IOException e) {
            log.warn("Failed to scan Bazel project for source directories: {}", e.getMessage());
        }
        log.debug("Found {} Bazel source directories", sourcePaths.size());
    }

    /**
     * Get dependency JARs from Bazel build output.
     *
     * <p>Bug E fix: {@code bazel-bin} is typically a symlink that resolves into
     * {@code bazel-out/<config>/bin/}. Scanning both naively produces every jar twice.
     * Each scan root is canonicalized via {@link Path#toRealPath}; any root whose
     * canonical path is contained in another root is skipped. Within a single scan,
     * collected paths are keyed by canonical form so hardlinks or nested symlinks
     * dedupe.
     *
     * <p>Emits a {@code BAZEL_NOT_BUILT} warning when no jars are found and a prior
     * pass hasn't already added one (deduplicated against {@code warnings}). Two
     * distinct cases produce the same signal: no {@code bazel-bin}/{@code bazel-out}
     * at all, or the roots exist but are empty (e.g. {@code bazel clean} was just run).
     */
    public List<String> getDependencies(Path projectPath, List<LoadWarning> warnings) {
        LinkedHashSet<Path> canonicalJars = new LinkedHashSet<>();
        List<Path> rootsToScan = canonicalizeAndDedupe(List.of(
            projectPath.resolve("bazel-bin"),
            projectPath.resolve("bazel-out")));
        for (Path root : rootsToScan) {
            scanBazelDirForJars(root, canonicalJars);
        }
        List<String> jars = new ArrayList<>(canonicalJars.size());
        for (Path p : canonicalJars) jars.add(p.toString());
        log.debug("Found {} JARs from Bazel output ({} candidate roots)", jars.size(), rootsToScan.size());

        boolean noScanRoots = rootsToScan.isEmpty();
        boolean rootsButNoJars = !rootsToScan.isEmpty() && jars.isEmpty();
        if ((noScanRoots || rootsButNoJars)
                && warnings.stream().noneMatch(w -> LoadWarning.BAZEL_NOT_BUILT.equals(w.code()))) {
            String detail = noScanRoots
                ? "Neither bazel-bin nor bazel-out exists in " + projectPath
                : "bazel-bin/bazel-out exist in " + projectPath + " but contain no jars (likely bazel clean was just run)";
            warnings.add(new LoadWarning(
                LoadWarning.BAZEL_NOT_BUILT,
                detail + "; classpath will be empty",
                "Run 'bazel build //...' (or the relevant target set) in the project root " +
                    "before loading. JavaLens reads dependency jars from Bazel's build " +
                    "output, not from the source tree."));
        }
        return jars;
    }

    /**
     * Filter dependency jars to those that exist as regular files. Used by the APT
     * cross-cutting scan to discover annotation processors carried on the Bazel build
     * classpath without a per-target declaration we parse.
     */
    public List<Path> getResolvedClasspathJars(Path projectPath, List<LoadWarning> warnings) {
        List<String> raw = getDependencies(projectPath, warnings);
        List<Path> result = new ArrayList<>(raw.size());
        for (String s : raw) {
            Path p = Path.of(s);
            if (Files.isRegularFile(p)) result.add(p);
        }
        return result;
    }

    /**
     * Read Java source level from BUILD/BUILD.bazel files. Bazel exposes javac flags
     * via the {@code javacopts} attribute on {@code java_library} / {@code java_binary}
     * / etc., which is walked for {@code -source}, {@code -target}, {@code --release},
     * and {@code --release=N}-style entries. Returns the highest level found across
     * the workspace (multi-target builds may declare it on every target).
     *
     * <p>Workspace-wide flags via {@code .bazelrc} (e.g. {@code build --javacopt=-source})
     * are not parsed; teams that pin compliance globally typically still set it
     * per-target.
     */
    public String detectCompilerLevel(Path projectPath) {
        Integer best = null;
        try (Stream<Path> stream = Files.walk(projectPath)) {
            for (Path file : (Iterable<Path>) stream
                    .filter(p -> {
                        if (p.getFileName() == null) return false;
                        String n = p.getFileName().toString();
                        return n.equals("BUILD") || n.equals("BUILD.bazel");
                    })
                    .filter(p -> !isBazelOutputDirectory(projectPath, p))::iterator) {
                String content = Files.readString(file);
                Matcher block = Pattern.compile("javacopts\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(content);
                while (block.find()) {
                    Integer level = highestLevelInJavacopts(block.group(1));
                    if (level != null && (best == null || level > best)) best = level;
                }
            }
        } catch (IOException e) {
            log.debug("Failed to walk for Bazel javacopts: {}", e.getMessage());
        }
        return best == null ? null : String.valueOf(best);
    }

    /**
     * Extract the highest numeric Java level declared in a {@code javacopts} list literal.
     * Recognizes {@code "-source", "17"}, {@code "-target", "17"}, {@code "--release", "17"},
     * and {@code "--release=17"} forms.
     */
    private Integer highestLevelInJavacopts(String inner) {
        Integer max = null;
        // -source 17 / -target 17 / --release 17 (paired tokens)
        Matcher paired = Pattern.compile(
            "['\"](?:-source|-target|--release)['\"]\\s*,\\s*['\"](\\d+)['\"]").matcher(inner);
        while (paired.find()) {
            int v = Integer.parseInt(paired.group(1));
            if (max == null || v > max) max = v;
        }
        // --release=17 (single token)
        Matcher inline = Pattern.compile("['\"]--release=(\\d+)['\"]").matcher(inner);
        while (inline.find()) {
            int v = Integer.parseInt(inline.group(1));
            if (max == null || v > max) max = v;
        }
        return max;
    }

    /**
     * Canonicalize each candidate root and drop any whose real path is contained in
     * another root's real path (to avoid double-walking the same physical directory
     * tree). Non-existent roots and roots whose canonicalization fails are skipped.
     * Returns canonical paths so {@link #scanBazelDirForJars} walks real directories
     * — on Linux, {@code bazel-bin}/{@code bazel-out} are real symbolic links and
     * {@code Files.walk} without {@code FOLLOW_LINKS} treats a symlink-to-directory
     * as a non-directory single entry, descending nothing.
     */
    private List<Path> canonicalizeAndDedupe(List<Path> candidates) {
        LinkedHashSet<Path> reals = new LinkedHashSet<>();
        for (Path c : candidates) {
            if (!Files.exists(c)) continue;
            try {
                reals.add(c.toRealPath());
            } catch (IOException e) {
                log.warn("Failed to canonicalize {}: {}", c, e.getMessage());
            }
        }
        List<Path> kept = new ArrayList<>();
        for (Path candidate : reals) {
            boolean contained = false;
            for (Path other : reals) {
                if (candidate.equals(other)) continue;
                if (candidate.startsWith(other)) { contained = true; break; }
            }
            if (!contained) kept.add(candidate);
        }
        return kept;
    }

    private void scanBazelDirForJars(Path dir, Set<Path> canonicalJars) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                  .filter(Files::isRegularFile)
                  .forEach(p -> {
                      try {
                          canonicalJars.add(p.toRealPath());
                      } catch (IOException e) {
                          // Fall back to non-canonical path; dedup via Set semantics still helps.
                          canonicalJars.add(p);
                      }
                  });
        } catch (IOException e) {
            log.warn("Failed to scan {} for JARs: {}", dir, e.getMessage());
        }
    }

    private boolean isBazelOutputDirectory(Path projectRoot, Path dir) {
        if (dir.equals(projectRoot)) {
            return false;
        }
        Path relative = projectRoot.relativize(dir);
        String first = relative.getName(0).toString();
        return first.startsWith("bazel-");
    }

    private boolean isBazelJavaPackage(Path dir) {
        boolean hasBuildFile = Files.exists(dir.resolve("BUILD")) ||
                               Files.exists(dir.resolve("BUILD.bazel"));
        return hasBuildFile && containsJavaFiles(dir);
    }

    private boolean containsJavaFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }
}
