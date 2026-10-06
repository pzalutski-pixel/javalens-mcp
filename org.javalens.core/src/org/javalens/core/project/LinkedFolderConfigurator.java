package org.javalens.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.javalens.core.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Build-system-agnostic source-path discovery and Eclipse linked-folder creation.
 *
 * <p>Two operations:
 * <ol>
 *   <li><b>Source-path discovery</b> via {@link #addSourcePathsFromDirectory}, used
 *       per module directory when an importer resolves its model:
 *       checks the standard layouts ({@code src/main/java}, {@code src/test/java},
 *       {@code src/main/kotlin}, {@code src/test/kotlin}, fallback {@code src/})
 *       and the generated-source directories written by annotation processors
 *       (Maven {@code target/generated-sources/*}, Gradle
 *       {@code build/generated/sources/<task>/java/{main,test}} and
 *       {@code build/generated/sources/<task>/{main,test}/java}). The conventional
 *       compiled-output directories are probed by {@link #addConventionalOutputDirs}.</li>
 *   <li><b>Linked-folder creation</b> via {@link #addLinkedSourceFolders}, used by
 *       the orchestrator: for each source root of the model, create a uniquely-named
 *       linked folder under the workspace project, and append a source
 *       {@link IClasspathEntry}.</li>
 * </ol>
 *
 * <p>Linked folders keep Eclipse metadata inside the workspace, not in the user's
 * project directory.
 */
public class LinkedFolderConfigurator {

    private static final Logger log = LoggerFactory.getLogger(LinkedFolderConfigurator.class);

    // External relative path -> default linked folder name. The last entry
    // ("src" -> "src") is a fallback only used when no other layout matched.
    private static final String[][] SOURCE_MAPPINGS = {
        {"src/main/java", "src-main-java"},
        {"src/test/java", "src-test-java"},
        {"src/main/kotlin", "src-main-kotlin"},
        {"src/test/kotlin", "src-test-kotlin"},
        {"src", "src"}
    };

    // Generated-source directories probed in each task directory under
    // build/generated/sources/, in the order they are added: the <task>/{main,test}/java
    // layout, then Gradle's own <task>/java/{main,test}.
    private static final List<String> GENERATED_TASK_LEAVES = List.of(
        "main/java", "test/java", "java/main", "java/test");

    // Compiled-output directories of the conventional Maven and Gradle Java layouts, in
    // the order they are reported.
    private static final List<String> CONVENTIONAL_OUTPUT_DIRS = List.of(
        "target/classes", "target/test-classes", "build/classes/java/main", "build/classes/java/test");

    /**
     * Add source paths discovered in {@code projectPath} to {@code sourcePaths}:
     * standard layouts first, generated-source dirs second, "src/" fallback if
     * nothing standard matched.
     */
    public void addSourcePathsFromDirectory(Path projectPath, List<Path> sourcePaths) {
        addConventionalSourcePaths(projectPath, sourcePaths);

        // Bug B fix: include generated-source directories. Annotation processors
        // (Lombok, MapStruct, Dagger, JPA Metamodel) write here at build time, and
        // hand-written code references symbols from those generated files. Without
        // these on the classpath as source folders, references are unresolved and
        // many navigation/refactor tools return wrong results.
        addGeneratedSourcePaths(projectPath, sourcePaths);
    }

    /**
     * Add the hand-written source layouts found in {@code projectPath}: standard
     * layouts first, then the "src/" fallback when no path already in
     * {@code sourcePaths} lies under {@code projectPath}.
     */
    void addConventionalSourcePaths(Path projectPath, List<Path> sourcePaths) {
        // Check standard layouts.
        for (int i = 0; i < SOURCE_MAPPINGS.length - 1; i++) {
            Path srcPath = projectPath.resolve(SOURCE_MAPPINGS[i][0]);
            if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                sourcePaths.add(srcPath);
            }
        }

        // Only add "src" fallback if no standard layout matched for THIS directory.
        boolean foundStandard = sourcePaths.stream()
            .anyMatch(p -> p.startsWith(projectPath));
        if (!foundStandard) {
            Path srcPath = projectPath.resolve("src");
            if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                sourcePaths.add(srcPath);
            }
        }
    }

    /**
     * Create a linked folder per source path in the Eclipse project, with a
     * unique name derived from the source path's location relative to
     * {@code projectRoot}; append a source classpath entry for each, excluding the
     * source path's {@code exclusionPatterns}. Failures to link a single folder are
     * logged but do not abort the rest of the loop.
     *
     * @param entries classpath entries list to append to (mutated)
     * @param project Eclipse workspace project to host the linked folders
     * @param projectRoot external filesystem root used to compute relative
     *                    paths for linked-folder names (e.g. a multi-module
     *                    submodule's path becomes "submodule-src-main-java")
     * @param sourcePaths absolute source-path roots to link
     * @param exclusionPatterns for a source path, the paths its source entry excludes,
     *                    relative to it with '/' separators, each ending in '/'; a source
     *                    path missing from the map excludes nothing
     * @param workspaceManager creates the linked folders via Eclipse resources API
     * @param multiModule logged for diagnostics only
     */
    public void addLinkedSourceFolders(
            List<IClasspathEntry> entries,
            IProject project,
            Path projectRoot,
            List<Path> sourcePaths,
            Map<Path, List<String>> exclusionPatterns,
            WorkspaceManager workspaceManager,
            boolean multiModule) throws CoreException {

        int folderIndex = 0;

        for (Path srcPath : sourcePaths) {
            // Linked folder name: unique per source path under the project root.
            // Format: "src-{index}-{sanitized-relative-path}".
            String relativePath = projectRoot.relativize(srcPath).toString()
                .replace(File.separator, "-");
            String linkedName = "src-" + folderIndex + "-" + sanitizeFolderName(relativePath);
            folderIndex++;

            try {
                workspaceManager.createLinkedFolder(project, linkedName, srcPath);
                IPath sourceEntryPath = project.getFolder(linkedName).getFullPath();
                IPath[] exclusions = exclusionPatterns.getOrDefault(srcPath, List.of()).stream()
                    .map(org.eclipse.core.runtime.Path::new)
                    .toArray(IPath[]::new);
                entries.add(JavaCore.newSourceEntry(sourceEntryPath, exclusions));
                log.debug("Added linked source folder: {} -> {}", linkedName, srcPath);
            } catch (Exception e) {
                log.warn("Failed to create linked folder for {}: {}", srcPath, e.getMessage());
            }
        }

        log.info("Added {} source folders (multi-module: {})", sourcePaths.size(), multiModule);
    }

    /**
     * Add the generated-source directories found in {@code projectPath}. Maven puts each
     * annotation processor's output under its own subdirectory of
     * {@code target/generated-sources/} (e.g. annotations, jaxws, jpamodelgen, ...), and of
     * {@code target/generated-test-sources/} for tests; each such subdirectory is added.
     * Under {@code build/generated/sources/}, each task's directory is probed for
     * {@code <task>/main/java} and {@code <task>/test/java}, then for
     * {@code <task>/java/main} and {@code <task>/java/test}, the layout Gradle's
     * annotation-processor output uses; each that exists is added.
     */
    void addGeneratedSourcePaths(Path projectPath, List<Path> sourcePaths) {
        addImmediateSubdirectories(projectPath.resolve("target").resolve("generated-sources"), sourcePaths);
        addImmediateSubdirectories(projectPath.resolve("target").resolve("generated-test-sources"), sourcePaths);

        Path gradleGenerated = projectPath.resolve("build").resolve("generated").resolve("sources");
        if (Files.isDirectory(gradleGenerated)) {
            try (Stream<Path> tasks = Files.list(gradleGenerated)) {
                tasks.filter(Files::isDirectory).forEach(taskDir -> {
                    for (String leaf : GENERATED_TASK_LEAVES) {
                        Path generated = taskDir.resolve(leaf);
                        if (Files.isDirectory(generated)) sourcePaths.add(generated);
                    }
                });
            } catch (IOException e) {
                log.debug("Failed to scan Gradle generated sources: {}", e.getMessage());
            }
        }
    }

    /**
     * Add the conventional compiled-output directories that exist in {@code dir}, in
     * {@link #CONVENTIONAL_OUTPUT_DIRS} order: Maven's {@code target/classes} and
     * {@code target/test-classes}, then Gradle's {@code build/classes/java/main} and
     * {@code build/classes/java/test}.
     */
    void addConventionalOutputDirs(Path dir, List<Path> outputDirs) {
        for (String candidate : CONVENTIONAL_OUTPUT_DIRS) {
            Path outputDir = dir.resolve(candidate);
            if (Files.isDirectory(outputDir)) {
                outputDirs.add(outputDir);
            }
        }
    }

    private void addImmediateSubdirectories(Path parent, List<Path> sourcePaths) {
        if (!Files.isDirectory(parent)) return;
        try (Stream<Path> children = Files.list(parent)) {
            children.filter(Files::isDirectory).forEach(sourcePaths::add);
        } catch (IOException e) {
            log.debug("Failed to list {}: {}", parent, e.getMessage());
        }
    }

    private String sanitizeFolderName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\-_]", "-").replaceAll("-+", "-");
    }

}
