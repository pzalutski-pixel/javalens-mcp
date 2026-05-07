package org.javalens.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.apt.core.util.AptConfig;
import org.eclipse.jdt.apt.core.util.IFactoryPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.javalens.core.project.model.LoadWarning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    /**
     * Warnings accumulated during the most recent {@link #configureJavaProject} call.
     * Reset at the start of each invocation. {@link JdtServiceImpl#getWarnings()} reads
     * this list to surface degraded-load scenarios in the {@code load_project} response.
     */
    private final List<LoadWarning> warnings = new ArrayList<>();

    /**
     * Returns the warnings from the most recent project import.
     */
    public List<LoadWarning> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    // Source folder mapping: external relative path -> linked folder name
    private static final String[][] SOURCE_MAPPINGS = {
        {"src/main/java", "src-main-java"},
        {"src/test/java", "src-test-java"},
        {"src/main/kotlin", "src-main-kotlin"},
        {"src/test/kotlin", "src-test-kotlin"},
        {"src", "src"}
    };

    // Directories to skip during recursive source scanning
    private static final List<String> IGNORED_DIRS = List.of(
        ".git", ".svn", ".mvn", ".gradle", ".settings", ".metadata",
        "node_modules", "target", "build", "bin", "out", "dist"
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
        // Reset warnings from any previous load before starting fresh accumulation.
        warnings.clear();

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

        // Bug G fix: apply the project's declared compiler source level. Without this, JDT
        // falls back to defaults that may be older than the source code, causing legitimate
        // language features (e.g. Java 21 record patterns) to be reported as syntax errors.
        BuildSystem buildSystem = detectBuildSystem(projectPath);
        applyCompilerOptions(javaProject, projectPath, buildSystem);

        // Bug H fix: enable annotation processing and register the processor jars declared
        // by the project. Without this, code that references annotation-processor-generated
        // members (Lombok @Data getters, MapStruct mappers, JPA metamodels) shows those
        // members as unresolved during analysis.
        applyAnnotationProcessing(javaProject, projectPath, buildSystem);

        log.info("Configured Java project with {} classpath entries", entries.size());
        return javaProject;
    }

    /**
     * Enable JDT's APT framework on the project and register declared annotation-processor
     * jars on its factory path. We resolve {@code <annotationProcessorPaths>} entries from
     * Maven's pom.xml against {@code ~/.m2/repository}; Gradle/Bazel processor wiring lands
     * with their respective classpath fixes.
     */
    private void applyAnnotationProcessing(IJavaProject javaProject, java.nio.file.Path projectPath, BuildSystem buildSystem) {
        if (buildSystem != BuildSystem.MAVEN) return;

        List<java.nio.file.Path> processorJars = detectMavenAnnotationProcessors(projectPath);
        if (processorJars.isEmpty()) return;

        try {
            AptConfig.setEnabled(javaProject, true);
            IFactoryPath factoryPath = AptConfig.getDefaultFactoryPath(javaProject);
            int registered = 0;
            for (java.nio.file.Path jar : processorJars) {
                if (Files.isRegularFile(jar)) {
                    factoryPath.addExternalJar(jar.toFile());
                    registered++;
                }
            }
            AptConfig.setFactoryPath(javaProject, factoryPath);
            log.info("Enabled APT with {} processor jar(s)", registered);
        } catch (CoreException e) {
            log.warn("Failed to configure APT: {}", e.getMessage());
        }
    }

    /**
     * Parse {@code <annotationProcessorPaths>} blocks from {@code maven-compiler-plugin}
     * configuration and resolve each {@code <path>} to the corresponding jar in the local
     * Maven repository. Property references in {@code <version>} (e.g. {@code ${lombok.version}})
     * are resolved against pom {@code <properties>}.
     */
    private List<java.nio.file.Path> detectMavenAnnotationProcessors(java.nio.file.Path projectPath) {
        java.nio.file.Path pom = projectPath.resolve("pom.xml");
        if (!Files.exists(pom)) return List.of();

        List<java.nio.file.Path> jars = new ArrayList<>();
        try {
            String content = Files.readString(pom);
            Matcher block = Pattern.compile(
                "<annotationProcessorPaths>(.*?)</annotationProcessorPaths>",
                Pattern.DOTALL).matcher(content);
            while (block.find()) {
                String inner = block.group(1);
                Matcher pathBlock = Pattern.compile("<path>(.*?)</path>", Pattern.DOTALL).matcher(inner);
                while (pathBlock.find()) {
                    String pb = pathBlock.group(1);
                    String group = extractXmlText(pb, "groupId");
                    String artifact = extractXmlText(pb, "artifactId");
                    String version = extractXmlText(pb, "version");
                    if (group != null && artifact != null && version != null) {
                        version = resolvePomProperty(content, version);
                        java.nio.file.Path jar = mavenRepoJarPath(group, artifact, version);
                        if (Files.isRegularFile(jar)) {
                            jars.add(jar);
                        } else {
                            log.warn("Annotation processor jar missing in local repo: {} " +
                                "(run 'mvn dependency:resolve' to download)", jar);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Failed to parse annotationProcessorPaths from pom: {}", e.getMessage());
        }
        return jars;
    }

    /**
     * Resolve a Maven property reference like {@code ${lombok.version}} against the pom's
     * own {@code <properties>}. Returns the input unchanged if it isn't a {@code ${...}}
     * placeholder or the property isn't declared.
     */
    private String resolvePomProperty(String pomContent, String value) {
        if (!value.startsWith("${") || !value.endsWith("}")) return value;
        String name = value.substring(2, value.length() - 1);
        String resolved = extractXmlText(pomContent, name);
        return resolved != null ? resolved : value;
    }

    private java.nio.file.Path mavenRepoJarPath(String groupId, String artifactId, String version) {
        String repo = System.getProperty("user.home") + "/.m2/repository";
        String groupPath = groupId.replace('.', '/');
        return java.nio.file.Path.of(repo, groupPath, artifactId, version,
            artifactId + "-" + version + ".jar");
    }

    /**
     * Read the project's declared Java source level from build metadata and apply it to
     * the {@link IJavaProject}. Sets {@code COMPILER_SOURCE}, {@code COMPILER_COMPLIANCE},
     * and {@code COMPILER_CODEGEN_TARGET_PLATFORM} so the JDT compiler parses and validates
     * code at the same level the build system uses.
     */
    private void applyCompilerOptions(IJavaProject javaProject, java.nio.file.Path projectPath, BuildSystem buildSystem) {
        String level = switch (buildSystem) {
            case MAVEN -> detectMavenCompilerLevel(projectPath);
            // Gradle/Bazel detection is wired up in their respective bug fixes.
            case GRADLE, BAZEL, UNKNOWN -> null;
        };
        if (level == null) {
            warnings.add(new LoadWarning(
                LoadWarning.COMPLIANCE_LEVEL_UNKNOWN,
                "Could not determine Java source level from project metadata; JDT defaults will apply",
                "Declare maven.compiler.source/release in pom.xml (or sourceCompatibility/toolchain in Gradle) so language-level features parse correctly"));
            return;
        }
        javaProject.setOption(JavaCore.COMPILER_SOURCE, level);
        javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, level);
        javaProject.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, level);
        log.info("Applied Java source level {} from build metadata", level);
    }

    /**
     * Extract Maven's declared compiler level from {@code <properties>} in pom.xml.
     * Tries {@code maven.compiler.release} first (the most precise), then
     * {@code maven.compiler.source}, then {@code maven.compiler.target}.
     * Returns {@code null} if none are declared.
     */
    private String detectMavenCompilerLevel(java.nio.file.Path projectPath) {
        java.nio.file.Path pom = projectPath.resolve("pom.xml");
        if (!Files.exists(pom)) return null;
        try {
            String content = Files.readString(pom);
            for (String key : new String[]{"maven.compiler.release", "maven.compiler.source", "maven.compiler.target"}) {
                String value = extractXmlText(content, key);
                if (value != null) return value;
            }
        } catch (IOException e) {
            log.debug("Failed to read pom.xml for compiler level: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract the trimmed text content of the first occurrence of {@code <tag>...</tag>}.
     * Returns {@code null} if the tag is absent or empty.
     */
    private static String extractXmlText(String xml, String tag) {
        Pattern p = Pattern.compile("<" + Pattern.quote(tag) + ">\\s*([^<]+?)\\s*</" + Pattern.quote(tag) + ">");
        Matcher m = p.matcher(xml);
        if (!m.find()) return null;
        String value = m.group(1).trim();
        return value.isEmpty() ? null : value;
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
        // Bazel: check root-level workspace markers (not BUILD files, which are per-package)
        if (Files.exists(projectPath.resolve("MODULE.bazel")) ||
            Files.exists(projectPath.resolve("WORKSPACE.bazel")) ||
            Files.exists(projectPath.resolve("WORKSPACE"))) {
            return BuildSystem.BAZEL;
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

        // For Bazel projects without standard source layout, scan for Java source directories
        if (sourcePaths.isEmpty() && detectBuildSystem(projectPath) == BuildSystem.BAZEL) {
            addBazelSourcePaths(projectPath, sourcePaths);
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

        // Bug B fix: include generated-source directories. Annotation processors
        // (Lombok, MapStruct, Dagger, JPA Metamodel) write here at build time, and the
        // hand-written code references symbols from those generated files. Without these
        // on the classpath as source folders, references are unresolved and many
        // navigation / refactor tools return wrong results.
        addGeneratedSourcePaths(projectPath, sourcePaths);
    }

    // Maven puts each annotation processor's output under its own subdirectory of
    // target/generated-sources/ (e.g. annotations, jaxws, jpamodelgen, ...). Gradle's
    // layout is build/generated/sources/<task>/{main,test}/java/. We probe one level
    // deep so each processor's directory becomes its own source folder.
    private void addGeneratedSourcePaths(java.nio.file.Path projectPath, List<java.nio.file.Path> sourcePaths) {
        addImmediateSubdirectories(projectPath.resolve("target").resolve("generated-sources"), sourcePaths);
        addImmediateSubdirectories(projectPath.resolve("target").resolve("generated-test-sources"), sourcePaths);

        java.nio.file.Path gradleGenerated = projectPath.resolve("build").resolve("generated").resolve("sources");
        if (Files.isDirectory(gradleGenerated)) {
            try (Stream<java.nio.file.Path> tasks = Files.list(gradleGenerated)) {
                tasks.filter(Files::isDirectory).forEach(taskDir -> {
                    java.nio.file.Path mainJava = taskDir.resolve("main").resolve("java");
                    java.nio.file.Path testJava = taskDir.resolve("test").resolve("java");
                    if (Files.isDirectory(mainJava)) sourcePaths.add(mainJava);
                    if (Files.isDirectory(testJava)) sourcePaths.add(testJava);
                });
            } catch (IOException e) {
                log.debug("Failed to scan Gradle generated sources: {}", e.getMessage());
            }
        }
    }

    private void addImmediateSubdirectories(java.nio.file.Path parent, List<java.nio.file.Path> sourcePaths) {
        if (!Files.isDirectory(parent)) return;
        try (Stream<java.nio.file.Path> children = Files.list(parent)) {
            children.filter(Files::isDirectory)
                    .forEach(sourcePaths::add);
        } catch (IOException e) {
            log.debug("Failed to list {}: {}", parent, e.getMessage());
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

    /**
     * Per-module classpath file path written by {@code dependency:build-classpath}.
     *
     * <p>Bug C: passing this as a *relative* path (rather than absolute) makes each reactor
     * child write to its own {@code <module>/target/javalens-classpath.txt}; an absolute
     * path causes every child to overwrite the same file so only the last child's classpath
     * survives. The {@code target/} prefix is necessary because the dependency-plugin
     * resolves relative {@code mdep.outputFile} against the module's project base directory,
     * not its build directory.
     */
    private static final String MAVEN_CP_FILENAME = "javalens-classpath.txt";
    private static final String MAVEN_CP_RELATIVE_PATH = "target/" + MAVEN_CP_FILENAME;

    private List<String> getMavenDependencies(java.nio.file.Path projectPath) {
        java.util.LinkedHashSet<String> jars = new java.util.LinkedHashSet<>();
        StringBuilder capturedOutput = new StringBuilder();

        try {
            // The system property override lets test runs (and pinned-Maven setups) point at
            // a specific Maven binary without touching the process PATH. Production callers
            // don't set this, so the default lookup is unchanged.
            String mvnCmd = System.getProperty("javalens.maven.binary",
                isWindows() ? "mvn.cmd" : "mvn");
            ProcessBuilder pb = new ProcessBuilder(
                mvnCmd,
                "dependency:build-classpath",
                "-Dmdep.outputFile=" + MAVEN_CP_RELATIVE_PATH,
                "-Dmdep.regenerateFile=true",
                "-q"
            );
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);

            log.info("Running Maven to get classpath...");
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                // Most common cause: mvn (or mvn.cmd) not on PATH. Surface it explicitly.
                log.warn("Cannot start Maven subprocess: {}", e.getMessage());
                warnings.add(new LoadWarning(
                    LoadWarning.MAVEN_SUBPROCESS_FAILED,
                    "Could not start '" + mvnCmd + "': " + e.getMessage(),
                    "Install Maven and ensure '" + mvnCmd + "' is on PATH for the process that " +
                        "launches JavaLens. Project dependencies will be unresolved until this is fixed."));
                return new ArrayList<>(jars);
            }

            // Consume output to prevent blocking. Capture lines so we can include them in
            // the warning message when mvn fails — silent failures were the original bug.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (capturedOutput.length() < 4096) {
                        capturedOutput.append(line).append('\n');
                    }
                }
            }

            boolean completed = process.waitFor(120, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Maven classpath command timed out");
                warnings.add(new LoadWarning(
                    LoadWarning.MAVEN_SUBPROCESS_TIMEOUT,
                    "Maven dependency:build-classpath did not finish within 120 seconds",
                    "Check that the project's pom.xml resolves correctly. Try running " +
                        "'mvn dependency:build-classpath' manually in " + projectPath));
                return new ArrayList<>(jars);
            }

            if (process.exitValue() == 0) {
                aggregateClasspathFiles(projectPath, jars);
                log.info("Got {} classpath entries from Maven", jars.size());
            } else {
                int exitCode = process.exitValue();
                log.warn("Maven classpath command failed with exit code: {}", exitCode);
                String snippet = trimToLastLines(capturedOutput.toString(), 5);
                warnings.add(new LoadWarning(
                    LoadWarning.MAVEN_SUBPROCESS_FAILED,
                    "mvn dependency:build-classpath exited with code " + exitCode +
                        (snippet.isEmpty() ? "" : ". Last output: " + snippet),
                    "Run 'mvn dependency:build-classpath' manually in " + projectPath +
                        " to see the full error."));
            }

        } catch (Exception e) {
            log.error("Failed to get Maven classpath", e);
            warnings.add(new LoadWarning(
                LoadWarning.MAVEN_SUBPROCESS_FAILED,
                "Maven invocation threw an unexpected error: " + e.getClass().getSimpleName() +
                    ": " + e.getMessage(),
                "Run 'mvn dependency:build-classpath' manually in " + projectPath +
                    " to reproduce."));
        } finally {
            cleanupClasspathFiles(projectPath);
        }

        return new ArrayList<>(jars);
    }

    /**
     * Walk the project tree for {@code <module>/target/javalens-classpath.txt} files written
     * by {@code dependency:build-classpath} and union their contents into {@code jars}. The
     * caller passes a {@link java.util.LinkedHashSet} so duplicates across modules collapse
     * while preserving discovery order.
     */
    private void aggregateClasspathFiles(java.nio.file.Path projectPath, java.util.Set<String> jars) {
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath)) {
            stream.filter(p -> p.getFileName() != null
                        && MAVEN_CP_FILENAME.equals(p.getFileName().toString()))
                  .filter(p -> p.getParent() != null
                        && p.getParent().getFileName() != null
                        && "target".equals(p.getParent().getFileName().toString()))
                  .forEach(cpFile -> {
                      try {
                          String content = Files.readString(cpFile).trim();
                          if (!content.isEmpty()) {
                              for (String entry : content.split(File.pathSeparator)) {
                                  if (!entry.isBlank()) jars.add(entry);
                              }
                          }
                      } catch (IOException e) {
                          log.warn("Could not read classpath file {}: {}", cpFile, e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.warn("Failed to walk {} for classpath files: {}", projectPath, e.getMessage());
        }
    }

    /**
     * Remove every {@code <module>/target/javalens-classpath.txt} we may have written so we
     * don't leave artifacts behind in the user's project.
     */
    private void cleanupClasspathFiles(java.nio.file.Path projectPath) {
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath)) {
            stream.filter(p -> p.getFileName() != null
                        && MAVEN_CP_FILENAME.equals(p.getFileName().toString()))
                  .filter(p -> p.getParent() != null
                        && p.getParent().getFileName() != null
                        && "target".equals(p.getParent().getFileName().toString()))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); }
                      catch (IOException e) { log.trace("Could not delete {}: {}", p, e.getMessage()); }
                  });
        } catch (IOException e) {
            log.trace("Cleanup walk failed for {}: {}", projectPath, e.getMessage());
        }
    }

    /** Returns the last {@code maxLines} of {@code text}, joined with spaces, trimmed. */
    private static String trimToLastLines(String text, int maxLines) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * Init script that registers a {@code javalensWriteClasspath} task in every Java
     * subproject. The task collects {@code testRuntimeClasspath} (preferred — includes
     * compile + main runtime + test) and writes it to {@code build/javalens-classpath.txt}.
     */
    private static final String GRADLE_INIT_SCRIPT = """
        allprojects { proj ->
            proj.afterEvaluate {
                if (proj.plugins.hasPlugin('java-base')) {
                    proj.tasks.register('javalensWriteClasspath') {
                        doLast {
                            def out = new File(proj.buildDir, 'javalens-classpath.txt')
                            out.parentFile.mkdirs()
                            def cps = [] as Set
                            def cfg = proj.configurations.findByName('testRuntimeClasspath') ?:
                                      proj.configurations.findByName('runtimeClasspath') ?:
                                      proj.configurations.findByName('compileClasspath')
                            cfg?.resolve()?.each { cps << it.absolutePath }
                            out.text = cps.join(System.getProperty('path.separator'))
                        }
                    }
                }
            }
        }
        """;

    private static final String GRADLE_CP_FILENAME = "javalens-classpath.txt";

    /**
     * Bug D fix: the original implementation was a stub returning {@code List.of()}, leaving
     * Gradle projects with empty classpaths. We now ship a Gradle init script that registers a
     * {@code javalensWriteClasspath} task in every subproject and run it via the project's
     * Gradle Wrapper (preferred) or {@code gradle} on PATH. Per-subproject classpath files
     * land at {@code <subproject>/build/javalens-classpath.txt}; we walk the tree and union
     * them.
     */
    private List<String> getGradleDependencies(java.nio.file.Path projectPath) {
        java.util.LinkedHashSet<String> jars = new java.util.LinkedHashSet<>();

        String gradleBinary = resolveGradleBinary(projectPath);
        if (gradleBinary == null) {
            warnings.add(new LoadWarning(
                LoadWarning.GRADLE_SUBPROCESS_FAILED,
                "No Gradle binary available: neither the project's Gradle Wrapper nor a 'gradle' on PATH",
                "Commit a Gradle Wrapper (./gradlew) into the project, or install Gradle and ensure 'gradle' is on PATH for the process that launches JavaLens."));
            return new ArrayList<>(jars);
        }

        java.nio.file.Path initScript = null;
        StringBuilder capturedOutput = new StringBuilder();
        try {
            initScript = Files.createTempFile("javalens-gradle-init-", ".gradle");
            Files.writeString(initScript, GRADLE_INIT_SCRIPT);

            ProcessBuilder pb = new ProcessBuilder(
                gradleBinary,
                "--init-script", initScript.toAbsolutePath().toString(),
                "javalensWriteClasspath",
                "-q",
                "--console=plain"
            );
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);

            log.info("Running Gradle to get classpath...");
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                log.warn("Cannot start Gradle subprocess: {}", e.getMessage());
                warnings.add(new LoadWarning(
                    LoadWarning.GRADLE_SUBPROCESS_FAILED,
                    "Could not start '" + gradleBinary + "': " + e.getMessage(),
                    "Verify the Gradle Wrapper is intact, or install Gradle and ensure 'gradle' is on PATH."));
                return new ArrayList<>(jars);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (capturedOutput.length() < 4096) {
                        capturedOutput.append(line).append('\n');
                    }
                }
            }

            // Gradle wrapper sometimes downloads a distribution on first run, hence a generous
            // timeout. Subsequent runs hit the cache.
            boolean completed = process.waitFor(10, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Gradle classpath command timed out");
                warnings.add(new LoadWarning(
                    LoadWarning.GRADLE_SUBPROCESS_FAILED,
                    "Gradle javalensWriteClasspath did not finish within 10 minutes",
                    "Check that the project's build.gradle resolves correctly. Try running './gradlew help' manually in " + projectPath));
                return new ArrayList<>(jars);
            }

            if (process.exitValue() == 0) {
                aggregateGradleClasspathFiles(projectPath, jars);
                log.info("Got {} classpath entries from Gradle", jars.size());
            } else {
                int exitCode = process.exitValue();
                log.warn("Gradle classpath command failed with exit code: {}", exitCode);
                String snippet = trimToLastLines(capturedOutput.toString(), 5);
                warnings.add(new LoadWarning(
                    LoadWarning.GRADLE_SUBPROCESS_FAILED,
                    "Gradle javalensWriteClasspath exited with code " + exitCode +
                        (snippet.isEmpty() ? "" : ". Last output: " + snippet),
                    "Run './gradlew help' manually in " + projectPath + " to see the full error."));
            }
        } catch (Exception e) {
            log.error("Failed to get Gradle classpath", e);
            warnings.add(new LoadWarning(
                LoadWarning.GRADLE_SUBPROCESS_FAILED,
                "Gradle invocation threw an unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                "Run './gradlew help' manually in " + projectPath + " to reproduce."));
        } finally {
            if (initScript != null) {
                try { Files.deleteIfExists(initScript); }
                catch (IOException e) { log.trace("Could not delete init script: {}", e.getMessage()); }
            }
            cleanupGradleClasspathFiles(projectPath);
        }

        return new ArrayList<>(jars);
    }

    /**
     * Locate a Gradle binary to invoke. Prefers the project's Gradle Wrapper
     * ({@code ./gradlew}), then {@code gradle} on PATH; returns {@code null} if neither is
     * available. Test runs can override via the {@code javalens.gradle.binary} system
     * property.
     */
    private String resolveGradleBinary(java.nio.file.Path projectPath) {
        String override = System.getProperty("javalens.gradle.binary");
        if (override != null && !override.isBlank()) return override;

        String wrapperName = isWindows() ? "gradlew.bat" : "gradlew";
        java.nio.file.Path wrapper = projectPath.resolve(wrapperName);
        if (Files.isRegularFile(wrapper)) return wrapper.toAbsolutePath().toString();

        return isWindows() ? "gradle.bat" : "gradle";
    }

    private void aggregateGradleClasspathFiles(java.nio.file.Path projectPath, java.util.Set<String> jars) {
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath)) {
            stream.filter(p -> p.getFileName() != null
                        && GRADLE_CP_FILENAME.equals(p.getFileName().toString()))
                  .filter(p -> p.getParent() != null
                        && p.getParent().getFileName() != null
                        && "build".equals(p.getParent().getFileName().toString()))
                  .forEach(cpFile -> {
                      try {
                          String content = Files.readString(cpFile).trim();
                          if (!content.isEmpty()) {
                              for (String entry : content.split(File.pathSeparator)) {
                                  if (!entry.isBlank()) jars.add(entry);
                              }
                          }
                      } catch (IOException e) {
                          log.warn("Could not read Gradle classpath file {}: {}", cpFile, e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.warn("Failed to walk {} for Gradle classpath files: {}", projectPath, e.getMessage());
        }
    }

    private void cleanupGradleClasspathFiles(java.nio.file.Path projectPath) {
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath)) {
            stream.filter(p -> p.getFileName() != null
                        && GRADLE_CP_FILENAME.equals(p.getFileName().toString()))
                  .filter(p -> p.getParent() != null
                        && p.getParent().getFileName() != null
                        && "build".equals(p.getParent().getFileName().toString()))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); }
                      catch (IOException e) { log.trace("Could not delete {}: {}", p, e.getMessage()); }
                  });
        } catch (IOException e) {
            log.trace("Cleanup walk failed for {}: {}", projectPath, e.getMessage());
        }
    }

    /**
     * Get dependency JARs from Bazel build output.
     *
     * <p>Bug E fix: {@code bazel-bin} is typically a symlink that resolves into
     * {@code bazel-out/<config>/bin/}. Scanning both naively produces every jar twice.
     * We canonicalize each scan root with {@link java.nio.file.Path#toRealPath} and skip
     * any root whose canonical path is contained in another root we've already scanned.
     * Within a single scan, we additionally key collected paths by their canonical form so
     * even hardlinks or nested symlinks dedupe.
     */
    private List<String> getBazelDependencies(java.nio.file.Path projectPath) {
        java.util.LinkedHashSet<java.nio.file.Path> canonicalJars = new java.util.LinkedHashSet<>();
        java.util.List<java.nio.file.Path> rootsToScan = canonicalizeAndDedupe(java.util.List.of(
            projectPath.resolve("bazel-bin"),
            projectPath.resolve("bazel-out")));
        for (java.nio.file.Path root : rootsToScan) {
            scanBazelDirForJars(root, canonicalJars);
        }
        List<String> jars = new ArrayList<>(canonicalJars.size());
        for (java.nio.file.Path p : canonicalJars) jars.add(p.toString());
        log.debug("Found {} JARs from Bazel output ({} candidate roots)", jars.size(), rootsToScan.size());
        return jars;
    }

    /**
     * Canonicalize each candidate root and drop any whose real path is contained in
     * another root's real path (to avoid double-walking the same physical directory tree).
     * Non-existent roots and roots whose canonicalization fails are skipped.
     */
    private java.util.List<java.nio.file.Path> canonicalizeAndDedupe(java.util.List<java.nio.file.Path> candidates) {
        java.util.LinkedHashMap<java.nio.file.Path, java.nio.file.Path> realToOriginal = new java.util.LinkedHashMap<>();
        for (java.nio.file.Path c : candidates) {
            if (!Files.exists(c)) continue;
            try {
                java.nio.file.Path real = c.toRealPath();
                realToOriginal.putIfAbsent(real, c);
            } catch (IOException e) {
                log.warn("Failed to canonicalize {}: {}", c, e.getMessage());
            }
        }
        java.util.List<java.nio.file.Path> reals = new ArrayList<>(realToOriginal.keySet());
        java.util.List<java.nio.file.Path> kept = new ArrayList<>();
        for (java.nio.file.Path candidate : reals) {
            boolean contained = false;
            for (java.nio.file.Path other : reals) {
                if (candidate.equals(other)) continue;
                if (candidate.startsWith(other)) { contained = true; break; }
            }
            if (!contained) kept.add(realToOriginal.get(candidate));
        }
        return kept;
    }

    private void scanBazelDirForJars(java.nio.file.Path dir, java.util.Set<java.nio.file.Path> canonicalJars) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<java.nio.file.Path> stream = Files.walk(dir)) {
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

    /**
     * Scan for Java source directories in a Bazel project.
     * Looks for directories containing both a BUILD/BUILD.bazel file and .java files.
     * Skips bazel-* output directories.
     */
    private void addBazelSourcePaths(java.nio.file.Path projectPath, List<java.nio.file.Path> sourcePaths) {
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath)) {
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

    private boolean isBazelOutputDirectory(java.nio.file.Path projectRoot, java.nio.file.Path dir) {
        if (dir.equals(projectRoot)) {
            return false;
        }
        java.nio.file.Path relative = projectRoot.relativize(dir);
        String first = relative.getName(0).toString();
        return first.startsWith("bazel-");
    }

    private boolean isBazelJavaPackage(java.nio.file.Path dir) {
        boolean hasBuildFile = Files.exists(dir.resolve("BUILD")) ||
                               Files.exists(dir.resolve("BUILD.bazel"));
        return hasBuildFile && containsJavaFiles(dir);
    }

    private boolean containsJavaFiles(java.nio.file.Path dir) {
        try (Stream<java.nio.file.Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }
}
