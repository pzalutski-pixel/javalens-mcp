package org.javalens.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Imports external Java projects (Maven/Gradle) into the Eclipse workspace
 * with proper classpath configuration for JDT analysis.
 *
 * Uses linked folders to keep all Eclipse metadata in the workspace,
 * not polluting the user's actual project directory.
 */
public class ProjectImporter {

    private static final Logger log = LoggerFactory.getLogger(ProjectImporter.class);

    public enum BuildSystem { MAVEN, GRADLE, UNKNOWN }

    // Source folder mapping: external relative path -> linked folder name
    private static final String[][] SOURCE_MAPPINGS = {
        {"src/main/java", "src-main-java"},
        {"src/test/java", "src-test-java"},
        {"src/main/kotlin", "src-main-kotlin"},
        {"src/test/kotlin", "src-test-kotlin"},
        {"src", "src"}
    };

    // Pattern to extract module names from pom.xml
    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>([^<]+)</module>");

    /**
     * Configure an IProject as a Java project with proper classpath.
     * Creates linked folders for source directories to keep Eclipse metadata
     * in the workspace, not polluting the user's project directory.
     *
     * @param project The workspace project (must be created and open)
     * @param projectPath The filesystem path to the external project
     * @param workspaceManager WorkspaceManager for creating linked folders
     * @return Configured IJavaProject
     * @throws CoreException if configuration fails
     */
    public IJavaProject configureJavaProject(IProject project, java.nio.file.Path projectPath,
            org.javalens.core.workspace.WorkspaceManager workspaceManager) throws CoreException {
        IJavaProject javaProject = JavaCore.create(project);

        // Build classpath entries
        List<IClasspathEntry> entries = new ArrayList<>();

        // 1. Add JRE container (provides java.* classes)
        IPath jreContainerPath = JavaRuntime.getDefaultJREContainerEntry().getPath();
        entries.add(JavaCore.newContainerEntry(jreContainerPath));

        // 2. Create linked folders and add source entries
        addSourceEntries(entries, project, projectPath, workspaceManager);

        // 3. Add dependency JARs from build system
        addDependencyEntries(entries, projectPath);

        // 4. Add output location
        IPath outputPath = project.getFullPath().append("bin");

        // Set the classpath
        javaProject.setRawClasspath(
            entries.toArray(new IClasspathEntry[0]),
            outputPath,
            new NullProgressMonitor()
        );

        log.info("Configured Java project with {} classpath entries", entries.size());
        return javaProject;
    }

    /**
     * Detect build system from project structure.
     */
    public BuildSystem detectBuildSystem(java.nio.file.Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        }
        if (Files.exists(projectPath.resolve("build.gradle")) ||
            Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return BuildSystem.GRADLE;
        }
        return BuildSystem.UNKNOWN;
    }

    /**
     * Detect if this is a multi-module Maven project.
     */
    public boolean isMultiModuleProject(java.nio.file.Path projectPath) {
        java.nio.file.Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return false;
        }
        try {
            String content = Files.readString(pomPath);
            return content.contains("<modules>") || content.contains("<packaging>pom</packaging>");
        } catch (IOException e) {
            log.debug("Error reading pom.xml: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get list of module directories for a multi-module project.
     */
    public List<java.nio.file.Path> getModules(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> modules = new ArrayList<>();
        java.nio.file.Path pomPath = projectPath.resolve("pom.xml");

        if (!Files.exists(pomPath)) {
            return modules;
        }

        try {
            String content = Files.readString(pomPath);
            Matcher matcher = MODULE_PATTERN.matcher(content);
            while (matcher.find()) {
                String moduleName = matcher.group(1).trim();
                java.nio.file.Path modulePath = projectPath.resolve(moduleName);
                if (Files.exists(modulePath) && Files.isDirectory(modulePath)) {
                    modules.add(modulePath);
                }
            }
        } catch (IOException e) {
            log.warn("Error reading pom.xml for modules: {}", e.getMessage());
        }

        log.debug("Found {} modules in multi-module project", modules.size());
        return modules;
    }

    /**
     * Get all source directories, including from submodules if multi-module project.
     */
    private List<java.nio.file.Path> getAllSourcePaths(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> sourcePaths = new ArrayList<>();

        // First check the root project
        addSourcePathsFromDirectory(projectPath, sourcePaths);

        // If multi-module, also check each module
        if (isMultiModuleProject(projectPath)) {
            for (java.nio.file.Path modulePath : getModules(projectPath)) {
                addSourcePathsFromDirectory(modulePath, sourcePaths);
            }
        }

        return sourcePaths;
    }

    /**
     * Add source paths from a single project directory.
     */
    private void addSourcePathsFromDirectory(java.nio.file.Path projectPath, List<java.nio.file.Path> sourcePaths) {
        // Check standard layouts
        for (int i = 0; i < SOURCE_MAPPINGS.length - 1; i++) {
            java.nio.file.Path srcPath = projectPath.resolve(SOURCE_MAPPINGS[i][0]);
            if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                sourcePaths.add(srcPath);
            }
        }

        // Only add "src" fallback if no standard layout found for this directory
        boolean foundStandard = sourcePaths.stream()
            .anyMatch(p -> p.startsWith(projectPath));
        if (!foundStandard) {
            java.nio.file.Path srcPath = projectPath.resolve("src");
            if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                sourcePaths.add(srcPath);
            }
        }
    }

    /**
     * Create linked folders for source directories and add them to classpath.
     * Uses linked folders to keep Eclipse metadata in the workspace.
     * Supports multi-module projects by scanning submodules.
     */
    private void addSourceEntries(List<IClasspathEntry> entries, IProject project,
            java.nio.file.Path projectPath, org.javalens.core.workspace.WorkspaceManager workspaceManager)
            throws CoreException {

        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);
        int folderIndex = 0;

        for (java.nio.file.Path srcPath : sourcePaths) {
            // Create unique linked folder name based on relative path
            String relativePath = projectPath.relativize(srcPath).toString().replace(File.separator, "-");
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

        log.info("Added {} source folders (multi-module: {})", sourcePaths.size(), isMultiModuleProject(projectPath));
    }

    /**
     * Sanitize folder name for Eclipse project.
     */
    private String sanitizeFolderName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\-_]", "-").replaceAll("-+", "-");
    }

    private void addDependencyEntries(List<IClasspathEntry> entries, java.nio.file.Path projectPath) {
        BuildSystem buildSystem = detectBuildSystem(projectPath);

        List<String> jars = switch (buildSystem) {
            case MAVEN -> getMavenDependencies(projectPath);
            case GRADLE -> getGradleDependencies(projectPath);
            default -> List.of();
        };

        for (String jar : jars) {
            java.nio.file.Path jarPath = java.nio.file.Path.of(jar);
            if (Files.exists(jarPath)) {
                IPath eclipsePath = new Path(jar);
                entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
            }
        }

        // Add compiled classes directories (Maven)
        addIfExists(entries, projectPath, "target/classes");
        addIfExists(entries, projectPath, "target/test-classes");
        // Add compiled classes directories (Gradle)
        addIfExists(entries, projectPath, "build/classes/java/main");
        addIfExists(entries, projectPath, "build/classes/java/test");

        log.info("Added {} dependency entries from {}", jars.size(), buildSystem);
    }

    private void addIfExists(List<IClasspathEntry> entries, java.nio.file.Path projectPath, String relativePath) {
        java.nio.file.Path fullPath = projectPath.resolve(relativePath);
        if (Files.exists(fullPath) && Files.isDirectory(fullPath)) {
            IPath eclipsePath = new Path(fullPath.toString());
            entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
        }
    }

    private List<String> getMavenDependencies(java.nio.file.Path projectPath) {
        List<String> jars = new ArrayList<>();
        java.nio.file.Path cpFile = null;

        try {
            // Create temp file in system temp directory, not in user's project
            cpFile = Files.createTempFile("javalens-", ".classpath");

            String mvnCmd = isWindows() ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(
                mvnCmd,
                "dependency:build-classpath",
                "-Dmdep.outputFile=" + cpFile.toAbsolutePath(),
                "-q"
            );
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);

            log.info("Running Maven to get classpath...");
            Process process = pb.start();

            // Consume output to prevent blocking
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* discard */ }
            }

            boolean completed = process.waitFor(120, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Maven classpath command timed out");
                return jars;
            }

            if (process.exitValue() == 0 && Files.exists(cpFile)) {
                String classpath = Files.readString(cpFile).trim();

                if (!classpath.isEmpty()) {
                    jars.addAll(Arrays.asList(classpath.split(File.pathSeparator)));
                }
                log.info("Got {} classpath entries from Maven", jars.size());
            } else {
                log.warn("Maven classpath command failed with exit code: {}", process.exitValue());
            }

        } catch (Exception e) {
            log.error("Failed to get Maven classpath", e);
        } finally {
            // Always clean up temp file
            if (cpFile != null) {
                try {
                    Files.deleteIfExists(cpFile);
                } catch (Exception e) {
                    log.trace("Could not delete temp classpath file: {}", e.getMessage());
                }
            }
        }

        return jars;
    }

    private List<String> getGradleDependencies(java.nio.file.Path projectPath) {
        // Gradle classpath extraction relies on build output directories
        // which are added in addDependencyEntries
        return List.of();
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Count Java source files in the project.
     * Supports multi-module projects.
     */
    public int countSourceFiles(java.nio.file.Path projectPath) {
        int count = 0;
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);

        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath)) {
                count += (int) stream.filter(p -> p.toString().endsWith(".java")).count();
            } catch (IOException e) {
                log.warn("Failed to count files in {}", srcPath, e);
            }
        }
        return count;
    }

    /**
     * Find all packages in the project.
     * Supports multi-module projects.
     */
    public List<String> findPackages(java.nio.file.Path projectPath) {
        List<String> packages = new ArrayList<>();
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);

        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath)) {
                stream.filter(Files::isDirectory)
                      .filter(this::containsJavaFiles)
                      .map(p -> srcPath.relativize(p).toString())
                      .map(s -> s.replace(File.separator, "."))
                      .filter(s -> !s.isEmpty())
                      .filter(s -> !packages.contains(s))  // Avoid duplicates
                      .forEach(packages::add);
            } catch (IOException e) {
                log.warn("Failed to find packages in {}", srcPath, e);
            }
        }

        return packages;
    }

    private boolean containsJavaFiles(java.nio.file.Path dir) {
        try (Stream<java.nio.file.Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }
}
