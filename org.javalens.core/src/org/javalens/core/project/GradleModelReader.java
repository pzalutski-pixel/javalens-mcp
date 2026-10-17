package org.javalens.core.project;

import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds the {@link BuildModel} from the files {@link GradleImporter}'s init script wrote
 * into its model directory. Each line of a file is one record: its fields URL-encoded and
 * separated by single spaces, the first field naming the record.
 *
 * <ul>
 *   <li>{@code build-*.txt}, one per build of the invocation, written when the build's
 *       projects are loaded: {@code build <root dir> <true for the root build>}; then
 *       {@code settings <file>} when the build has a settings file;
 *       {@code project <path> <project dir> <build file>} per project, in Gradle's
 *       {@code allprojects} order; {@code included <name> <dir>} per included build, in
 *       order.</li>
 *   <li>{@code project-*.txt}, one per project: {@code project <build root dir> <path>};
 *       for a project with the {@code java-base} plugin, {@code level <sourceCompatibility>},
 *       then per source set, in Gradle's order, its {@code source} directories (java, then
 *       Kotlin), {@code generated} directories and {@code classes} directories, then
 *       {@code processor} files (the {@code annotationProcessor} configuration) and
 *       {@code classpath} files (the project's external artifacts).</li>
 * </ul>
 *
 * <p>Gradle reports canonical paths. A path under the root build's directory is rebased
 * onto the project root as the load names it, so a project reached through a symbolic link
 * or another spelling keeps its paths under that root.
 */
final class GradleModelReader {

    private static final Logger log = LoggerFactory.getLogger(GradleModelReader.class);

    private final Path projectRoot;

    /** The root build's directory as Gradle reports it. */
    private Path reportedRoot;

    private GradleModelReader(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /** One build's structure. */
    private record Build(Path rootDir, boolean root, Path settings, List<Project> projects, List<Included> included) {
    }

    /** One project of a build, as its structure lists it. */
    private record Project(String path, Path dir, Path buildFile) {
    }

    /** One build a build includes. */
    private record Included(String name, Path dir) {
    }

    /** Identifies a project across builds: its build's root directory and its path. */
    private record ProjectKey(Path buildRoot, String path) {
    }

    /** What a project's model task reported. Paths are as Gradle reports them. */
    private record Details(String level, List<Path> sources, List<Path> generated, List<Path> classes,
            List<Path> processors, List<Path> classpath) {
    }

    /** A build in module order, with the prefix its projects' module ids carry. */
    private record OrderedBuild(Build build, String idPrefix) {
    }

    /**
     * The model of the build at {@code projectRoot}, read from {@code modelDir}.
     *
     * <p>Modules are the root build's projects in Gradle's {@code allprojects} order, then
     * each included build's, in the order the builds include them. A root-build project's id
     * is its Gradle path; an included build named {@code N} contributes {@code :N} for its
     * root project and {@code :N} followed by the path for the others. Each module's source
     * and generated roots are the reported directories that exist, its output directories
     * every reported classes directory, and its declared level its
     * {@code sourceCompatibility}; then {@link ConventionalFolders} keeps the conventional
     * folders no module reports, and {@link DeclaredLevels} picks the analysis level.
     *
     * @throws IOException when the files cannot be read, a record is malformed, a structure
     *     path is not a valid path, no root build is reported, an included build reports no
     *     structure, or a project reports no details
     */
    static BuildModel read(Path projectRoot, Path modelDir) throws IOException {
        return new GradleModelReader(projectRoot).readModel(modelDir);
    }

    private BuildModel readModel(Path modelDir) throws IOException {
        Map<Path, Build> builds = new HashMap<>();
        Map<ProjectKey, Details> details = new HashMap<>();
        Build rootBuild = null;
        List<Path> files;
        try (Stream<Path> listing = Files.list(modelDir)) {
            files = listing.sorted().toList();
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            List<List<String>> records = records(file);
            if (name.startsWith("build-")) {
                Build build = build(file, records);
                builds.put(build.rootDir(), build);
                if (build.root()) {
                    rootBuild = build;
                }
            } else if (name.startsWith("project-")) {
                project(file, records, details);
            } else {
                throw new IOException(name + " is not a model file");
            }
        }
        if (rootBuild == null) {
            throw new IOException("no root build was reported");
        }
        reportedRoot = rootBuild.rootDir();

        List<OrderedBuild> order = buildOrder(rootBuild, builds);
        List<LoadWarning> warnings = new ArrayList<>();
        List<BuildModule> reported = new ArrayList<>();
        Map<String, Details> detailsById = new LinkedHashMap<>();
        for (OrderedBuild ordered : order) {
            for (Project project : ordered.build().projects()) {
                String id = moduleId(ordered.idPrefix(), project.path());
                Details reportedDetails = details.get(new ProjectKey(ordered.build().rootDir(), project.path()));
                if (reportedDetails == null) {
                    throw new IOException("project '" + id + "' reported no details");
                }
                detailsById.put(id, reportedDetails);
                reported.add(new BuildModule(id, rebase(project.dir()),
                    existingDirectories(reportedDetails.sources()),
                    existingDirectories(reportedDetails.generated()),
                    rebased(reportedDetails.classes()),
                    reportedDetails.level(), true));
            }
        }
        List<BuildModule> modules = ConventionalFolders.keepUndeclared(projectRoot, reported, warnings);
        String analysisLevel = DeclaredLevels.analysisLevel(modules, warnings);

        Set<Path> classpath = new LinkedHashSet<>();
        Set<Path> processorJars = new LinkedHashSet<>();
        for (Details moduleDetails : detailsById.values()) {
            classpath.addAll(rebased(moduleDetails.classpath()));
            for (Path processor : rebased(moduleDetails.processors())) {
                if (Files.exists(processor)) {
                    processorJars.add(processor);
                }
            }
        }

        return new BuildModel(BuildSystem.GRADLE, Provenance.BUILD_TOOL, modules,
            classpath.stream().map(Path::toString).toList(), List.copyOf(processorJars), analysisLevel,
            inputs(order, modules), warnings);
    }

    /**
     * The root build, then each build it includes, in order, then each build those include
     * that is not yet listed, and so on. An included build named {@code N} prefixes its
     * modules' ids with {@code :N}.
     */
    private static List<OrderedBuild> buildOrder(Build rootBuild, Map<Path, Build> builds) throws IOException {
        List<OrderedBuild> order = new ArrayList<>();
        Set<Path> listed = new HashSet<>();
        order.add(new OrderedBuild(rootBuild, ""));
        listed.add(rootBuild.rootDir());
        for (int i = 0; i < order.size(); i++) {
            for (Included included : order.get(i).build().included()) {
                Build build = builds.get(included.dir());
                if (build == null) {
                    throw new IOException("included build '" + included.name() + "' reported no structure");
                }
                if (listed.add(build.rootDir())) {
                    order.add(new OrderedBuild(build, ":" + included.name()));
                }
            }
        }
        return order;
    }

    private static String moduleId(String idPrefix, String projectPath) {
        if (idPrefix.isEmpty()) {
            return projectPath;
        }
        return ":".equals(projectPath) ? idPrefix : idPrefix + projectPath;
    }

    /**
     * The files and directories the build reads: every settings file, then every module's
     * build file, that exists, then every other Gradle script ({@code *.gradle},
     * {@code *.gradle.kts}) under each build's root, which covers scripts applied with
     * {@code apply from}; then, whether or not they exist, {@code gradle.properties} in
     * each module's directory (which covers each build's root), {@code gradle/libs.versions.toml}
     * in each build's root, and the root build's {@code gradle/wrapper/gradle-wrapper.properties};
     * and each build root's {@code buildSrc} directory. Builds and modules are in module
     * order; a path listed twice is kept at its first position.
     */
    private BuildInputs inputs(List<OrderedBuild> order, List<BuildModule> modules) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (OrderedBuild ordered : order) {
            Path settings = ordered.build().settings();
            if (settings != null && Files.isRegularFile(rebase(settings))) {
                files.add(rebase(settings));
            }
        }
        for (OrderedBuild ordered : order) {
            for (Project project : ordered.build().projects()) {
                if (Files.isRegularFile(rebase(project.buildFile()))) {
                    files.add(rebase(project.buildFile()));
                }
            }
        }
        for (OrderedBuild ordered : order) {
            files.addAll(gradleScripts(rebase(ordered.build().rootDir())));
        }
        for (BuildModule module : modules) {
            files.add(module.dir().resolve("gradle.properties"));
        }
        List<Path> buildRoots = order.stream().map(ordered -> rebase(ordered.build().rootDir())).toList();
        for (Path buildRoot : buildRoots) {
            files.add(buildRoot.resolve("gradle").resolve("libs.versions.toml"));
        }
        files.add(buildRoots.get(0).resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties"));

        List<Path> directories = buildRoots.stream().map(buildRoot -> buildRoot.resolve("buildSrc")).toList();
        return new BuildInputs(List.copyOf(files), directories);
    }

    /** Directories a Gradle script walk skips: build output, Gradle's state, version control. */
    private static final Set<String> SCRIPT_WALK_SKIPS = Set.of("build", ".gradle", ".git", "node_modules", "out",
        "target");

    /** Every {@code *.gradle} and {@code *.gradle.kts} file under {@code buildRoot}, in path order. */
    private static List<Path> gradleScripts(Path buildRoot) throws IOException {
        List<Path> scripts = new ArrayList<>();
        java.nio.file.Files.walkFileTree(buildRoot, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return !dir.equals(buildRoot) && (SCRIPT_WALK_SKIPS.contains(name) || name.startsWith("bazel-"))
                    ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (attrs.isRegularFile() && (name.endsWith(".gradle") || name.endsWith(".gradle.kts"))) {
                    scripts.add(file.normalize());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        scripts.sort(null);
        return scripts;
    }

    /** {@code paths} rebased, keeping the directories that exist, each once. */
    private List<Path> existingDirectories(List<Path> paths) {
        return rebased(paths).stream().filter(Files::isDirectory).toList();
    }

    /** {@code paths} rebased, each once, in order. */
    private List<Path> rebased(List<Path> paths) {
        Set<Path> rebased = new LinkedHashSet<>();
        for (Path path : paths) {
            rebased.add(rebase(path));
        }
        return List.copyOf(rebased);
    }

    /**
     * {@code reported} under the project root as the load names it when it lies under the
     * root build's reported directory; otherwise {@code reported} itself, normalized.
     */
    private Path rebase(Path reported) {
        Path normalized = reported.normalize();
        if (!normalized.startsWith(reportedRoot)) {
            return normalized;
        }
        return projectRoot.resolve(reportedRoot.relativize(normalized)).normalize();
    }

    // ========== Files and records ==========

    /** Every line of {@code file} as its decoded fields. */
    private static List<List<String>> records(Path file) throws IOException {
        List<List<String>> records = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            List<String> fields = new ArrayList<>();
            for (String field : lines.get(i).split(" ", -1)) {
                try {
                    fields.add(URLDecoder.decode(field, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    throw malformed(file, i + 1);
                }
            }
            records.add(fields);
        }
        return records;
    }

    private static Build build(Path file, List<List<String>> records) throws IOException {
        if (records.isEmpty() || !isRecord(records.get(0), "build", 3)) {
            throw malformed(file, 1);
        }
        Path rootDir = structurePath(file, records.get(0).get(1));
        boolean root = Boolean.parseBoolean(records.get(0).get(2));
        Path settings = null;
        List<Project> projects = new ArrayList<>();
        List<Included> included = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (isRecord(record, "settings", 2)) {
                settings = structurePath(file, record.get(1));
            } else if (isRecord(record, "project", 4)) {
                projects.add(new Project(record.get(1), structurePath(file, record.get(2)),
                    structurePath(file, record.get(3))));
            } else if (isRecord(record, "included", 3)) {
                included.add(new Included(record.get(1), structurePath(file, record.get(2))));
            } else {
                throw malformed(file, i + 1);
            }
        }
        return new Build(rootDir, root, settings, projects, included);
    }

    private static void project(Path file, List<List<String>> records, Map<ProjectKey, Details> details)
            throws IOException {
        if (records.isEmpty() || !isRecord(records.get(0), "project", 3)) {
            throw malformed(file, 1);
        }
        ProjectKey key = new ProjectKey(structurePath(file, records.get(0).get(1)), records.get(0).get(2));
        String level = null;
        Map<String, List<Path>> paths = new HashMap<>();
        for (String kind : List.of("source", "generated", "classes", "processor", "classpath")) {
            paths.put(kind, new ArrayList<>());
        }
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (isRecord(record, "level", 2)) {
                level = record.get(1);
            } else if (record.size() == 2 && paths.containsKey(record.get(0))) {
                reportedPath(record.get(1)).ifPresent(paths.get(record.get(0))::add);
            } else {
                throw malformed(file, i + 1);
            }
        }
        details.put(key, new Details(level, paths.get("source"), paths.get("generated"), paths.get("classes"),
            paths.get("processor"), paths.get("classpath")));
    }

    private static boolean isRecord(List<String> record, String kind, int fieldCount) {
        return record.size() == fieldCount && kind.equals(record.get(0));
    }

    /** A path of the build's structure; one that is not a valid path makes the model unreadable. */
    private static Path structurePath(Path file, String value) throws IOException {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new IOException(file.getFileName() + " names '" + value + "', which is not a valid path: "
                + e.getReason(), e);
        }
    }

    /** A path a project reports; one that is not a valid path is skipped and logged at warn. */
    private static Optional<Path> reportedPath(String value) {
        try {
            return Optional.of(Path.of(value));
        } catch (InvalidPathException e) {
            log.warn("Skipping '{}' that Gradle reported, which is not a valid path: {}", value, e.getReason());
            return Optional.empty();
        }
    }

    private static IOException malformed(Path file, int line) {
        return new IOException(file.getFileName() + " line " + line + " is malformed");
    }
}
