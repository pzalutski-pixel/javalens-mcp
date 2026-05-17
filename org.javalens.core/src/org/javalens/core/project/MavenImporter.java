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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Maven {@link BuildSystemImporter} implementation. Owns:
 * <ul>
 *   <li>Multi-module detection and recursive module walking driven by
 *       {@code <modules>} entries in {@code pom.xml}.</li>
 *   <li>Dependency assembly via {@code mvn dependency:build-classpath}, with
 *       per-module aux files unioned after the build exits.</li>
 *   <li>Compiler-level extraction from {@code <properties>} shortcuts and the
 *       {@code maven-compiler-plugin} {@code <configuration>} block.</li>
 *   <li>Annotation-processor discovery by parsing {@code <annotationProcessorPaths>}
 *       blocks across the whole reactor, resolved against the local Maven repository.</li>
 * </ul>
 */
public class MavenImporter implements BuildSystemImporter {

    private static final Logger log = LoggerFactory.getLogger(MavenImporter.class);

    // Pattern to extract module names from pom.xml
    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>([^<]+)</module>");

    /**
     * Per-module classpath file path written by {@code dependency:build-classpath}.
     *
     * <p>Bug C: passing this as a *relative* path (rather than absolute) makes each
     * reactor child write to its own {@code <module>/target/javalens-classpath.txt};
     * an absolute path causes every child to overwrite the same file so only the last
     * child's classpath survives. The {@code target/} prefix is necessary because the
     * dependency-plugin resolves relative {@code mdep.outputFile} against the module's
     * project base directory, not its build directory.
     */
    private static final String MAVEN_CP_FILENAME = "javalens-classpath.txt";
    private static final String MAVEN_CP_RELATIVE_PATH = "target/" + MAVEN_CP_FILENAME;

    /**
     * Detect if this is a multi-module Maven project.
     */
    public boolean isMultiModule(Path projectPath) {
        Path pomPath = projectPath.resolve("pom.xml");
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
    public List<Path> getModules(Path projectPath) {
        List<Path> modules = new ArrayList<>();
        Path pomPath = projectPath.resolve("pom.xml");

        if (!Files.exists(pomPath)) {
            return modules;
        }

        try {
            String content = Files.readString(pomPath);
            Matcher matcher = MODULE_PATTERN.matcher(content);
            while (matcher.find()) {
                String moduleName = matcher.group(1).trim();
                Path modulePath = projectPath.resolve(moduleName);
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
     * Recursively visit a Maven module and every {@code <module>} entry it declares,
     * calling {@code visitor} on each module root (including pure aggregators that have
     * {@code <modules>} but no {@code src/main/java}). The visited set is keyed on
     * canonical paths so a relative {@code <module>} like {@code ../sibling} that loops
     * back to an already-visited directory exits cleanly.
     *
     * <p>The visitor is invoked once per module root in the reactor; callers wire their
     * own per-module logic (e.g. probing for source layouts).
     */
    public void walkModules(Path projectPath, Consumer<Path> visitor) {
        walkModulesInternal(projectPath, visitor, new HashSet<>());
    }

    private void walkModulesInternal(Path moduleRoot, Consumer<Path> visitor, Set<Path> visited) {
        Path canonical;
        try {
            canonical = moduleRoot.toRealPath();
        } catch (IOException e) {
            canonical = moduleRoot.toAbsolutePath().normalize();
        }
        if (!visited.add(canonical)) return;

        visitor.accept(moduleRoot);

        if (isMultiModule(moduleRoot)) {
            for (Path child : getModules(moduleRoot)) {
                walkModulesInternal(child, visitor, visited);
            }
        }
    }

    /**
     * Read the project's declared Java source level from pom.xml. Tries, in priority order:
     * <ol>
     *   <li>{@code <properties>}: {@code maven.compiler.release} > {@code source} >
     *       {@code target}.</li>
     *   <li>{@code <plugin><artifactId>maven-compiler-plugin</artifactId><configuration>}:
     *       {@code <release>} > {@code <source>} > {@code <target>}. This form is the more
     *       common one in real projects that don't use the property shortcuts.</li>
     * </ol>
     * Returns {@code null} if neither location declares a level.
     */
    public String detectCompilerLevel(Path projectPath) {
        Path pom = projectPath.resolve("pom.xml");
        if (!Files.exists(pom)) return null;
        try {
            String content = Files.readString(pom);

            // 1. <properties> shortcuts.
            for (String key : new String[]{"maven.compiler.release", "maven.compiler.source", "maven.compiler.target"}) {
                String value = extractXmlText(content, key);
                if (value != null) return resolvePomProperty(content, value);
            }

            // 2. <plugin>maven-compiler-plugin</plugin>'s <configuration> block. Match
            // <plugin>...</plugin> spans first, then check artifactId inside — handles
            // groupId-before-artifactId, artifactId-before-groupId, or no groupId at all.
            Matcher pluginBlock = Pattern.compile("<plugin>(.*?)</plugin>", Pattern.DOTALL).matcher(content);
            while (pluginBlock.find()) {
                String inner = pluginBlock.group(1);
                String artifact = extractXmlText(inner, "artifactId");
                if (!"maven-compiler-plugin".equals(artifact)) continue;
                Matcher configBlock = Pattern.compile("<configuration>(.*?)</configuration>", Pattern.DOTALL).matcher(inner);
                if (configBlock.find()) {
                    String config = configBlock.group(1);
                    for (String tag : new String[]{"release", "source", "target"}) {
                        String value = extractXmlText(config, tag);
                        if (value != null) return resolvePomProperty(content, value);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read pom.xml for compiler level: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Parse {@code <annotationProcessorPaths>} blocks from {@code maven-compiler-plugin}
     * configuration across the whole reactor, resolve each {@code <path>} to the
     * corresponding jar in the local Maven repository, and union the results.
     *
     * <p>In a multi-module project the {@code <annotationProcessorPaths>} block typically
     * lives in a child module's pom (e.g. only {@code :model} declares Lombok), so reading
     * just the root pom misses the processors entirely. We walk the reactor: root pom plus
     * every module's pom, recursively for nested modules. Property references in
     * {@code <version>} (e.g. {@code ${lombok.version}}) resolve against the pom they
     * appear in first, then fall back to the parent if undefined.
     */
    public List<Path> detectAnnotationProcessors(Path projectPath) {
        List<Path> jars = new ArrayList<>();
        LinkedHashSet<Path> visited = new LinkedHashSet<>();
        collectProcessorJarsRecursive(projectPath, null, jars, visited);
        return jars;
    }

    private void collectProcessorJarsRecursive(Path moduleRoot, String parentPomContent,
            List<Path> jars, Set<Path> visited) {
        if (!visited.add(moduleRoot)) return;

        Path pom = moduleRoot.resolve("pom.xml");
        if (!Files.exists(pom)) return;

        String content;
        try {
            content = Files.readString(pom);
        } catch (IOException e) {
            log.debug("Failed to read pom.xml at {}: {}", pom, e.getMessage());
            return;
        }

        parseProcessorPathsFromPomContent(content, parentPomContent, jars);

        // Recurse into <modules> entries.
        Matcher matcher = MODULE_PATTERN.matcher(content);
        while (matcher.find()) {
            String moduleName = matcher.group(1).trim();
            Path childRoot = moduleRoot.resolve(moduleName);
            if (Files.isDirectory(childRoot)) {
                collectProcessorJarsRecursive(childRoot, content, jars, visited);
            }
        }
    }

    private void parseProcessorPathsFromPomContent(String content, String parentPomContent, List<Path> jars) {
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
                    if (version.startsWith("${") && parentPomContent != null) {
                        version = resolvePomProperty(parentPomContent, version);
                    }
                    Path jar = mavenRepoJarPath(group, artifact, version);
                    if (Files.isRegularFile(jar)) {
                        jars.add(jar);
                    } else {
                        log.warn("Annotation processor jar missing in local repo: {} " +
                            "(run 'mvn dependency:resolve' to download)", jar);
                    }
                }
            }
        }
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

    private Path mavenRepoJarPath(String groupId, String artifactId, String version) {
        String repo = System.getProperty("user.home") + "/.m2/repository";
        String groupPath = groupId.replace('.', '/');
        return Path.of(repo, groupPath, artifactId, version,
            artifactId + "-" + version + ".jar");
    }

    /**
     * Run {@code mvn dependency:build-classpath} with a per-module {@code mdep.outputFile},
     * then walk the reactor for the per-module aux files and union their contents. Failures
     * and timeouts append a {@code MAVEN_SUBPROCESS_FAILED} / {@code MAVEN_SUBPROCESS_TIMEOUT}
     * warning so the orchestrator can surface degraded loads.
     */
    public List<String> getDependencies(Path projectPath, List<LoadWarning> warnings) {
        LinkedHashSet<String> jars = new LinkedHashSet<>();
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
                int filesFound = aggregateClasspathFiles(projectPath, jars);
                log.info("Got {} classpath entries from Maven ({} per-module files)", jars.size(), filesFound);
                // Narrow Bug X regression: mvn can exit 0 yet write zero classpath files
                // — for example, a custom dependency-plugin version that doesn't honor our
                // flag, a profile that disables the plugin, or a different mojo binding.
                // Distinguish "mvn wrote nothing" (suspicious, soft fail) from "mvn wrote
                // files but the project has no deps" (legitimate, no warning).
                if (filesFound == 0) {
                    String snippet = trimToLastLines(capturedOutput.toString(), 5);
                    warnings.add(new LoadWarning(
                        LoadWarning.MAVEN_SUBPROCESS_FAILED,
                        "mvn dependency:build-classpath exited successfully but produced no " +
                            "classpath files. Last output: " + (snippet.isEmpty() ? "(empty)" : snippet),
                        "Run 'mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt' " +
                            "manually in " + projectPath + " to confirm the plugin emits output."));
                }
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
     * Walk the project tree for {@code <module>/target/javalens-classpath.txt} files
     * written by {@code dependency:build-classpath} and union their contents into
     * {@code jars}. The caller passes a {@link LinkedHashSet} so duplicates across
     * modules collapse while preserving discovery order.
     *
     * @return number of classpath files actually found and read. Distinguishes "mvn ran but
     *     produced no files" (suspicious, plugin disabled or wrong version) from "mvn ran,
     *     produced files, files were empty" (legitimate — project has no dependencies).
     */
    private int aggregateClasspathFiles(Path projectPath, Set<String> jars) {
        AtomicInteger filesFound = new AtomicInteger();
        try (Stream<Path> stream = Files.walk(projectPath)) {
            stream.filter(p -> p.getFileName() != null
                        && MAVEN_CP_FILENAME.equals(p.getFileName().toString()))
                  .filter(p -> p.getParent() != null
                        && p.getParent().getFileName() != null
                        && "target".equals(p.getParent().getFileName().toString()))
                  .forEach(cpFile -> {
                      filesFound.incrementAndGet();
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
        return filesFound.get();
    }

    /**
     * Remove every {@code <module>/target/javalens-classpath.txt} we may have written so we
     * don't leave artifacts behind in the user's project.
     */
    private void cleanupClasspathFiles(Path projectPath) {
        try (Stream<Path> stream = Files.walk(projectPath)) {
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
