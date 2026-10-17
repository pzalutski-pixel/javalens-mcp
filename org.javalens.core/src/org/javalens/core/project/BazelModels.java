package org.javalens.core.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Builds a Bazel project's {@link BuildModel} from what Bazel reports or, for a static read,
 * from the BUILD files themselves.
 *
 * <p>Modules are the main repository's packages that hold Java targets, in the order Bazel
 * lists the targets, each identified by its package label ({@code //model}, {@code //} for
 * the root package). A package's source roots are the directories its targets' Java sources
 * sit in, less the package each source declares ({@code model/src/main/java} for a source of
 * package {@code com.example.model} in {@code model/src/main/java/com/example/model}); its
 * output is each target's compiled jar; its declared level is the highest level its targets'
 * javac actions compile at, each the last {@code -source}, {@code --release} or
 * {@code --release=N} among the action's javac options, after the toolchain's defaults and
 * {@code .bazelrc}'s flags.
 *
 * <p>The classpath is the actions' {@code --classpath} entries, resolved against the
 * execution root, without the main repository's own target outputs, whose sources are linked;
 * processor jars are the actions' {@code --processorpath} entries.
 */
final class BazelModels {

    private static final Logger log = LoggerFactory.getLogger(BazelModels.class);

    private static final LinkedFolderConfigurator LAYOUT = new LinkedFolderConfigurator();

    /** Root files every Bazel build reads, listed as inputs whether or not they exist. */
    private static final List<String> ROOT_INPUTS = List.of(
        "MODULE.bazel", "WORKSPACE", "WORKSPACE.bazel", ".bazelrc", ".bazelversion");

    /** Directories skipped by the static read's package walk, besides Bazel's own {@code bazel-*} trees. */
    private static final List<String> IGNORED_DIRS = List.of(
        ".git", ".svn", ".mvn", ".gradle", ".settings", ".metadata",
        "node_modules", "target", "build", "bin", "out", "dist");

    private static final List<String> BUILD_FILE_NAMES = List.of("BUILD.bazel", "BUILD");

    private BazelModels() {
    }

    /** A main-repository Java target: its label, its package and its source labels. */
    private record Target(String label, String pkg, List<String> sources) {
    }

    /** What one javac action reports. Paths are relative to the execution root. */
    private record Action(String level, List<String> classpath, List<String> processorPath, String output) {
    }

    // ========== Reported model ==========

    /**
     * The model of the answers Bazel wrote: {@code targets} ({@code query --output=xml} of the
     * Java targets), {@code buildFiles} ({@code query buildfiles} labels) and {@code actions}
     * ({@code aquery --output=jsonproto} of the javac actions), whose paths resolve against
     * {@code executionRoot}.
     */
    static BuildModel reportedModel(Path projectRoot, Path targets, Path buildFiles, Path actions, Path executionRoot)
            throws IOException {
        List<Target> javaTargets = readTargets(targets);
        Map<String, Action> actionsByLabel = readActions(actions);

        Map<String, List<Target>> byPackage = new LinkedHashMap<>();
        for (Target target : javaTargets) {
            byPackage.computeIfAbsent(target.pkg(), key -> new ArrayList<>()).add(target);
        }

        List<LoadWarning> warnings = new ArrayList<>();
        List<BuildModule> reported = new ArrayList<>();
        Set<String> classpath = new LinkedHashSet<>();
        Set<Path> processors = new LinkedHashSet<>();
        List<String> notBuilt = new ArrayList<>();
        for (Map.Entry<String, List<Target>> pkg : byPackage.entrySet()) {
            Path dir = packageDir(projectRoot, pkg.getKey());
            Set<Path> sourceRoots = new LinkedHashSet<>();
            List<Path> outputs = new ArrayList<>();
            List<String> levels = new ArrayList<>();
            for (Target target : pkg.getValue()) {
                for (String source : target.sources()) {
                    sourceFile(projectRoot, source).flatMap(BazelModels::sourceRoot).ifPresent(sourceRoots::add);
                }
                Action action = actionsByLabel.get(target.label());
                if (action == null) continue;
                if (action.level() != null) levels.add(action.level());
                if (action.output() != null) outputs.add(resolve(executionRoot, action.output()));
                for (String entry : action.classpath()) {
                    if (isOwnOutput(entry)) continue;
                    Path jar = resolve(executionRoot, entry);
                    if (!Files.exists(jar)) {
                        if (!notBuilt.contains(entry)) notBuilt.add(entry);
                        continue;
                    }
                    classpath.add(jar.toString());
                }
                for (String entry : action.processorPath()) {
                    processors.add(resolve(executionRoot, entry));
                }
            }
            reported.add(new BuildModule(packageLabel(pkg.getKey()), dir, List.copyOf(sourceRoots), List.of(),
                outputs, highest(levels), true));
        }

        List<BuildModule> modules = ConventionalFolders.keepUndeclared(projectRoot, reported, warnings);
        String level = DeclaredLevels.analysisLevel(modules, warnings);
        if (!notBuilt.isEmpty()) {
            warnings.add(new LoadWarning(LoadWarning.BAZEL_NOT_BUILT,
                notBuilt.size() + " classpath jar(s) the javac actions name are not built, so their types do not "
                    + "resolve: " + String.join(", ", notBuilt.subList(0, Math.min(5, notBuilt.size())))
                    + (notBuilt.size() > 5 ? ", ..." : ""),
                "Run 'bazel build //...' (or the relevant target set) in the project root, then reload the project."));
        }
        return new BuildModel(BuildSystem.BAZEL, Provenance.BUILD_TOOL, modules, List.copyOf(classpath),
            List.copyOf(processors), level, inputs(projectRoot, readBuildFiles(projectRoot, buildFiles)), warnings);
    }

    /** The main repository's Java targets in {@code targets}, in Bazel's order. */
    private static List<Target> readTargets(Path targets) throws IOException {
        Element query = SecureXml.read(targets);
        List<Target> javaTargets = new ArrayList<>();
        for (Element rule : SecureXml.children(query, "rule")) {
            String label = rule.getAttribute("name");
            Optional<String> pkg = mainPackage(label);
            if (pkg.isEmpty()) continue;
            List<String> sources = new ArrayList<>();
            for (Element list : SecureXml.children(rule, "list")) {
                if (!"srcs".equals(list.getAttribute("name"))) continue;
                for (Element value : SecureXml.children(list, "label")) {
                    sources.add(value.getAttribute("value"));
                }
            }
            javaTargets.add(new Target(label, pkg.get(), sources));
        }
        return javaTargets;
    }

    /** Each javac action in {@code actions}, by its target's label. */
    private static Map<String, Action> readActions(Path actions) throws IOException {
        JsonNode root = new ObjectMapper().readTree(actions.toFile());
        Map<Integer, String> labels = new HashMap<>();
        for (JsonNode target : root.path("targets")) {
            labels.put(target.path("id").asInt(), target.path("label").asText());
        }
        Map<String, Action> byLabel = new LinkedHashMap<>();
        for (JsonNode action : root.path("actions")) {
            String label = labels.get(action.path("targetId").asInt());
            if (label == null || byLabel.containsKey(label)) continue;
            List<String> arguments = new ArrayList<>();
            action.path("arguments").forEach(argument -> arguments.add(argument.asText()));
            byLabel.put(label, new Action(level(arguments), values(arguments, "--classpath"),
                values(arguments, "--processorpath"), values(arguments, "--output").stream().findFirst().orElse(null)));
        }
        return byLabel;
    }

    /**
     * The level a javac action compiles at: the last {@code -source N}, {@code --release N} or
     * {@code --release=N} among its javac options, which run from {@code --javacopts} to the
     * next {@code --}. Null when none is given.
     */
    private static String level(List<String> arguments) {
        int start = arguments.indexOf("--javacopts");
        if (start < 0) return null;
        String level = null;
        for (int i = start + 1; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals("--")) break;
            if ((argument.equals("-source") || argument.equals("--release")) && i + 1 < arguments.size()) {
                level = arguments.get(++i);
            } else if (argument.startsWith("--release=")) {
                level = argument.substring("--release=".length());
            }
        }
        return level;
    }

    /** The values after {@code flag}, up to the next argument that starts with {@code --}. */
    private static List<String> values(List<String> arguments, String flag) {
        List<String> values = new ArrayList<>();
        int start = arguments.indexOf(flag);
        if (start < 0) return values;
        for (int i = start + 1; i < arguments.size() && !arguments.get(i).startsWith("--"); i++) {
            values.add(arguments.get(i));
        }
        return values;
    }

    /**
     * True when {@code entry}, relative to the execution root, is a main-repository target's
     * output: {@code bazel-out/<configuration>/bin/<path>} outside {@code bin/external}.
     */
    private static boolean isOwnOutput(String entry) {
        String[] segments = entry.replace('\\', '/').split("/");
        return segments.length >= 4 && segments[0].equals("bazel-out") && segments[2].equals("bin")
            && !segments[3].equals("external");
    }

    /** The main-repository build files of {@code buildFiles}, as files under the project root. */
    private static List<Path> readBuildFiles(Path projectRoot, Path buildFiles) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String line : Files.readAllLines(buildFiles, StandardCharsets.UTF_8)) {
            String label = line.trim();
            int colon = label.indexOf(':');
            Optional<String> pkg = mainPackage(label);
            if (pkg.isEmpty() || colon < 0) continue;
            try {
                files.add(packageDir(projectRoot, pkg.get()).resolve(label.substring(colon + 1)).normalize());
            } catch (InvalidPathException e) {
                log.warn("Skipping build file label '{}', which is not a valid path: {}", label, e.getReason());
            }
        }
        return files;
    }

    /** The build files, then the root files every build reads, each once. */
    private static BuildInputs inputs(Path projectRoot, List<Path> buildFiles) {
        Set<Path> files = new LinkedHashSet<>(buildFiles);
        for (String name : ROOT_INPUTS) {
            files.add(projectRoot.resolve(name));
        }
        return new BuildInputs(List.copyOf(files), List.of());
    }

    /**
     * The package of a main-repository label: {@code //a/b:c} is {@code a/b}, {@code //:c} the
     * root package {@code ""}; empty for a label of another repository.
     */
    private static Optional<String> mainPackage(String label) {
        String main = label.startsWith("@@//") ? label.substring(2) : label.startsWith("@//") ? label.substring(1) : label;
        if (!main.startsWith("//")) return Optional.empty();
        int colon = main.indexOf(':');
        return Optional.of(colon < 0 ? main.substring(2) : main.substring(2, colon));
    }

    private static String packageLabel(String pkg) {
        return "//" + pkg;
    }

    private static Path packageDir(Path projectRoot, String pkg) {
        return pkg.isEmpty() ? projectRoot : projectRoot.resolve(pkg).normalize();
    }

    /** The file a main-repository source label names, when it is a Java file that exists. */
    private static Optional<Path> sourceFile(Path projectRoot, String label) {
        Optional<String> pkg = mainPackage(label);
        int colon = label.indexOf(':');
        if (pkg.isEmpty() || colon < 0 || !label.endsWith(".java")) return Optional.empty();
        try {
            Path file = packageDir(projectRoot, pkg.get()).resolve(label.substring(colon + 1)).normalize();
            return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * The source root of {@code file}: its directory less the trailing directories that
     * spell the package it declares, read as code so comments before the declaration are
     * skipped. A file whose directory does not end with its package's path is its own
     * directory's; a file in the default package is too.
     */
    private static Optional<Path> sourceRoot(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The source " + file + " could not be read: " + e.getMessage(), e);
        }
        List<BuildLexer.Token> tokens = BuildLexer.groovy(content);
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).isIdentifier("package")) continue;
            for (int j = i + 1; j < tokens.size() && tokens.get(j).kind() == BuildLexer.Kind.IDENTIFIER; j += 2) {
                segments.add(tokens.get(j).text());
                if (j + 1 >= tokens.size() || !tokens.get(j + 1).isSymbol('.')) break;
            }
            break;
        }
        Path dir = file.getParent();
        Path root = dir;
        for (int i = segments.size() - 1; i >= 0 && root != null; i--) {
            if (root.getFileName() == null || !segments.get(i).equals(root.getFileName().toString())) {
                return Optional.of(dir);
            }
            root = root.getParent();
        }
        return Optional.ofNullable(root);
    }

    private static Path resolve(Path executionRoot, String entry) {
        Path path = executionRoot.resolve(entry).normalize();
        try {
            return Files.exists(path) ? path.toRealPath() : path;
        } catch (IOException e) {
            return path;
        }
    }

    /** The highest of {@code levels} by feature number, or null when there is none. */
    private static String highest(List<String> levels) {
        return levels.stream()
            .max(Comparator.comparingInt(level -> DeclaredLevels.featureNumber(level).orElse(-1)))
            .orElse(null);
    }

    // ========== Static read ==========

    /**
     * The model read from the BUILD files: the project root and every package under it (a
     * directory holding a {@code BUILD} or {@code BUILD.bazel} file, outside Bazel's
     * {@code bazel-*} trees), each laid out by convention; when no package holds a
     * conventional layout, each package directory holding {@code .java} files is its own
     * source root. A package's declared level is the highest of its {@code javacopts} list
     * literals' levels, each the last {@code -source}, {@code --release} or
     * {@code --release=N} in the list, read as code so comments and strings elsewhere are
     * not. The classpath is every jar in {@code bazel-bin} and {@code bazel-out} outside the
     * main repository's own target outputs, and the processor jars those declaring an
     * annotation processor. {@code trailing} warnings follow the model's own.
     */
    static BuildModel staticModel(Path projectRoot, List<LoadWarning> trailing) {
        List<Path> packages = packages(projectRoot);
        List<BuildModule> modules = new ArrayList<>();
        List<Path> discovered = new ArrayList<>();
        Map<Path, String> levels = new HashMap<>();
        for (Path dir : packages) {
            levels.put(dir, packageLevel(dir));
        }
        if (!packages.contains(projectRoot)) {
            modules.add(probe(projectRoot, projectRoot, discovered, null));
        }
        for (Path dir : packages) {
            modules.add(probe(projectRoot, dir, discovered, levels.get(dir)));
        }
        if (discovered.isEmpty()) {
            // No package has a conventional layout: each package holding .java files directly
            // is its own source root.
            for (int i = 0; i < modules.size(); i++) {
                BuildModule module = modules.get(i);
                if (packages.contains(module.dir()) && containsJavaFiles(module.dir())) {
                    modules.set(i, new BuildModule(module.id(), module.dir(), List.of(module.dir()), List.of(),
                        List.of(), module.declaredLevel(), false));
                }
            }
        }

        List<LoadWarning> warnings = new ArrayList<>();
        String level = DeclaredLevels.analysisLevel(modules, warnings);
        List<Path> jars = new ArrayList<>();
        Map<Path, List<Path>> ownJars = new LinkedHashMap<>();
        outputJars(projectRoot, jars, ownJars);
        modules = withOwnOutputs(projectRoot, modules, ownJars);
        if (jars.isEmpty() && ownJars.isEmpty()) {
            boolean noRoots = !Files.exists(projectRoot.resolve("bazel-bin")) && !Files.exists(projectRoot.resolve("bazel-out"));
            warnings.add(new LoadWarning(LoadWarning.BAZEL_NOT_BUILT,
                (noRoots
                    ? "Neither bazel-bin nor bazel-out exists in " + projectRoot
                    : "bazel-bin/bazel-out exist in " + projectRoot + " but contain no jars (likely bazel clean was "
                        + "just run)")
                    + "; classpath will be empty",
                "Run 'bazel build //...' (or the relevant target set) in the project root before loading. JavaLens "
                    + "reads dependency jars from Bazel's build output, not from the source tree."));
        }
        List<Path> processors = jars.stream().filter(BazelModels::declaresProcessor).toList();
        warnings.addAll(trailing);

        List<Path> buildFiles = new ArrayList<>();
        for (Path dir : packages) {
            buildFile(dir).ifPresent(buildFiles::add);
        }
        return new BuildModel(BuildSystem.BAZEL, Provenance.STATIC_READ, modules,
            jars.stream().map(Path::toString).toList(), processors, level, inputs(projectRoot, buildFiles), warnings);
    }

    private static BuildModule probe(Path projectRoot, Path dir, List<Path> discovered, String level) {
        int sourcesStart = discovered.size();
        LAYOUT.addConventionalSourcePaths(dir, discovered);
        int generatedStart = discovered.size();
        LAYOUT.addGeneratedSourcePaths(dir, discovered);
        return new BuildModule(id(projectRoot, dir), dir,
            List.copyOf(discovered.subList(sourcesStart, generatedStart)),
            List.copyOf(discovered.subList(generatedStart, discovered.size())), List.of(), level, false);
    }

    private static String id(Path projectRoot, Path dir) {
        String relative = ProjectPaths.relativeToRoot(projectRoot, dir);
        return Files.exists(dir.resolve("BUILD")) || Files.exists(dir.resolve("BUILD.bazel"))
            ? packageLabel(relative.equals(".") ? "" : relative) : relative;
    }

    /** Every package directory under the project root, in path order. */
    private static List<Path> packages(Path projectRoot) {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk.filter(Files::isDirectory)
                .filter(dir -> !isIgnored(projectRoot, dir))
                .filter(dir -> buildFile(dir).isPresent())
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("The project tree could not be walked for BUILD files: " + e.getMessage(), e);
        }
    }

    private static Optional<Path> buildFile(Path dir) {
        return BUILD_FILE_NAMES.stream().map(dir::resolve).filter(Files::isRegularFile).findFirst();
    }

    /**
     * True when {@code dir} is inside Bazel's output trees ({@code bazel-*} at the root) or
     * inside, or is, a directory of {@link #IGNORED_DIRS}.
     */
    private static boolean isIgnored(Path projectRoot, Path dir) {
        if (dir.equals(projectRoot)) return false;
        Path relative = projectRoot.relativize(dir);
        if (relative.getName(0).toString().startsWith("bazel-")) return true;
        for (Path segment : relative) {
            if (IGNORED_DIRS.contains(segment.toString())) return true;
        }
        return false;
    }

    /**
     * The highest level the {@code javacopts} list literals of {@code dir}'s BUILD file give,
     * each the last {@code -source N}, {@code --release N} or {@code --release=N} in its list;
     * null when none gives one.
     */
    private static String packageLevel(Path dir) {
        Optional<Path> file = buildFile(dir);
        if (file.isEmpty()) return null;
        String content;
        try {
            content = Files.readString(file.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The BUILD file " + file.get() + " could not be read: " + e.getMessage(), e);
        }
        List<BuildLexer.Token> tokens = BuildLexer.starlark(content);
        List<String> levels = new ArrayList<>();
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (!tokens.get(i).isIdentifier("javacopts") || !tokens.get(i + 1).isSymbol('=')
                    || !tokens.get(i + 2).isSymbol('[')) {
                continue;
            }
            List<String> options = new ArrayList<>();
            int j = i + 3;
            for (; j < tokens.size() && !tokens.get(j).isSymbol(']'); j++) {
                if (tokens.get(j).kind() == BuildLexer.Kind.STRING) options.add(tokens.get(j).text());
            }
            String level = null;
            for (int k = 0; k < options.size(); k++) {
                String option = options.get(k);
                if ((option.equals("-source") || option.equals("--release")) && k + 1 < options.size()) {
                    level = options.get(++k);
                } else if (option.startsWith("--release=")) {
                    level = option.substring("--release=".length());
                }
            }
            if (level != null) levels.add(level);
            i = j;
        }
        return highest(levels);
    }

    /**
     * {@code modules}, each with the own target jars of its package as its output, so the
     * load's compiled-output rule keeps a jar only when it holds classes with no linked
     * source. A jar whose package is no module's is the first module's output.
     */
    private static List<BuildModule> withOwnOutputs(Path projectRoot, List<BuildModule> modules,
            Map<Path, List<Path>> ownJars) {
        if (modules.isEmpty() || ownJars.isEmpty()) return modules;
        Map<Path, List<Path>> outputs = new LinkedHashMap<>();
        for (Map.Entry<Path, List<Path>> pkg : ownJars.entrySet()) {
            Path dir = projectRoot.resolve(pkg.getKey()).normalize();
            Path owner = modules.stream().map(BuildModule::dir).filter(dir::equals).findFirst()
                .orElse(modules.get(0).dir());
            outputs.computeIfAbsent(owner, key -> new ArrayList<>()).addAll(pkg.getValue());
        }
        List<BuildModule> result = new ArrayList<>();
        Set<Path> assigned = new HashSet<>();
        for (BuildModule module : modules) {
            List<Path> moduleOutputs = new ArrayList<>(module.outputDirs());
            if (assigned.add(module.dir())) {
                moduleOutputs.addAll(outputs.getOrDefault(module.dir(), List.of()));
            }
            result.add(new BuildModule(module.id(), module.dir(), module.sourceRoots(), module.generatedSourceRoots(),
                moduleOutputs, module.declaredLevel(), module.evaluated()));
        }
        return result;
    }

    /**
     * Collect every jar in {@code bazel-bin} and {@code bazel-out}, by real path, each once:
     * the main repository's own target outputs ({@code <configuration>/bin/<package>/...}
     * outside {@code bin/external}) into {@code ownJars} by package path, every other jar into
     * {@code jars}. {@code bazel-bin} is a link into {@code bazel-out}, so roots are compared
     * by real path and a root inside another is walked once.
     */
    private static void outputJars(Path projectRoot, List<Path> jars, Map<Path, List<Path>> ownJars) {
        List<Path> roots = new ArrayList<>();
        Path outRoot = null;
        for (String name : List.of("bazel-bin", "bazel-out")) {
            Path root = projectRoot.resolve(name);
            if (!Files.exists(root)) continue;
            try {
                Path real = root.toRealPath();
                roots.add(real);
                if (name.equals("bazel-out")) outRoot = real;
            } catch (IOException e) {
                throw new UncheckedIOException("Bazel's output tree " + root + " could not be resolved: "
                    + e.getMessage(), e);
            }
        }
        Set<Path> seen = new LinkedHashSet<>();
        for (Path root : roots) {
            if (roots.stream().anyMatch(other -> !other.equals(root) && root.startsWith(other))) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path jar : walk.filter(path -> path.toString().endsWith(".jar")).filter(Files::isRegularFile)
                        .toList()) {
                    Path real = jar.toRealPath();
                    if (!seen.add(real)) continue;
                    String relative = outRoot != null && real.startsWith(outRoot)
                        ? "bazel-out/" + outRoot.relativize(real).toString().replace('\\', '/') : null;
                    if (relative != null && isOwnOutput(relative)) {
                        // bazel-out/<configuration>/bin/<package>/<jar>
                        Path inBin = Path.of(relative).subpath(3, Path.of(relative).getNameCount());
                        Path pkg = inBin.getParent() == null ? Path.of("") : inBin.getParent();
                        ownJars.computeIfAbsent(pkg, key -> new ArrayList<>()).add(real);
                    } else {
                        jars.add(real);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Bazel's output tree " + root + " could not be walked: "
                    + e.getMessage(), e);
            }
        }
    }

    private static boolean declaresProcessor(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getEntry("META-INF/services/javax.annotation.processing.Processor") != null;
        } catch (IOException e) {
            log.warn("Could not read jar {} while scanning for annotation processors: {}", jar, e.getMessage());
            return false;
        }
    }

    private static boolean containsJavaFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(path -> path.toString().endsWith(".java"));
        } catch (IOException e) {
            throw new UncheckedIOException("The package " + dir + " could not be listed: " + e.getMessage(), e);
        }
    }
}
