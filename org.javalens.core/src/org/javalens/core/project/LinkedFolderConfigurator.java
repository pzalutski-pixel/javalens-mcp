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
import java.util.stream.Stream;

/**
 * Build-system-agnostic source-path discovery and Eclipse linked-folder creation.
 *
 * <p>The orchestrator harvests module / subproject / package roots from each
 * {@link BuildSystemImporter}, then delegates here for two operations:
 * <ol>
 *   <li><b>Source-path discovery</b> via {@link #addSourcePathsFromDirectory}:
 *       checks the standard layouts ({@code src/main/java}, {@code src/test/java},
 *       {@code src/main/kotlin}, {@code src/test/kotlin}, fallback {@code src/})
 *       and the generated-source directories written by annotation processors
 *       (Maven {@code target/generated-sources/*}, Gradle
 *       {@code build/generated/sources/<task>/{main,test}/java}).</li>
 *   <li><b>Linked-folder creation</b> via {@link #addLinkedSourceFolders}: for
 *       each discovered source path, create a uniquely-named linked folder under
 *       the workspace project, and append a source {@link IClasspathEntry}.</li>
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

    /**
     * Add source paths discovered in {@code projectPath} to {@code sourcePaths}:
     * standard layouts first, generated-source dirs second, "src/" fallback if
     * nothing standard matched.
     */
    public void addSourcePathsFromDirectory(Path projectPath, List<Path> sourcePaths) {
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

        // Bug B fix: include generated-source directories. Annotation processors
        // (Lombok, MapStruct, Dagger, JPA Metamodel) write here at build time, and
        // hand-written code references symbols from those generated files. Without
        // these on the classpath as source folders, references are unresolved and
        // many navigation/refactor tools return wrong results.
        addGeneratedSourcePaths(projectPath, sourcePaths);
    }

    /**
     * Create a linked folder per source path in the Eclipse project, with a
     * unique name derived from the source path's location relative to
     * {@code projectRoot}; append a source classpath entry for each. Failures
     * to link a single folder are logged but do not abort the rest of the loop.
     *
     * @param entries classpath entries list to append to (mutated)
     * @param project Eclipse workspace project to host the linked folders
     * @param projectRoot external filesystem root used to compute relative
     *                    paths for linked-folder names (e.g. a multi-module
     *                    submodule's path becomes "submodule-src-main-java")
     * @param sourcePaths absolute source-path roots to link
     * @param workspaceManager creates the linked folders via Eclipse resources API
     * @param multiModule logged for diagnostics only
     */
    public void addLinkedSourceFolders(
            List<IClasspathEntry> entries,
            IProject project,
            Path projectRoot,
            List<Path> sourcePaths,
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
                entries.add(JavaCore.newSourceEntry(sourceEntryPath));
                log.debug("Added linked source folder: {} -> {}", linkedName, srcPath);
            } catch (Exception e) {
                log.warn("Failed to create linked folder for {}: {}", srcPath, e.getMessage());
            }
        }

        log.info("Added {} source folders (multi-module: {})", sourcePaths.size(), multiModule);
    }

    // Maven puts each annotation processor's output under its own subdirectory of
    // target/generated-sources/ (e.g. annotations, jaxws, jpamodelgen, ...). Gradle's
    // layout is build/generated/sources/<task>/{main,test}/java/. We probe one level
    // deep so each processor's directory becomes its own source folder.
    private void addGeneratedSourcePaths(Path projectPath, List<Path> sourcePaths) {
        addImmediateSubdirectories(projectPath.resolve("target").resolve("generated-sources"), sourcePaths);
        addImmediateSubdirectories(projectPath.resolve("target").resolve("generated-test-sources"), sourcePaths);

        Path gradleGenerated = projectPath.resolve("build").resolve("generated").resolve("sources");
        if (Files.isDirectory(gradleGenerated)) {
            try (Stream<Path> tasks = Files.list(gradleGenerated)) {
                tasks.filter(Files::isDirectory).forEach(taskDir -> {
                    Path mainJava = taskDir.resolve("main").resolve("java");
                    Path testJava = taskDir.resolve("test").resolve("java");
                    if (Files.isDirectory(mainJava)) sourcePaths.add(mainJava);
                    if (Files.isDirectory(testJava)) sourcePaths.add(testJava);
                });
            } catch (IOException e) {
                log.debug("Failed to scan Gradle generated sources: {}", e.getMessage());
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
