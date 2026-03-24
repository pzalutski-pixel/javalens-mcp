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
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Imports external Java projects (Maven/Gradle/Bazel) into the Eclipse workspace
 * with proper classpath configuration for JDT analysis.
 *
 * Uses linked folders to keep all Eclipse metadata in the workspace,
 * not polluting the user's actual project directory.
 */
public class ProjectImporter {

    private static final Logger log = LoggerFactory.getLogger(ProjectImporter.class);

    public enum BuildSystem { MAVEN, GRADLE, BAZEL, UNKNOWN }

    // Source folder mapping: external relative path -> linked folder name
    private static final String[][] SOURCE_MAPPINGS = {
        {"src/main/java", "src-main-java"},
        {"src/test/java", "src-test-java"},
        {"src/main/kotlin", "src-main-kotlin"},
        {"src/test/kotlin", "src-test-kotlin"},
        {"src", "src"}
    };

    // Directories to ignore during recursive scanning
    private static final List<String> IGNORED_DIRS = List.of(
        ".git", ".svn", ".mvn", ".gradle", ".settings", ".metadata", ".project", ".classpath",
        "node_modules", "target", "build", "bin", "out", "dist",
        "bazel-bin", "bazel-out", "bazel-testlogs", "bazel-genfiles"
    );

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

        // 1. Add JRE/JDK entries
        addJdkEntries(entries);

        // 2. Create linked folders and add source entries
        addSourceEntries(entries, project, projectPath, workspaceManager);
...
    private void addJdkEntries(List<IClasspathEntry> entries) {
        // 1. Try JAVA_HOME from environment
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            javaHome = System.getProperty("java.home");
        }

        if (javaHome != null && !javaHome.isBlank()) {
            java.nio.file.Path jdkPath = java.nio.file.Path.of(javaHome);
            log.info("Detected JDK at: {}", jdkPath);

            // In Java 9+, the modules are in the 'lib/modules' file or 'jmods' directory
            java.nio.file.Path jmods = jdkPath.resolve("jmods");
            if (Files.exists(jmods)) {
                log.info("Adding JDK modules from jmods directory...");
                try (Stream<java.nio.file.Path> stream = Files.list(jmods)) {
                    stream.filter(p -> p.toString().endsWith(".jmod"))
                          .forEach(p -> {
                              IPath eclipsePath = new Path(p.toAbsolutePath().toString());
                              entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
                          });
                    return;
                } catch (IOException e) {
                    log.warn("Error reading jmods: {}", e.getMessage());
                }
            }
            
            // Fallback for some distributions: check lib/modules
            java.nio.file.Path modules = jdkPath.resolve("lib").resolve("modules");
            if (Files.exists(modules)) {
                log.info("Adding JDK modules from lib/modules...");
                entries.add(JavaCore.newLibraryEntry(new Path(modules.toAbsolutePath().toString()), null, null));
                return;
            }
        }

        // Final fallback: Standard JRE container
        log.info("Using standard JRE container fallback");
        IPath jrePath = JavaRuntime.getDefaultJREContainerEntry().getPath();
        entries.add(JavaCore.newContainerEntry(jrePath));
    }

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
     * Checks root and subdirectories (depth 2) to support multi-service repositories.
     */
    public BuildSystem detectBuildSystem(java.nio.file.Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            log.info("Detected MAVEN project at root");
            return BuildSystem.MAVEN;
        }
        if (Files.exists(projectPath.resolve("build.gradle")) || 
            Files.exists(projectPath.resolve("build.gradle.kts"))) {
            log.info("Detected GRADLE project at root");
            return BuildSystem.GRADLE;
        }
            
        // Check for Bazel files at root or in subdirectories (depth 2)
        log.info("Scanning for Bazel workspace files (WORKSPACE, MODULE.bazel, BUILD)...");
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath, 2)) {
            boolean isBazel = stream.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.equals("WORKSPACE") || name.equals("WORKSPACE.bazel") ||
                       name.equals("BUILD") || name.equals("BUILD.bazel") ||
                       name.equals("MODULE.bazel");
            });
            if (isBazel) {
                log.info("Detected BAZEL project structure");
                return BuildSystem.BAZEL;
            }
        } catch (IOException e) {
            log.debug("Error during build system detection: {}", e.getMessage());
        }
        
        return BuildSystem.UNKNOWN;
    }

    /**
     * Detect if this is a multi-module Maven project.
     */
    public boolean isMultiModuleProject(java.nio.file.Path projectPath) {
        if (detectBuildSystem(projectPath) != BuildSystem.MAVEN) {
            return false;
        }
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
     * For Bazel or unknown structures, recursively scan subdirectories.
     */
    private List<java.nio.file.Path> getAllSourcePaths(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> sourcePaths = new ArrayList<>();
        BuildSystem bs = detectBuildSystem(projectPath);

        log.info("Collecting source paths for {} project: {}", bs, projectPath);

        // 1. Check the root project
        addSourcePathsFromDirectory(projectPath, sourcePaths);

        // 2. Handle Maven modules
        if (isMultiModuleProject(projectPath)) {
            for (java.nio.file.Path modulePath : getModules(projectPath)) {
                addSourcePathsFromDirectory(modulePath, sourcePaths);
            }
        }

        // 3. For Bazel or UNKNOWN (like microservices at root), scan recursively (depth 5)
        // This handles cases like: root/microservice1/src/main/java
        if (bs == BuildSystem.BAZEL || bs == BuildSystem.UNKNOWN || sourcePaths.isEmpty()) {
            log.info("Scanning for subproject source folders (depth 5)...");
            try {
                Files.walkFileTree(projectPath, EnumSet.noneOf(FileVisitOption.class), 5, new SimpleFileVisitor<java.nio.file.Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(java.nio.file.Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName().toString();
                        if (IGNORED_DIRS.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        
                        // Avoid walking into already found source paths
                        if (sourcePaths.stream().anyMatch(sp -> dir.startsWith(sp))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        if (!dir.equals(projectPath)) {
                            addSourcePathsFromDirectory(dir, sourcePaths);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.warn("Error scanning for subprojects: {}", e.getMessage());
            }
        }

        log.info("Found total {} source paths", sourcePaths.size());
        for (java.nio.file.Path p : sourcePaths) {
            log.debug("Source path: {}", p);
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

        log.info("Adding dependencies for {} project...", buildSystem);

        List<String> jars = switch (buildSystem) {
            case MAVEN -> getMavenDependencies(projectPath);
            case GRADLE -> getGradleDependencies(projectPath);
            case BAZEL -> getBazelDependencies(projectPath);
            default -> List.of();
        };

        for (String jar : jars) {
            java.nio.file.Path jarPath = java.nio.file.Path.of(jar);
            if (Files.exists(jarPath)) {
                IPath eclipsePath = new Path(jar);
                entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
            }
        }

        // Standard output locations
        addIfExists(entries, projectPath, "target/classes");
        addIfExists(entries, projectPath, "target/test-classes");
        addIfExists(entries, projectPath, "build/classes/java/main");
        addIfExists(entries, projectPath, "build/classes/java/test");

        // Bazel-specific output locations (searching for compiled classes in bazel-bin)
        if (buildSystem == BuildSystem.BAZEL) {
            findBazelOutputDirs(projectPath, entries);
        }

        log.info("Added {} dependency entries (JARs/Classes)", entries.size());
    }

    private List<String> getBazelDependencies(java.nio.file.Path projectPath) {
        List<String> jars = new ArrayList<>();
        
        // 1. Root bazel-bin (fixed for Docker symlinks)
        java.nio.file.Path rootBazelBin = resolveBazelBinPath(projectPath.resolve("bazel-bin"));
        if (rootBazelBin != null && Files.exists(rootBazelBin)) {
            log.info("Root bazel-bin detected at: {}. Using root dependencies.", rootBazelBin);
            collectJarsFromBazelBin(rootBazelBin, jars);
        } else {
            // 2. Microservices strategy
            log.info("No root bazel-bin found. Scanning subdirectories for microservice dependencies...");
            try (Stream<java.nio.file.Path> stream = Files.list(projectPath)) {
                stream.filter(Files::isDirectory)
                      .map(p -> p.resolve("bazel-bin"))
                      .map(this::resolveBazelBinPath)
                      .filter(bin -> bin != null && Files.exists(bin))
                      .forEach(bin -> {
                          log.info("Found bazel-bin in microservice: {}", bin.getParent().getFileName());
                          collectJarsFromBazelBin(bin, jars);
                      });
            } catch (IOException e) {
                log.warn("Error scanning subdirectories for bazel-bin: {}", e.getMessage());
            }
        }
        
        if (jars.size() > 1000) {
            log.warn("Too many JARs ({}) found. Limiting to first 1000.", jars.size());
            return jars.subList(0, 1000);
        }
        
        return jars;
    }

    /**
     * Resolve bazel-bin path, handling broken absolute symlinks from host.
     */
    private java.nio.file.Path resolveBazelBinPath(java.nio.file.Path binPath) {
        if (!Files.exists(binPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }

        try {
            if (Files.isSymbolicLink(binPath)) {
                java.nio.file.Path target = Files.readSymbolicLink(binPath);
                if (Files.exists(binPath)) {
                    return binPath; // Link is fine
                }
                
                // Link is broken (likely absolute host path)
                log.warn("Broken bazel-bin symlink detected: {} -> {}", binPath, target);
                
                // Try to rescue: find '.cache' in the target path and remap to /workspace
                String targetStr = target.toString();
                if (targetStr.contains(".cache")) {
                    String relativeFromCache = targetStr.substring(targetStr.indexOf(".cache"));
                    // Try to find .cache at project root or parent
                    java.nio.file.Path parent = binPath.getParent();
                    while (parent != null) {
                        java.nio.file.Path possibleCache = parent.resolve(relativeFromCache);
                        if (Files.exists(possibleCache)) {
                            log.info("Rescued bazel-bin symlink: mapping to {}", possibleCache);
                            return possibleCache;
                        }
                        parent = parent.getParent();
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Error resolving symlink {}: {}", binPath, e.getMessage());
        }
        
        return Files.exists(binPath) ? binPath : null;
    }

    private void collectJarsFromBazelBin(java.nio.file.Path bazelBin, List<String> jars) {
        try (Stream<java.nio.file.Path> stream = Files.walk(bazelBin, 3, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                  .filter(p -> !p.toString().contains("-src.jar"))
                  .filter(p -> !p.toString().contains("-sources.jar"))
                  .map(p -> p.toAbsolutePath().toString())
                  .forEach(jars::add);
        } catch (IOException e) {
            log.warn("Error collecting JARs from {}: {}", bazelBin, e.getMessage());
        }
    }

    private void findBazelOutputDirs(java.nio.file.Path projectPath, List<IClasspathEntry> entries) {
        // Logique hiérarchique identique pour les dossiers de classes
        java.nio.file.Path rootBazelBin = resolveBazelBinPath(projectPath.resolve("bazel-bin"));
        if (rootBazelBin != null && Files.exists(rootBazelBin)) {
            collectClassesFromBazelBin(rootBazelBin, entries);
        } else {
            try (Stream<java.nio.file.Path> stream = Files.list(projectPath)) {
                stream.filter(Files::isDirectory)
                      .map(p -> p.resolve("bazel-bin"))
                      .map(this::resolveBazelBinPath)
                      .filter(bin -> bin != null && Files.exists(bin))
                      .forEach(bin -> collectClassesFromBazelBin(bin, entries));
            } catch (IOException e) {
                log.debug("Error scanning subdirectories for bazel-bin classes: {}", e.getMessage());
            }
        }
    }

    private void collectClassesFromBazelBin(java.nio.file.Path bazelBin, List<IClasspathEntry> entries) {
        try (Stream<java.nio.file.Path> stream = Files.walk(bazelBin, 5, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> p.getFileName().toString().equals("classes") || p.toString().contains("_javac"))
                  .forEach(p -> {
                      IPath eclipsePath = new Path(p.toAbsolutePath().toString());
                      entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
                      log.debug("Added Bazel class directory: {}", p);
                  });
        } catch (IOException e) {
            log.debug("Error collecting classes from {}: {}", bazelBin, e.getMessage());
        }
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
