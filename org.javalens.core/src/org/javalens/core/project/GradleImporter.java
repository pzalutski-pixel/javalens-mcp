package org.javalens.core.project;

import org.javalens.core.project.model.LoadWarning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Gradle {@link BuildSystemImporter} implementation. Owns:
 * <ul>
 *   <li>Subproject discovery from {@code settings.gradle}{@code .kts}.</li>
 *   <li>Dependency assembly via a Gradle init script that registers a
 *       {@code javalensWriteClasspath} task in every Java subproject; we run it via
 *       the project's Gradle Wrapper (preferred) or {@code gradle} on PATH, then walk
 *       the tree for the per-subproject aux files it writes.</li>
 *   <li>Compiler-level and annotation-processor extraction from the aux files written
 *       by that same init script.</li>
 * </ul>
 *
 * <p>State: {@link #getDependencies} populates the compiler-level and processor-jar
 * caches as a side effect; {@link #detectCompilerLevel} and
 * {@link #detectAnnotationProcessors} read them (ignoring the {@code projectPath}
 * argument required by the {@link BuildSystemImporter} contract). The caches are
 * reset at the start of every {@link #getDependencies} invocation so stale state
 * from a prior import does not leak.
 */
public class GradleImporter implements BuildSystemImporter {

    private static final Logger log = LoggerFactory.getLogger(GradleImporter.class);

    /**
     * Init script that registers a {@code javalensWriteClasspath} task in every Java
     * subproject. The task writes three files under each subproject's {@code build/}:
     * <ul>
     *   <li>{@code javalens-classpath.txt} — testRuntime/runtime/compile classpath jars.</li>
     *   <li>{@code javalens-compliance.txt} — declared {@code sourceCompatibility} (Bug G).</li>
     *   <li>{@code javalens-processors.txt} — annotationProcessor configuration jars (Bug H).</li>
     * </ul>
     * GradleImporter reads all three after Gradle exits.
     */
    private static final String GRADLE_INIT_SCRIPT = """
        allprojects { proj ->
            proj.afterEvaluate {
                if (proj.plugins.hasPlugin('java-base')) {
                    proj.tasks.register('javalensWriteClasspath') {
                        doLast {
                            def buildDir = proj.buildDir
                            buildDir.mkdirs()

                            // Union compileClasspath (includes compileOnly deps like Lombok),
                            // testRuntimeClasspath (includes test deps), and runtimeClasspath
                            // (the actual runtime). Picking just one drops compileOnly deps,
                            // which JDT needs for code that references compile-time annotations.
                            def cps = [] as Set
                            ['compileClasspath', 'testCompileClasspath',
                             'runtimeClasspath', 'testRuntimeClasspath'].each { cfgName ->
                                proj.configurations.findByName(cfgName)?.resolve()?.each { cps << it.absolutePath }
                            }
                            new File(buildDir, 'javalens-classpath.txt').text =
                                cps.join(System.getProperty('path.separator'))

                            def srcCompat = ''
                            try {
                                if (proj.hasProperty('java') && proj.java.sourceCompatibility) {
                                    srcCompat = proj.java.sourceCompatibility.toString()
                                } else if (proj.hasProperty('sourceCompatibility') && proj.sourceCompatibility) {
                                    srcCompat = proj.sourceCompatibility.toString()
                                }
                            } catch (Exception ignored) {}
                            if (srcCompat) {
                                new File(buildDir, 'javalens-compliance.txt').text = srcCompat
                            }

                            def procs = [] as Set
                            proj.configurations.findByName('annotationProcessor')?.resolve()?.each { procs << it.absolutePath }
                            if (procs) {
                                new File(buildDir, 'javalens-processors.txt').text =
                                    procs.join(System.getProperty('path.separator'))
                            }
                        }
                    }
                }
            }
        }
        """;

    private static final String GRADLE_CP_FILENAME = "javalens-classpath.txt";
    private static final String GRADLE_COMPLIANCE_FILENAME = "javalens-compliance.txt";
    private static final String GRADLE_PROCESSORS_FILENAME = "javalens-processors.txt";

    private String cachedCompilerLevel;
    private final List<Path> cachedProcessorJars = new ArrayList<>();

    /**
     * Parse {@code settings.gradle} (or {@code settings.gradle.kts}) for {@code include}
     * directives and resolve each to a subproject directory under the root.
     *
     * <p>Recognizes the common forms: {@code include 'a'}, {@code include "a"},
     * {@code include 'a', 'b'} and {@code include('a')}. Colon-prefixed paths
     * (e.g. {@code include ':app:core'}) are normalized: leading colons stripped,
     * internal colons converted to file separators.
     */
    public List<Path> getSubprojects(Path projectPath) {
        Path settings = projectPath.resolve("settings.gradle");
        if (!Files.exists(settings)) settings = projectPath.resolve("settings.gradle.kts");
        if (!Files.exists(settings)) return List.of();

        List<Path> subprojects = new ArrayList<>();
        try {
            String content = Files.readString(settings);
            Matcher includeMatcher = Pattern.compile(
                "include\\s*\\(?\\s*((?:['\"][^'\"]+['\"]\\s*,?\\s*)+)").matcher(content);
            while (includeMatcher.find()) {
                Matcher nameMatcher = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(includeMatcher.group(1));
                while (nameMatcher.find()) {
                    String raw = nameMatcher.group(1).trim();
                    if (raw.isEmpty()) continue;
                    // Gradle ':app:core' notation -> 'app/core' on disk.
                    String pathStr = raw.replaceAll("^:+", "").replace(':', '/');
                    Path subprojectPath = projectPath.resolve(pathStr);
                    if (Files.isDirectory(subprojectPath)) {
                        subprojects.add(subprojectPath);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read settings.gradle for subprojects: {}", e.getMessage());
        }
        return subprojects;
    }

    /**
     * Run the project's Gradle build with our init script, harvest the per-subproject
     * classpath/compliance/processor aux files, populate the compiler-level and
     * processor-jar caches as a side effect, and return the union of classpath jars.
     *
     * <p>Bug D fix: the original implementation was a stub returning {@code List.of()},
     * leaving Gradle projects with empty classpaths. We now ship a Gradle init script
     * that registers a {@code javalensWriteClasspath} task in every subproject and run
     * it via the project's Gradle Wrapper (preferred) or {@code gradle} on PATH.
     * Per-subproject classpath files land at
     * {@code <subproject>/build/javalens-classpath.txt}; we walk the tree and union them.
     */
    public List<String> getDependencies(Path projectPath, List<LoadWarning> warnings) {
        // Reset cache state for every invocation so a stale value from a prior import
        // can never leak into the next.
        cachedCompilerLevel = null;
        cachedProcessorJars.clear();

        LinkedHashSet<String> jars = new LinkedHashSet<>();

        String gradleBinary = resolveGradleBinary(projectPath);
        if (gradleBinary == null) {
            warnings.add(new LoadWarning(
                LoadWarning.GRADLE_SUBPROCESS_FAILED,
                "No Gradle binary available: neither the project's Gradle Wrapper nor a 'gradle' on PATH",
                "Commit a Gradle Wrapper (./gradlew) into the project, or install Gradle and ensure 'gradle' is on PATH for the process that launches JavaLens."));
            return new ArrayList<>(jars);
        }

        Path initScript = null;
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

            // Gradle wrapper sometimes downloads a distribution on first run, hence a
            // generous timeout. Subsequent runs hit the cache.
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
                // Harvest compliance + processor jars while their aux files still exist.
                // The cleanup in finally below removes them; subsequent
                // applyCompilerOptions / applyAnnotationProcessing read from the cache
                // populated here.
                cachedCompilerLevel = readFirstGradleAuxFile(projectPath, GRADLE_COMPLIANCE_FILENAME);
                cachedProcessorJars.addAll(readGradleProcessorJars(projectPath));
                log.info("Got {} classpath entries from Gradle (compliance={}, {} processor jars)",
                    jars.size(), cachedCompilerLevel, cachedProcessorJars.size());
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
     * Cached during {@link #getDependencies}; null when no Gradle build ran. The
     * {@code projectPath} parameter is required by the {@link BuildSystemImporter}
     * contract but ignored — the cache is populated once per import and the path was
     * already provided to {@code getDependencies}.
     */
    @Override
    public String detectCompilerLevel(Path projectPath) {
        return cachedCompilerLevel;
    }

    /**
     * Cached during {@link #getDependencies}; empty when no Gradle build ran. The
     * {@code projectPath} parameter is ignored (see {@link #detectCompilerLevel}).
     */
    @Override
    public List<Path> detectAnnotationProcessors(Path projectPath) {
        return new ArrayList<>(cachedProcessorJars);
    }

    /**
     * Locate a Gradle binary to invoke. Prefers the project's Gradle Wrapper
     * ({@code ./gradlew}), then {@code gradle} on PATH; returns {@code null} if neither
     * is available. Test runs can override via the {@code javalens.gradle.binary} system
     * property.
     */
    private String resolveGradleBinary(Path projectPath) {
        String override = System.getProperty("javalens.gradle.binary");
        if (override != null && !override.isBlank()) return override;

        String wrapperName = isWindows() ? "gradlew.bat" : "gradlew";
        Path wrapper = projectPath.resolve(wrapperName);
        if (Files.isRegularFile(wrapper)) return wrapper.toAbsolutePath().toString();

        return isWindows() ? "gradle.bat" : "gradle";
    }

    private void aggregateGradleClasspathFiles(Path projectPath, Set<String> jars) {
        try (Stream<Path> stream = Files.walk(projectPath)) {
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

    private void cleanupGradleClasspathFiles(Path projectPath) {
        // Cleanup all three aux files written by the init script. Compliance + processor
        // files are read by applyCompilerOptions / applyAnnotationProcessing further along
        // configureJavaProject, so cleanup happens after those callsites consume them.
        Set<String> auxFiles = Set.of(
            GRADLE_CP_FILENAME, GRADLE_COMPLIANCE_FILENAME, GRADLE_PROCESSORS_FILENAME);
        try (Stream<Path> stream = Files.walk(projectPath)) {
            stream.filter(p -> p.getFileName() != null
                        && auxFiles.contains(p.getFileName().toString()))
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
     * Read the first {@code build/<filename>} aux file the init script wrote. In a
     * multi-project build every Java subproject writes its own; we use the first one as
     * a representative value (compliance levels are typically uniform).
     */
    private String readFirstGradleAuxFile(Path projectPath, String filename) {
        try (Stream<Path> stream = Files.walk(projectPath)) {
            return stream.filter(p -> p.getFileName() != null
                        && filename.equals(p.getFileName().toString()))
                    .filter(p -> p.getParent() != null
                        && p.getParent().getFileName() != null
                        && "build".equals(p.getParent().getFileName().toString()))
                    .map(p -> {
                        try { return Files.readString(p).trim(); }
                        catch (IOException e) { return ""; }
                    })
                    .filter(s -> !s.isEmpty())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.debug("Failed to walk for Gradle {} files: {}", filename, e.getMessage());
            return null;
        }
    }

    /**
     * Walk for {@code build/javalens-processors.txt} files and union their entries into
     * a list of processor jar paths.
     */
    private List<Path> readGradleProcessorJars(Path projectPath) {
        LinkedHashSet<Path> jars = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(projectPath)) {
            stream.filter(p -> p.getFileName() != null
                        && GRADLE_PROCESSORS_FILENAME.equals(p.getFileName().toString()))
                  .filter(p -> p.getParent() != null
                        && p.getParent().getFileName() != null
                        && "build".equals(p.getParent().getFileName().toString()))
                  .forEach(file -> {
                      try {
                          String content = Files.readString(file).trim();
                          if (content.isEmpty()) return;
                          for (String entry : content.split(File.pathSeparator)) {
                              String trimmed = entry.trim();
                              if (trimmed.isEmpty()) continue;
                              Path jar = Path.of(trimmed);
                              if (Files.isRegularFile(jar)) jars.add(jar);
                          }
                      } catch (IOException e) {
                          log.warn("Could not read Gradle processors file {}: {}", file, e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.warn("Failed to walk for Gradle processor files: {}", e.getMessage());
        }
        return new ArrayList<>(jars);
    }

    /** Returns the last {@code maxLines} of {@code text}, joined with " | ", trimmed. */
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

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
