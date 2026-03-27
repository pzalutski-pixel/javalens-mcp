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
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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
        {"src/test/kotlin", "src-test-kotlin"}
    };

    private static final List<String> IGNORED_DIRS = List.of(
        ".git", ".svn", ".mvn", ".gradle", ".settings", ".metadata", ".project", ".classpath",
        "node_modules", "target", "build", "bin", "out", "dist",
        "bazel-bin", "bazel-out", "bazel-testlogs", "bazel-genfiles"
    );

    // Pattern to extract module names from pom.xml
    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>([^<]+)</module>");

    /**
     * Configure an IProject as a Java project with proper classpath.
     */
    public IJavaProject configureJavaProject(IProject project, java.nio.file.Path projectPath,
            org.javalens.core.workspace.WorkspaceManager workspaceManager) throws CoreException {
        IJavaProject javaProject = JavaCore.create(project);

        // Fix for NPE in headless environment: provide default options if preference service is missing
        try {
            javaProject.setOptions(JavaCore.getOptions());
        } catch (Exception e) {
            log.warn("Could not set project options: {}", e.getMessage());
        }

        List<IClasspathEntry> entries = new ArrayList<>();

        // 1. Add JRE container
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
        if (Files.exists(projectPath.resolve("pom.xml"))) return BuildSystem.MAVEN;
        if (Files.exists(projectPath.resolve("build.gradle")) || Files.exists(projectPath.resolve("build.gradle.kts"))) return BuildSystem.GRADLE;
        
        // Flexible Bazel detection
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath, 2)) {
            boolean isBazel = stream.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.equals("WORKSPACE") || name.equals("WORKSPACE.bazel") || name.equals("MODULE.bazel");
            });
            if (isBazel) return BuildSystem.BAZEL;
        } catch (IOException e) { /* log debug */ }
        
        return BuildSystem.UNKNOWN;
    }

    /**
     * Detect if this is a multi-module Maven project.
     */
    public boolean isMultiModuleProject(java.nio.file.Path projectPath) {
        java.nio.file.Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) return false;
        try {
            String content = Files.readString(pomPath);
            return content.contains("<modules>") || content.contains("<packaging>pom</packaging>");
        } catch (IOException e) { return false; }
    }

    /**
     * Get list of module directories for a multi-module project.
     */
    public List<java.nio.file.Path> getModules(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> modules = new ArrayList<>();
        java.nio.file.Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) return modules;

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
        } catch (IOException e) { }
        return modules;
    }

    /**
     * Get all source directories, including from submodules if multi-module project.
     */
    private List<java.nio.file.Path> getAllSourcePaths(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> sourcePaths = new ArrayList<>();
        BuildSystem bs = detectBuildSystem(projectPath);

        // Standard check for root
        addSourcePathsFromDirectory(projectPath, sourcePaths);

        // Recursive scan for Bazel or unknown (Deep Multi-module support)
        if (bs == BuildSystem.BAZEL || bs == BuildSystem.UNKNOWN || sourcePaths.isEmpty()) {
            try {
                Files.walkFileTree(projectPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 15, new SimpleFileVisitor<java.nio.file.Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(java.nio.file.Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName().toString();
                        if (IGNORED_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                        // Avoid redundant scans
                        if (sourcePaths.stream().anyMatch(sp -> dir.startsWith(sp))) return FileVisitResult.SKIP_SUBTREE;
                        
                        addSourcePathsFromDirectory(dir, sourcePaths);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.warn("Recursive source scan failed: {}", e.getMessage());
            }
        }

        return sourcePaths;
    }

    private void addSourcePathsFromDirectory(java.nio.file.Path dir, List<java.nio.file.Path> sourcePaths) {
        for (String[] mapping : SOURCE_MAPPINGS) {
            java.nio.file.Path sourcePath = dir.resolve(mapping[0]);
            if (Files.exists(sourcePath) && Files.isDirectory(sourcePath) && hasJavaFiles(sourcePath)) {
                if (sourcePaths.stream().noneMatch(p -> p.equals(sourcePath))) {
                    sourcePaths.add(sourcePath);
                }
            }
        }
    }

    private void addSourceEntries(List<IClasspathEntry> entries, IProject project,
            java.nio.file.Path projectPath, org.javalens.core.workspace.WorkspaceManager workspaceManager)
            throws CoreException {

        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);
        int folderIndex = 0;

        for (java.nio.file.Path srcPath : sourcePaths) {
            String relativePath = projectPath.relativize(srcPath).toString().replace(File.separator, "-");
            String linkedName = "src-" + folderIndex + "-" + sanitizeFolderName(relativePath);
            folderIndex++;

            try {
                workspaceManager.createLinkedFolder(project, linkedName, srcPath);
                IPath sourceEntryPath = project.getFolder(linkedName).getFullPath();
                entries.add(JavaCore.newSourceEntry(sourceEntryPath));
            } catch (Exception e) {
                log.warn("Failed to create linked folder for {}: {}", srcPath, e.getMessage());
            }
        }
        log.info("Added {} source folders", sourcePaths.size());
    }

    private String sanitizeFolderName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\-_]", "-").replaceAll("-+", "-");
    }

    private void addDependencyEntries(List<IClasspathEntry> entries, java.nio.file.Path projectPath) {
        BuildSystem buildSystem = detectBuildSystem(projectPath);
        List<String> jars = switch (buildSystem) {
            case MAVEN -> getMavenDependencies(projectPath);
            case GRADLE -> getGradleDependencies(projectPath);
            case BAZEL -> getBazelDependencies(projectPath);
            default -> List.of();
        };

        for (String jar : jars) {
            java.nio.file.Path jarPath = java.nio.file.Path.of(jar);
            if (Files.exists(jarPath)) {
                entries.add(JavaCore.newLibraryEntry(new Path(jar), null, null));
            }
        }
        log.info("Added {} dependency entries from {}", jars.size(), buildSystem);
    }

    private List<String> getMavenDependencies(java.nio.file.Path projectPath) {
        List<String> jars = new ArrayList<>();
        java.nio.file.Path cpFile = null;
        try {
            cpFile = Files.createTempFile("javalens-", ".classpath");
            String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(mvnCmd, "dependency:build-classpath", "-Dmdep.outputFile=" + cpFile.toAbsolutePath(), "-q");
            pb.directory(projectPath.toFile());
            Process process = pb.start();
            process.waitFor(120, TimeUnit.SECONDS);
            if (process.exitValue() == 0 && Files.exists(cpFile)) {
                String classpath = Files.readString(cpFile).trim();
                if (!classpath.isEmpty()) {
                    jars.addAll(Arrays.asList(classpath.split(File.pathSeparator)));
                }
            }
        } catch (Exception e) {
            log.error("Maven CP extraction failed", e);
        } finally {
            if (cpFile != null) try { Files.deleteIfExists(cpFile); } catch (IOException e) {}
        }
        return jars;
    }

    private List<String> getGradleDependencies(java.nio.file.Path projectPath) {
        return List.of();
    }

    private List<String> getBazelDependencies(java.nio.file.Path projectPath) {
        List<String> jars = new ArrayList<>();
        scanBazelDirForJars(projectPath.resolve("bazel-bin"), jars);
        scanBazelDirForJars(projectPath.resolve("bazel-out"), jars);
        return jars;
    }

    private void scanBazelDirForJars(java.nio.file.Path dir, List<String> jars) {
        if (!Files.exists(dir)) return;
        try (Stream<java.nio.file.Path> stream = Files.walk(dir, 20, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                  .filter(Files::isRegularFile)
                  .map(java.nio.file.Path::toString)
                  .forEach(jars::add);
        } catch (IOException e) {}
    }

    public int countSourceFiles(java.nio.file.Path projectPath) {
        int count = 0;
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);
        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath, 20, FileVisitOption.FOLLOW_LINKS)) {
                count += (int) stream.filter(p -> p.toString().endsWith(".java")).count();
            } catch (IOException e) {}
        }
        return count;
    }

    public List<String> findPackages(java.nio.file.Path projectPath) {
        List<String> packages = new ArrayList<>();
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);
        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath, 20, FileVisitOption.FOLLOW_LINKS)) {
                stream.filter(Files::isDirectory)
                      .filter(this::hasJavaFiles)
                      .map(p -> srcPath.relativize(p).toString())
                      .map(s -> s.replace(File.separator, "."))
                      .filter(s -> !s.isEmpty())
                      .filter(s -> !packages.contains(s))
                      .forEach(packages::add);
            } catch (IOException e) {}
        }
        return packages;
    }

    private boolean hasJavaFiles(java.nio.file.Path dir) {
        try (Stream<java.nio.file.Path> stream = Files.walk(dir, 10)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) { return false; }
    }
}
