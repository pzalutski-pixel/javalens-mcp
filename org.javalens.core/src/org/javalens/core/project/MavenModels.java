package org.javalens.core.project;

import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds a Maven project's {@link BuildModel} from the poms Maven reports or, for a static
 * read, from the {@code pom.xml} files themselves, read as XML through {@link SecureXml}.
 *
 * <p>Modules are the reactor walked from the project root: each module's {@code <modules>}
 * entries name its child modules, relative to its pom. Each module's id is its
 * {@code groupId:artifactId}; its source roots are its source and test source directories,
 * then the build-helper and Kotlin source directories its pom adds, that exist; its generated
 * roots are the subdirectories of its build directory's {@code generated-sources} and
 * {@code generated-test-sources}; its output directories are its output and test output
 * directories; its declared level is the level maven-compiler-plugin compiles it at
 * ({@link MavenPom#compilerLevel}).
 *
 * <p>Modules a profile declares that are not in the reactor, and Gradle projects a
 * {@code settings.gradle} beside the root pom declares, stay linked with their conventional
 * layout and a {@link LoadWarning#INCLUDED_OUTSIDE_BUILD}, as do conventional source folders
 * no module declares ({@link ConventionalFolders}).
 */
final class MavenModels {

    private static final Logger log = LoggerFactory.getLogger(MavenModels.class);

    private static final LinkedFolderConfigurator LAYOUT = new LinkedFolderConfigurator();

    /** Maven's per-project configuration files under the root, read whether or not they exist. */
    private static final List<String> MAVEN_CONFIG_FILES = List.of(
        ".mvn/maven.config", ".mvn/jvm.config", ".mvn/extensions.xml");

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private MavenModels() {
    }

    /**
     * One reactor module.
     *
     * @param dir the module's directory
     * @param pomFile its pom
     * @param pom what the model is built from: Maven's effective pom, or for a static read
     *     the raw pom with its parents' values and its references resolved
     * @param raw the raw pom
     */
    record Module(Path dir, Path pomFile, MavenPom pom, MavenPom raw) {
    }

    /** Resolves the module whose pom is {@code pomFile} in {@code dir}. */
    private interface PomSource {
        Module module(Path dir, Path pomFile) throws IOException;
    }

    // ========== Reading ==========

    /** The projects of the effective pom file {@code effectivePom}, a {@code <projects>} or one {@code <project>}. */
    static List<MavenPom> readEffective(Path effectivePom) throws IOException {
        Element root = SecureXml.read(effectivePom);
        List<Element> projects = "project".equals(SecureXml.localName(root))
            ? List.of(root) : SecureXml.children(root, "project");
        if (projects.isEmpty()) {
            throw new IOException(effectivePom.getFileName() + " holds no project");
        }
        return projects.stream().map(MavenPom::of).toList();
    }

    /**
     * The reactor Maven reported as {@code effective}, walked from the project root. A module
     * is matched to its effective pom by the {@code groupId:artifactId} its raw pom declares.
     *
     * @throws UncheckedIOException when the root pom cannot be read or matches no reported project
     */
    static List<Module> walkEffective(Path projectRoot, List<MavenPom> effective, List<LoadWarning> warnings) {
        Map<String, MavenPom> byId = new HashMap<>();
        Map<String, List<MavenPom>> byArtifact = new HashMap<>();
        for (MavenPom pom : effective) {
            byId.putIfAbsent(pom.id(), pom);
            byArtifact.computeIfAbsent(pom.artifactId(), key -> new ArrayList<>()).add(pom);
        }
        return walk(projectRoot, (dir, pomFile) -> {
            MavenPom raw = MavenPom.of(SecureXml.read(pomFile));
            MavenPom pom = byId.get(raw.id());
            if (pom == null) {
                List<MavenPom> sameArtifact = byArtifact.getOrDefault(raw.artifactId(), List.of());
                if (sameArtifact.size() != 1) {
                    throw new IOException("Maven reported no project " + raw.id());
                }
                pom = sameArtifact.get(0);
            }
            return new Module(dir, pomFile, pom, raw);
        }, warnings);
    }

    /**
     * The reactor the {@code pom.xml} files declare, walked from the project root. Each pom
     * takes its parent's values when the parent is a pom on disk at its
     * {@code relativePath} whose artifactId is the one it names, and has its {@code ${...}}
     * references resolved against its properties and Maven's {@code project.*} values.
     *
     * @throws UncheckedIOException when the root pom cannot be read
     */
    static List<Module> walkStatic(Path projectRoot, List<LoadWarning> warnings) {
        return walk(projectRoot, (dir, pomFile) -> {
            MavenPom raw = MavenPom.of(SecureXml.read(pomFile));
            MavenPom merged = raw;
            for (ParentPom parent : parentChain(pomFile)) {
                merged = merged.inheriting(parent.pom());
            }
            return new Module(dir, pomFile, interpolate(merged, dir), raw);
        }, warnings);
    }

    /** A parent pom found on disk. */
    private record ParentPom(Path file, MavenPom pom) {
    }

    /**
     * The parents of the pom at {@code pomFile} that are on disk, nearest first: each one the
     * pom at its child's {@code relativePath} (a directory means its {@code pom.xml}) whose
     * artifactId is the one the child names. The chain ends at a pom with no parent, a
     * parent not on disk, one that cannot be read, or a pom already in the chain.
     */
    private static List<ParentPom> parentChain(Path pomFile) {
        List<ParentPom> chain = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        Path current = pomFile;
        while (seen.add(ProjectPaths.identity(current))) {
            Element element;
            try {
                element = SecureXml.read(current);
            } catch (IOException e) {
                break;
            }
            Optional<String> parentArtifact = SecureXml.text(element, "parent", "artifactId");
            String relative = SecureXml.path(element, "parent", "relativePath")
                .map(e -> e.getTextContent().trim()).orElse("../pom.xml");
            if (parentArtifact.isEmpty() || relative.isEmpty()) break;
            Path parentFile;
            try {
                parentFile = current.resolveSibling(relative).normalize();
            } catch (InvalidPathException e) {
                break;
            }
            if (Files.isDirectory(parentFile)) parentFile = parentFile.resolve("pom.xml");
            if (!Files.isRegularFile(parentFile)) break;
            MavenPom parent;
            try {
                parent = MavenPom.of(SecureXml.read(parentFile));
            } catch (IOException e) {
                break;
            }
            if (!parentArtifact.get().equals(parent.artifactId())) break;
            chain.add(new ParentPom(parentFile, parent));
            current = parentFile;
        }
        return chain;
    }

    /**
     * {@code pom} with each {@code ${name}} reference replaced by its property, or by
     * {@code project.basedir}, {@code basedir}, {@code project.groupId},
     * {@code project.artifactId}, {@code project.version} or {@code project.build.directory};
     * references are resolved repeatedly, so a property may refer to another. A reference to
     * an unknown name stays as written.
     */
    private static MavenPom interpolate(MavenPom pom, Path dir) {
        Map<String, String> values = new HashMap<>(pom.properties());
        values.put("project.basedir", dir.toString());
        values.put("basedir", dir.toString());
        if (pom.groupId() != null) values.put("project.groupId", pom.groupId());
        if (pom.artifactId() != null) values.put("project.artifactId", pom.artifactId());
        if (pom.version() != null) values.put("project.version", pom.version());
        values.putIfAbsent("project.build.directory", dir.resolve("target").toString());
        return pom.resolved(value -> resolveReferences(value, values));
    }

    private static String resolveReferences(String value, Map<String, String> values) {
        String current = value;
        for (int pass = 0; pass < 10 && current.contains("${"); pass++) {
            Matcher matcher = REFERENCE.matcher(current);
            StringBuilder resolved = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = values.get(matcher.group(1));
                if (replacement != null) changed = true;
                matcher.appendReplacement(resolved,
                    Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
            }
            matcher.appendTail(resolved);
            current = resolved.toString();
            if (!changed) break;
        }
        return current;
    }

    // ========== The reactor walk ==========

    /**
     * Walk the reactor from the root pom. Each {@code <modules>} entry names a directory
     * holding a {@code pom.xml}, or a pom file, relative to the declaring pom. An entry that
     * does not resolve, or whose pom cannot be read, is a {@link LoadWarning#MODULE_NOT_LOADED};
     * a module reached a second time, in any spelling of its path, is visited once.
     */
    private static List<Module> walk(Path projectRoot, PomSource source, List<LoadWarning> warnings) {
        List<Module> modules = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        Module root;
        try {
            root = source.module(projectRoot, projectRoot.resolve("pom.xml"));
        } catch (IOException e) {
            throw new UncheckedIOException("The root pom.xml could not be read: " + e.getMessage(), e);
        }
        visit(projectRoot, root, source, modules, visited, warnings);
        return modules;
    }

    private static void visit(Path projectRoot, Module module, PomSource source, List<Module> modules,
            Set<Path> visited, List<LoadWarning> warnings) {
        if (!visited.add(ProjectPaths.identity(module.dir()))) return;
        modules.add(module);
        // Every entry of this pom is resolved, and each one not loaded reported, before the
        // walk descends, so a pom's declarations are reported before its modules' ones.
        List<Module> children = new ArrayList<>();
        for (String entry : module.pom().modules()) {
            Optional<Path> pomFile = modulePom(projectRoot, module.pomFile(), entry, warnings);
            if (pomFile.isEmpty()) continue;
            try {
                children.add(source.module(pomFile.get().getParent(), pomFile.get()));
            } catch (IOException e) {
                warnings.add(new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
                    ProjectPaths.relativeToRoot(projectRoot, module.pomFile()) + " declares '" + entry
                        + "', but its pom could not be read: " + e.getMessage(),
                    "Correct the module's pom.xml, then reload the project.",
                    entry));
            }
        }
        for (Module child : children) {
            visit(projectRoot, child, source, modules, visited, warnings);
        }
    }

    /**
     * The pom of the module {@code declaringPom} declares as {@code entry}: the file itself
     * when the entry names a file, otherwise the {@code pom.xml} of the directory it names.
     * Empty, with the reason appended to {@code warnings}, when it names neither.
     */
    private static Optional<Path> modulePom(Path projectRoot, Path declaringPom, String entry,
            List<LoadWarning> warnings) {
        try {
            Path named = declaringPom.resolveSibling(entry).normalize();
            if (Files.isRegularFile(named)) {
                return Optional.of(named);
            }
        } catch (InvalidPathException e) {
            // Reported, with the reason, by the directory lookup below.
        }
        return DeclaredPaths.mavenModuleDir(projectRoot, declaringPom, entry, warnings)
            .map(dir -> dir.normalize().resolve("pom.xml"));
    }

    // ========== The model ==========

    /**
     * The model of {@code reactor}. {@code classpathDir} holds each module's classpath file,
     * {@code <groupId>__<artifactId>.txt}, as Maven wrote it, or is null for a static read,
     * whose classpath is empty. Processor jars are resolved in {@code localRepository}, or not
     * at all when it is null. {@code trailing} warnings follow the model's own.
     */
    static BuildModel model(Path projectRoot, Provenance provenance, List<Module> reactor, Path classpathDir,
            Path localRepository, List<LoadWarning> warnings, List<LoadWarning> trailing) {
        List<BuildModule> modules = new ArrayList<>();
        for (Module module : reactor) {
            modules.add(buildModule(module, provenance == Provenance.BUILD_TOOL));
        }
        Set<Path> inputs = new LinkedHashSet<>();
        for (Module module : reactor) {
            inputs.add(module.pomFile());
            parentChain(module.pomFile()).forEach(parent -> inputs.add(parent.file()));
        }
        addProfileModules(projectRoot, reactor, modules, inputs, warnings);
        addGradleProjects(projectRoot, modules, inputs, warnings);
        for (String config : MAVEN_CONFIG_FILES) {
            inputs.add(projectRoot.resolve(config));
        }

        List<BuildModule> kept = ConventionalFolders.keepUndeclared(projectRoot, modules, warnings);
        String level = DeclaredLevels.analysisLevel(kept, warnings);
        List<String> classpath = classpathDir == null ? List.of() : classpath(reactor, classpathDir);
        List<Path> processorJars = localRepository == null ? List.of()
            : processorJars(reactor, localRepository, warnings);

        List<LoadWarning> allWarnings = new ArrayList<>(warnings);
        allWarnings.addAll(trailing);
        return new BuildModel(BuildSystem.MAVEN, provenance, kept, classpath, processorJars, level,
            new BuildInputs(List.copyOf(inputs), List.of()), allWarnings);
    }

    private static BuildModule buildModule(Module module, boolean evaluated) {
        MavenPom pom = module.pom();
        Path dir = module.dir();
        Path buildDir = buildDirectory(module);
        Set<Path> sources = new LinkedHashSet<>();
        sources.add(path(dir, pom.sourceDirectory()).orElse(dir.resolve("src/main/java")));
        sources.add(path(dir, pom.testSourceDirectory()).orElse(dir.resolve("src/test/java")));
        pom.mainSources().forEach(source -> path(dir, source).ifPresent(sources::add));
        pom.testSources().forEach(source -> path(dir, source).ifPresent(sources::add));
        List<Path> sourceRoots = sources.stream().filter(Files::isDirectory).distinct().toList();

        List<Path> generated = new ArrayList<>();
        addSubdirectories(buildDir.resolve("generated-sources"), generated);
        addSubdirectories(buildDir.resolve("generated-test-sources"), generated);

        List<Path> outputs = List.of(
            path(dir, pom.outputDirectory()).orElse(buildDir.resolve("classes")),
            path(dir, pom.testOutputDirectory()).orElse(buildDir.resolve("test-classes")));
        String id = pom.artifactId() == null ? String.valueOf(dir.getFileName()) : pom.id();
        return new BuildModule(id, dir, sourceRoots, generated, outputs, pom.compilerLevel(), evaluated);
    }

    /**
     * Modules a reactor pom declares only in a profile and the reactor does not hold: each is
     * linked with its conventional layout and named by an {@link LoadWarning#INCLUDED_OUTSIDE_BUILD},
     * its pom an input whether or not it exists.
     */
    private static void addProfileModules(Path projectRoot, List<Module> reactor, List<BuildModule> modules,
            Set<Path> inputs, List<LoadWarning> warnings) {
        for (Module module : reactor) {
            for (Map.Entry<String, List<String>> profile : module.raw().profileModules().entrySet()) {
                for (String entry : profile.getValue()) {
                    Optional<Path> pomFile = modulePom(projectRoot, module.pomFile(), entry, warnings);
                    if (pomFile.isEmpty()) continue;
                    inputs.add(pomFile.get());
                    Path dir = pomFile.get().getParent();
                    if (holds(modules, dir)) continue;
                    String id = rawId(pomFile.get()).orElse(ProjectPaths.relativeToRoot(projectRoot, dir));
                    modules.add(conventionalModule(id, dir));
                    warnings.add(new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                        ProjectPaths.relativeToRoot(projectRoot, dir) + " is declared as a module only in profile '"
                            + profile.getKey() + "' of " + ProjectPaths.relativeToRoot(projectRoot, module.pomFile())
                            + ", which the load did not activate, so its dependencies are not resolved; it is linked "
                            + "with its conventional layout",
                        "Activate the profile through JAVALENS_MAVEN_ARGS (for example -P" + profile.getKey()
                            + ") and reload the project to load the module fully.",
                        id));
                }
            }
        }
    }

    /**
     * Gradle projects a {@code settings.gradle} or {@code settings.gradle.kts} beside the root
     * pom declares that no module holds: each is linked with its conventional layout and
     * named by an {@link LoadWarning#INCLUDED_OUTSIDE_BUILD}; the settings file is an input.
     */
    private static void addGradleProjects(Path projectRoot, List<BuildModule> modules, Set<Path> inputs,
            List<LoadWarning> warnings) {
        for (String settings : List.of("settings.gradle", "settings.gradle.kts")) {
            if (Files.isRegularFile(projectRoot.resolve(settings))) {
                inputs.add(projectRoot.resolve(settings));
            }
        }
        for (Path dir : new GradleImporter().getSubprojects(projectRoot, warnings)) {
            if (holds(modules, dir)) continue;
            String id = ProjectPaths.relativeToRoot(projectRoot, dir);
            modules.add(conventionalModule(id, dir));
            warnings.add(new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                id + " is a Gradle project the settings file declares, but the project is loaded as a Maven build, "
                    + "so its dependencies are not resolved; it is linked with its conventional layout",
                "Load it as part of its Gradle build, or remove it from the settings file.",
                id));
        }
    }

    private static boolean holds(List<BuildModule> modules, Path dir) {
        Path identity = ProjectPaths.identity(dir);
        return modules.stream().anyMatch(module -> ProjectPaths.identity(module.dir()).equals(identity));
    }

    private static Optional<String> rawId(Path pomFile) {
        try {
            MavenPom raw = MavenPom.of(SecureXml.read(pomFile));
            return raw.artifactId() == null ? Optional.empty() : Optional.of(raw.id());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static BuildModule conventionalModule(String id, Path dir) {
        List<Path> sources = new ArrayList<>();
        LAYOUT.addConventionalSourcePaths(dir, sources);
        List<Path> generated = new ArrayList<>();
        LAYOUT.addGeneratedSourcePaths(dir, generated);
        List<Path> outputs = new ArrayList<>();
        LAYOUT.addConventionalOutputDirs(dir, outputs);
        return new BuildModule(id, dir, sources, generated, outputs, null, false);
    }

    /**
     * The classpath Maven wrote for each module, in module order, each entry once. Entries
     * that are the reactor's own modules are left out, since their sources are linked: a
     * module's directory, build directory or output directory, and a jar in a local
     * repository at the reactor module's coordinates. A module with no classpath file
     * contributes nothing; the importer reports it.
     */
    private static List<String> classpath(List<Module> reactor, Path classpathDir) {
        Set<Path> owned = new HashSet<>();
        for (Module module : reactor) {
            MavenPom pom = module.pom();
            Path dir = module.dir();
            Path buildDir = buildDirectory(module);
            owned.add(realOf(dir));
            owned.add(realOf(buildDir));
            owned.add(realOf(path(dir, pom.outputDirectory()).orElse(buildDir.resolve("classes"))));
            owned.add(realOf(path(dir, pom.testOutputDirectory()).orElse(buildDir.resolve("test-classes"))));
        }

        Set<String> entries = new LinkedHashSet<>();
        for (Module module : reactor) {
            Path file = classpathFile(classpathDir, module);
            if (!Files.isRegularFile(file)) continue;
            String content;
            try {
                content = Files.readString(file).trim();
            } catch (IOException e) {
                throw new UncheckedIOException("Maven's classpath file " + file.getFileName()
                    + " could not be read: " + e.getMessage(), e);
            }
            if (content.isEmpty()) continue;
            for (String entry : content.split(Pattern.quote(File.pathSeparator))) {
                if (entry.isBlank()) continue;
                Optional<Path> entryPath = path(module.dir(), entry.trim());
                if (entryPath.isEmpty()) continue;
                if (owned.contains(realOf(entryPath.get())) || isReactorArtifact(entryPath.get(), reactor)) {
                    continue;
                }
                entries.add(entry.trim());
            }
        }
        return List.copyOf(entries);
    }

    /** The file Maven writes {@code module}'s classpath to in {@code classpathDir}. */
    static Path classpathFile(Path classpathDir, Module module) {
        return classpathDir.resolve(module.pom().groupId() + "__" + module.pom().artifactId() + ".txt");
    }

    /**
     * True when {@code entry} is a reactor module's artifact in a local repository:
     * {@code <groupId path>/<artifactId>/<version>/<artifactId>-<version>...}.
     */
    private static boolean isReactorArtifact(Path entry, List<Module> reactor) {
        Path versionDir = entry.getParent();
        if (versionDir == null || versionDir.getParent() == null || entry.getFileName() == null) return false;
        Path artifactDir = versionDir.getParent();
        for (Module module : reactor) {
            MavenPom pom = module.pom();
            if (pom.groupId() == null || pom.artifactId() == null || pom.version() == null) continue;
            if (!pom.version().equals(versionDir.getFileName().toString())
                    || !pom.artifactId().equals(String.valueOf(artifactDir.getFileName()))
                    || !entry.getFileName().toString().startsWith(pom.artifactId() + "-" + pom.version())) {
                continue;
            }
            String[] group = pom.groupId().split("\\.");
            Path current = artifactDir.getParent();
            boolean matches = true;
            for (int i = group.length - 1; i >= 0 && matches; i--) {
                matches = current != null && group[i].equals(String.valueOf(current.getFileName()));
                current = current == null ? null : current.getParent();
            }
            if (matches) return true;
        }
        return false;
    }

    /**
     * The jars of every module's annotation processor paths, in module order, each once,
     * resolved in {@code localRepository}; a path that leaves out its version takes it from
     * the module's dependency management. A path with no version anywhere is named by an
     * {@link LoadWarning#APT_PROCESSOR_JARS_MISSING}. Jars are listed whether or not they
     * exist; the load reports the missing ones.
     */
    private static List<Path> processorJars(List<Module> reactor, Path localRepository, List<LoadWarning> warnings) {
        Set<Path> jars = new LinkedHashSet<>();
        List<String> unversioned = new ArrayList<>();
        for (Module module : reactor) {
            MavenPom pom = module.pom();
            for (MavenPom.Coordinates path : pom.processorPaths()) {
                String version = path.version() != null ? path.version()
                    : pom.managedVersions().get(path.groupId() + ":" + path.artifactId());
                if (version == null || version.contains("${")) {
                    unversioned.add(path.groupId() + ":" + path.artifactId() + " (" + pom.id() + ")");
                    continue;
                }
                jars.add(localRepository.resolve(path.groupId().replace('.', File.separatorChar))
                    .resolve(path.artifactId()).resolve(version)
                    .resolve(path.artifactId() + "-" + version + ".jar"));
            }
        }
        if (!unversioned.isEmpty()) {
            warnings.add(new LoadWarning(LoadWarning.APT_PROCESSOR_JARS_MISSING,
                "Annotation processor paths without a version, in the path or in dependency management, are not "
                    + "registered: " + String.join(", ", unversioned),
                "Give each annotation processor path a version, then reload the project."));
        }
        return List.copyOf(jars);
    }

    /** True when any module of {@code reactor} declares annotation processor paths. */
    static boolean declaresProcessors(List<Module> reactor) {
        return reactor.stream().anyMatch(module -> !module.pom().processorPaths().isEmpty());
    }

    // ========== Paths ==========

    /**
     * {@code value} as a path: against {@code dir} when relative; when absolute, and inside
     * {@code dir} however either is spelled (Maven reports its own spelling, which may differ
     * from the load's, as a symbolic link or a short name does), the same location under
     * {@code dir} as the load spells it, so files keep mapping to their source roots. Empty
     * when null, still an unresolved reference, or not a valid path, which is logged.
     */
    private static Optional<Path> path(Path dir, String value) {
        if (value == null || value.isBlank() || value.contains("${")) return Optional.empty();
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                return Optional.of(dir.resolve(path).normalize());
            }
            Path real = realOf(path);
            Path realDir = realOf(dir);
            return Optional.of(real.startsWith(realDir)
                ? dir.resolve(realDir.relativize(real)).normalize()
                : path.normalize());
        } catch (InvalidPathException e) {
            log.warn("Skipping '{}' declared for {}, which is not a valid path: {}", value, dir, e.getReason());
            return Optional.empty();
        }
    }

    /** {@code module}'s build directory: its pom's, or {@code target} under its directory. */
    private static Path buildDirectory(Module module) {
        return path(module.dir(), module.pom().buildDirectory()).orElse(module.dir().resolve("target"));
    }

    /**
     * The real path of {@code path}: its longest existing ancestor's real path, followed by
     * the rest of {@code path}, normalized; {@code path} normalized when no ancestor resolves.
     */
    static Path realOf(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) return absolute;
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException e) {
            return absolute;
        }
    }

    private static void addSubdirectories(Path parent, List<Path> into) {
        if (!Files.isDirectory(parent)) return;
        try (Stream<Path> children = Files.list(parent)) {
            children.filter(Files::isDirectory).sorted().forEach(into::add);
        } catch (IOException e) {
            throw new UncheckedIOException("The generated-source directory " + parent + " could not be listed: "
                + e.getMessage(), e);
        }
    }

    /** The default local repository a static read resolves processors in: {@code ~/.m2/repository}. */
    static Path defaultLocalRepository() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }
}
