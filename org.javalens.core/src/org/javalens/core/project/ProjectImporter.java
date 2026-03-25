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
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Imports external Java projects (Maven/Gradle/Bazel) into the Eclipse workspace
 * with proper classpath configuration for JDT analysis.
 */
public class ProjectImporter {

    private static final Logger log = LoggerFactory.getLogger(ProjectImporter.class);

    public enum BuildSystem { MAVEN, GRADLE, BAZEL, UNKNOWN }

    private static final String[][] SOURCE_MAPPINGS = {
        {"src/main/java", "src-main-java"},
        {"src/test/java", "src-test-java"},
        {"src/main/kotlin", "src-main-kotlin"},
        {"src/test/kotlin", "src-test-kotlin"},
        {"src", "src"}
    };

    private static final List<String> IGNORED_DIRS = List.of(
        ".git", ".svn", ".mvn", ".gradle", ".settings", ".metadata", ".project", ".classpath",
        "node_modules", "target", "build", "bin", "out", "dist",
        "bazel-bin", "bazel-out", "bazel-testlogs", "bazel-genfiles"
    );

    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>([^<]+)</module>");

    public IJavaProject configureJavaProject(IProject project, java.nio.file.Path projectPath,
            org.javalens.core.workspace.WorkspaceManager workspaceManager) throws CoreException {
        IJavaProject javaProject = JavaCore.create(project);
        
        // Fix for NPE in headless environment: provide default options if preference service is missing
        try {
            javaProject.setOptions(JavaCore.getOptions());
        } catch (Exception e) {
            System.err.println("Warning: Could not set project options, JDT might be unstable: " + e.getMessage());
        }

        List<IClasspathEntry> entries = new ArrayList<>();

        addJdkEntries(entries);
        addSourceEntries(entries, project, projectPath, workspaceManager);
        addDependencyEntries(entries, projectPath);

        IPath outputPath = project.getFullPath().append("bin");
        javaProject.setRawClasspath(entries.toArray(new IClasspathEntry[0]), outputPath, new NullProgressMonitor());

        log.info("Configured Java project with {} classpath entries", entries.size());
        return javaProject;
    }

    private void addJdkEntries(List<IClasspathEntry> entries) {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) javaHome = System.getProperty("java.home");

        if (javaHome != null && !javaHome.isBlank()) {
            java.nio.file.Path jdkPath = java.nio.file.Path.of(javaHome);
            log.info("Detected JDK at: {}", jdkPath);

            java.nio.file.Path jmods = jdkPath.resolve("jmods");
            if (Files.exists(jmods)) {
                log.info("Adding JDK modules from jmods directory...");
                try (Stream<java.nio.file.Path> stream = Files.list(jmods)) {
                    stream.filter(p -> p.toString().endsWith(".jmod"))
                          .forEach(p -> entries.add(JavaCore.newLibraryEntry(new Path(p.toAbsolutePath().toString()), null, null)));
                    return;
                } catch (IOException e) {
                    log.warn("Error reading jmods: {}", e.getMessage());
                }
            }
            
            java.nio.file.Path modules = jdkPath.resolve("lib").resolve("modules");
            if (Files.exists(modules)) {
                log.info("Adding JDK modules from lib/modules...");
                entries.add(JavaCore.newLibraryEntry(new Path(modules.toAbsolutePath().toString()), null, null));
                return;
            }
        }

        log.info("Using standard JRE container fallback");
        entries.add(JavaCore.newContainerEntry(JavaRuntime.getDefaultJREContainerEntry().getPath()));
    }

    public BuildSystem detectBuildSystem(java.nio.file.Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) return BuildSystem.MAVEN;
        if (Files.exists(projectPath.resolve("build.gradle")) || Files.exists(projectPath.resolve("build.gradle.kts"))) return BuildSystem.GRADLE;
            
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath, 2)) {
            boolean isBazel = stream.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.equals("WORKSPACE") || name.equals("WORKSPACE.bazel") ||
                       name.equals("BUILD") || name.equals("BUILD.bazel") ||
                       name.equals("MODULE.bazel");
            });
            if (isBazel) return BuildSystem.BAZEL;
        } catch (IOException e) { /* log debug */ }
        return BuildSystem.UNKNOWN;
    }

    private void addSourceEntries(List<IClasspathEntry> entries, IProject project, java.nio.file.Path projectPath,
            org.javalens.core.workspace.WorkspaceManager workspaceManager) throws CoreException {
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);
        int folderIndex = 0;
        for (java.nio.file.Path sourcePath : sourcePaths) {
            String folderName = "src-" + (folderIndex++) + "-" + sanitizeFolderName(projectPath.relativize(sourcePath).toString());
            workspaceManager.createLinkedFolder(project, folderName, sourcePath);
            entries.add(JavaCore.newSourceEntry(project.getFullPath().append(folderName)));
        }
    }

    private List<java.nio.file.Path> getAllSourcePaths(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> sourcePaths = new ArrayList<>();
        BuildSystem bs = detectBuildSystem(projectPath);
        addSourcePathsFromDirectory(projectPath, sourcePaths);
        
        if (bs == BuildSystem.BAZEL || bs == BuildSystem.UNKNOWN || sourcePaths.isEmpty()) {
            try {
                Files.walkFileTree(projectPath, EnumSet.noneOf(FileVisitOption.class), 5, new SimpleFileVisitor<java.nio.file.Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(java.nio.file.Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName().toString();
                        if (IGNORED_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                        if (sourcePaths.stream().anyMatch(sp -> dir.startsWith(sp))) return FileVisitResult.SKIP_SUBTREE;
                        if (!dir.equals(projectPath)) addSourcePathsFromDirectory(dir, sourcePaths);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) { /* log warn */ }
        }
        return sourcePaths;
    }

    private void addSourcePathsFromDirectory(java.nio.file.Path dir, List<java.nio.file.Path> sourcePaths) {
        for (String[] mapping : SOURCE_MAPPINGS) {
            java.nio.file.Path sourcePath = dir.resolve(mapping[0]);
            if (Files.exists(sourcePath) && Files.isDirectory(sourcePath) && hasJavaFiles(sourcePath)) {
                if (sourcePaths.stream().noneMatch(p -> p.equals(sourcePath))) sourcePaths.add(sourcePath);
            }
        }
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
            if (Files.exists(java.nio.file.Path.of(jar))) entries.add(JavaCore.newLibraryEntry(new Path(jar), null, null));
        }
        if (buildSystem == BuildSystem.BAZEL) findBazelOutputDirs(projectPath, entries);
    }

    private List<String> getBazelDependencies(java.nio.file.Path projectPath) {
        List<String> jars = new ArrayList<>();
        java.nio.file.Path rootBazelBin = resolveBazelBinPath(projectPath.resolve("bazel-bin"));
        if (rootBazelBin != null && Files.exists(rootBazelBin)) {
            collectJarsFromBazelBin(rootBazelBin, jars);
        } else {
            try (Stream<java.nio.file.Path> stream = Files.list(projectPath)) {
                stream.filter(Files::isDirectory)
                      .map(p -> p.resolve("bazel-bin"))
                      .map(this::resolveBazelBinPath)
                      .filter(bin -> bin != null && Files.exists(bin))
                      .forEach(bin -> collectJarsFromBazelBin(bin, jars));
            } catch (IOException e) { /* log warn */ }
        }
        return jars.size() > 1000 ? jars.subList(0, 1000) : jars;
    }

    private java.nio.file.Path resolveBazelBinPath(java.nio.file.Path binPath) {
        if (!Files.exists(binPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (Files.isSymbolicLink(binPath)) {
                java.nio.file.Path target = Files.readSymbolicLink(binPath);
                if (Files.exists(binPath)) return binPath;
                String targetStr = target.toString();
                if (targetStr.contains(".cache")) {
                    String relativeFromCache = targetStr.substring(targetStr.indexOf(".cache"));
                    java.nio.file.Path parent = binPath.getParent();
                    while (parent != null) {
                        java.nio.file.Path possibleCache = parent.resolve(relativeFromCache);
                        if (Files.exists(possibleCache)) return possibleCache;
                        parent = parent.getParent();
                    }
                }
            }
        } catch (IOException e) { /* log debug */ }
        return Files.exists(binPath) ? binPath : null;
    }

    private void collectJarsFromBazelBin(java.nio.file.Path bazelBin, List<String> jars) {
        try (Stream<java.nio.file.Path> stream = Files.walk(bazelBin, 3, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                  .filter(p -> !p.toString().contains("-src.jar") && !p.toString().contains("-sources.jar"))
                  .map(p -> p.toAbsolutePath().toString())
                  .forEach(jars::add);
        } catch (IOException e) { /* log warn */ }
    }

    private void findBazelOutputDirs(java.nio.file.Path projectPath, List<IClasspathEntry> entries) {
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
            } catch (IOException e) { /* log debug */ }
        }
    }

    private void collectClassesFromBazelBin(java.nio.file.Path bazelBin, List<IClasspathEntry> entries) {
        try (Stream<java.nio.file.Path> stream = Files.walk(bazelBin, 5, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> p.getFileName().toString().equals("classes") || p.toString().contains("_javac"))
                  .forEach(p -> entries.add(JavaCore.newLibraryEntry(new Path(p.toAbsolutePath().toString()), null, null)));
        } catch (IOException e) { /* log debug */ }
    }

    private List<String> getMavenDependencies(java.nio.file.Path projectPath) {
        List<String> jars = new ArrayList<>();
        java.nio.file.Path m2Repo = java.nio.file.Path.of(System.getProperty("user.home"), ".m2", "repository");
        if (Files.exists(m2Repo)) {
            try (Stream<java.nio.file.Path> stream = Files.walk(m2Repo, 10)) {
                stream.filter(p -> p.toString().endsWith(".jar")).map(p -> p.toAbsolutePath().toString()).forEach(jars::add);
            } catch (IOException e) { /* log warn */ }
        }
        return jars;
    }

    private List<String> getGradleDependencies(java.nio.file.Path projectPath) {
        List<String> jars = new ArrayList<>();
        java.nio.file.Path gradleCache = java.nio.file.Path.of(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1");
        if (Files.exists(gradleCache)) {
            try (Stream<java.nio.file.Path> stream = Files.walk(gradleCache, 10)) {
                stream.filter(p -> p.toString().endsWith(".jar")).map(p -> p.toAbsolutePath().toString()).forEach(jars::add);
            } catch (IOException e) { /* log warn */ }
        }
        return jars;
    }

    public int countSourceFiles(java.nio.file.Path projectPath) {
        System.err.println("Counting source files in: " + projectPath);
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath, 20, FileVisitOption.FOLLOW_LINKS)) {
            return (int) stream.filter(p -> p.toString().endsWith(".java"))
                               .peek(p -> System.err.println("Found java file: " + p))
                               .count();
        } catch (IOException e) { 
            System.err.println("Error counting source files: " + e.getMessage());
            return 0; 
        }
    }

    public List<String> findPackages(java.nio.file.Path projectPath) {
        List<String> packages = new ArrayList<>();
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath, 20, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .map(this::extractPackage)
                  .filter(p -> p != null && !p.isEmpty()).distinct().forEach(packages::add);
        } catch (IOException e) { /* log warn */ }
        return packages;
    }

    private String extractPackage(java.nio.file.Path javaFile) {
        try (BufferedReader reader = Files.newBufferedReader(javaFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("package ")) return line.substring(8, line.indexOf(';')).trim();
                if (line.startsWith("import ") || line.startsWith("public ") || line.startsWith("class ")) break;
            }
        } catch (IOException e) { }
        return null;
    }

    private boolean hasJavaFiles(java.nio.file.Path dir) {
        try (Stream<java.nio.file.Path> stream = Files.walk(dir, 10)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) { return false; }
    }
}
