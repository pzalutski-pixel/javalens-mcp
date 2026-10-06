package org.javalens.core.project;

import org.eclipse.jdt.apt.core.util.AptConfig;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the build-model seam: a load resolves the importer's {@link BuildModel} exactly
 * once and derives source folders, file count, packages, classpath, compiler level, APT
 * and warnings from that model alone. The service then exposes that model and the level it
 * applied.
 *
 * <p>The orchestrator tests register a {@link ModelStub} for {@link BuildSystem#UNKNOWN}
 * (the temp projects carry no build file). The stub's per-concern methods answer nothing,
 * so everything the load reports must come from {@link BuildSystemImporter#resolve}. Where
 * source roots are pinned, the temp project also holds a conventional {@code src/main/java}
 * the model does not declare, so layout probing by the orchestrator would be visible.
 *
 * <p>The resolve tests pin the models the plain-Java and Bazel importers compose from
 * their per-concern methods.
 */
class BuildModelSeamTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    // ========== Orchestrator derives the load from the model ==========

    @Test
    @DisplayName("Load links exactly the model's source root, applies its level, counts and lists packages from it, and resolves once")
    void loadDerivesFoldersLevelCountAndPackagesFromModel() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-custom-root"));
        Path customJava = root.resolve("custom/java");
        writeSource(customJava, "p", "P");
        // Conventional layout the model does not declare: two files in package q.
        Path conventional = root.resolve("src/main/java");
        writeSource(conventional, "q", "Q1");
        writeSource(conventional, "q", "Q2");

        ModelStub stub = new ModelStub(model(BuildSystem.UNKNOWN, "17",
            List.of(module(".", root, List.of(customJava), List.of())),
            List.of(), List.of(), List.of()));
        JdtServiceImpl service = load(root, stub);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertAll(
            () -> assertEquals(List.of(customJava), snapshot.sourceFolders(), "linked source folders"),
            () -> assertEquals("17", snapshot.compilerSource(), "COMPILER_SOURCE"),
            () -> assertEquals(1, service.getSourceFileCount(), "source file count"),
            () -> assertEquals(List.of("p"), service.getPackages(), "packages"),
            () -> assertEquals(1, stub.resolveCalls, "resolve() calls for the whole load"),
            () -> assertEquals(List.of(), service.getWarnings(), "warnings"));
    }

    @Test
    @DisplayName("Source folders follow module order, each module's source roots before its generated roots")
    void sourceFoldersFollowModuleOrder() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-order"));
        Path alphaMain = root.resolve("alpha/main");
        Path alphaTest = root.resolve("alpha/test");
        Path alphaGen = root.resolve("alpha/gen");
        Path betaMain = root.resolve("beta/main");
        Path betaGen = root.resolve("beta/gen");
        writeSource(alphaMain, "a", "A");
        writeSource(alphaTest, "a", "ATest");
        writeSource(alphaGen, "g", "G");
        writeSource(betaMain, "b", "B");
        writeSource(betaGen, "b", "BGen");
        writeSource(root.resolve("src/main/java"), "q", "Q");

        ModelStub stub = new ModelStub(model(BuildSystem.UNKNOWN, "17",
            List.of(
                module("alpha", root.resolve("alpha"), List.of(alphaMain, alphaTest), List.of(alphaGen)),
                module("beta", root.resolve("beta"), List.of(betaMain), List.of(betaGen))),
            List.of(), List.of(), List.of()));
        JdtServiceImpl service = load(root, stub);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        // Linked-folder names are "src-<index>-<root-relative path>", so the index pins the order.
        List<String> linkedFolderNames = Arrays.stream(service.getJavaProject().getRawClasspath())
            .filter(entry -> entry.getEntryKind() == IClasspathEntry.CPE_SOURCE)
            .map(entry -> entry.getPath().lastSegment())
            .toList();
        assertAll(
            () -> assertEquals(List.of(alphaMain, alphaTest, alphaGen, betaMain, betaGen),
                snapshot.sourceFolders(), "linked source folders"),
            () -> assertEquals(List.of("src-0-alpha-main", "src-1-alpha-test", "src-2-alpha-gen",
                "src-3-beta-main", "src-4-beta-gen"), linkedFolderNames, "linked folder names"),
            () -> assertEquals(5, service.getSourceFileCount(), "source file count"),
            () -> assertEquals(List.of("a", "g", "b"), service.getPackages(), "packages, first-seen order"),
            () -> assertEquals(1, stub.resolveCalls, "resolve() calls for the whole load"));
    }

    @Test
    @DisplayName("Classpath holds the model's existing entries in model order and no compiled output the model does not declare")
    void classpathHoldsModelEntriesAndNoUndeclaredOutput() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-classpath"));
        Path dependency = writeJar(root.resolve("libs/dependency.jar"), Map.of());
        Path absent = root.resolve("libs/absent.jar");
        Path srcMain = root.resolve("src/main/java");
        writeSource(srcMain, "p", "P");
        // Output directories at the root that the model's module does not declare: one
        // empty, one whose only class has a linked source, one whose class has none.
        Files.createDirectories(root.resolve("target/classes"));
        writeFile(root.resolve("build/classes/java/main/p/P.class"), "placeholder\n");
        writeFile(root.resolve("target/test-classes/t/T.class"), "placeholder\n");

        ModelStub stub = new ModelStub(model(BuildSystem.UNKNOWN, "17",
            List.of(module(".", root, List.of(srcMain), List.of())),
            List.of(dependency.toString(), absent.toString()), List.of(), List.of()));
        JdtServiceImpl service = load(root, stub);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertAll(
            () -> assertEquals(List.of(dependency), snapshot.libraries(), "library entries"),
            () -> assertEquals(List.of(), service.getWarnings(), "warnings"),
            () -> assertEquals(1, stub.resolveCalls, "resolve() calls for the whole load"));
    }

    @Test
    @DisplayName("A source root two modules share is linked, counted and listed once, at its first position")
    void sharedSourceRootLinkedOnce() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-shared-root"));
        Path alphaSrc = root.resolve("alpha/src");
        Path shared = root.resolve("shared");
        Path betaSrc = root.resolve("beta/src");
        writeSource(alphaSrc, "a", "A");
        writeSource(shared, "s", "S1");
        writeSource(shared, "s", "S2");
        writeSource(betaSrc, "b", "B");

        // beta names the shared root through a path that normalizes to it.
        ModelStub stub = new ModelStub(model(BuildSystem.UNKNOWN, "17",
            List.of(
                module("alpha", root.resolve("alpha"), List.of(alphaSrc, shared), List.of()),
                module("beta", root.resolve("beta"), List.of(root.resolve("beta/../shared"), betaSrc), List.of())),
            List.of(), List.of(), List.of()));
        JdtServiceImpl service = load(root, stub);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        List<String> linkedFolderNames = Arrays.stream(service.getJavaProject().getRawClasspath())
            .filter(entry -> entry.getEntryKind() == IClasspathEntry.CPE_SOURCE)
            .map(entry -> entry.getPath().lastSegment())
            .toList();
        assertAll(
            () -> assertEquals(List.of(alphaSrc, shared, betaSrc), snapshot.sourceFolders(), "linked source folders"),
            () -> assertEquals(List.of("src-0-alpha-src", "src-1-shared", "src-2-beta-src"), linkedFolderNames,
                "linked folder names"),
            () -> assertEquals(4, service.getSourceFileCount(), "source file count"),
            () -> assertEquals(List.of("a", "s", "b"), service.getPackages(), "packages"),
            () -> assertEquals(List.of(), service.getWarnings(), "warnings"));
    }

    @Test
    @DisplayName("A source root inside another is linked once with its own package; the outer root excludes it, so each file is counted, listed and resolved once and edits to it are seen")
    void nestedSourceRootIsExcludedFromTheOuterRoot() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-nested-root"));
        Path outer = root.resolve("src");
        Path inner = root.resolve("src/gen");
        writeSource(outer, "p", "Outer");
        writeSource(inner, "q", "Gen");
        Path genFile = inner.resolve("q/Gen.java");

        ModelStub stub = new ModelStub(model(BuildSystem.UNKNOWN, "17",
            List.of(module(".", root, List.of(outer, inner), List.of())),
            List.of(), List.of(), List.of()));
        JdtServiceImpl service = load(root, stub);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        List<List<String>> exclusionPatterns = Arrays.stream(service.getJavaProject().getRawClasspath())
            .filter(entry -> entry.getEntryKind() == IClasspathEntry.CPE_SOURCE)
            .map(entry -> Arrays.stream(entry.getExclusionPatterns()).map(Object::toString).toList())
            .toList();
        IType gen = service.findType("q.Gen");
        assertAll(
            () -> assertEquals(List.of(outer, inner), snapshot.sourceFolders(), "linked source folders"),
            () -> assertEquals(List.of(List.of("gen/"), List.of()), exclusionPatterns,
                "exclusion patterns of each source entry"),
            () -> assertEquals(2, service.getSourceFileCount(), "source file count"),
            () -> assertEquals(List.of("p", "q"), service.getPackages(), "packages"),
            () -> assertEquals(outer.resolve("p/Outer.java"), location(service.findType("p.Outer")), "p.Outer"),
            () -> assertEquals(genFile, location(gen), "q.Gen"),
            () -> assertEquals("q", gen.getPackageFragment().getElementName(), "q.Gen's package"),
            () -> assertNull(service.findType("gen.q.Gen"), "gen.q.Gen"),
            () -> assertEquals(List.of(), service.getWarnings(), "warnings"));

        Files.writeString(genFile,
            "package q;\n\npublic class Gen {\n    public int added() {\n        return 1;\n    }\n}\n");
        List<Path> repaired = service.ensureFresh();
        List<String> methods = Arrays.stream(service.findType("q.Gen").getMethods())
            .map(method -> method.getElementName())
            .toList();
        assertAll(
            () -> assertEquals(List.of(genFile), repaired, "files disk sync repaired"),
            () -> assertEquals(List.of("added"), methods, "q.Gen's methods after the edit"));
    }

    @Test
    @DisplayName("A jar the model's classpath lists twice, in any spelling of its path, is one library entry")
    void repeatedClasspathEntryAddedOnce() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-repeated-jar"));
        Path dependency = writeJar(root.resolve("libs/dependency.jar"), Map.of());
        Path other = writeJar(root.resolve("libs/other.jar"), Map.of());

        ModelStub stub = new ModelStub(model(BuildSystem.UNKNOWN, "17", List.of(),
            List.of(dependency.toString(), other.toString(), dependency.toString(),
                root.resolve("libs/../libs/dependency.jar").toString()),
            List.of(), List.of()));
        JdtServiceImpl service = load(root, stub);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertAll(
            () -> assertEquals(List.of(dependency, other), snapshot.libraries(), "library entries"),
            () -> assertEquals(List.of(), service.getWarnings(), "warnings"));
    }

    @Test
    @DisplayName("APT is wired from the model's processor jars; a missing jar raises APT_PROCESSOR_JARS_MISSING naming it")
    void processorJarsFromModelWireApt() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-apt"));
        Path missingProcessor = root.resolve("processors/absent-processor.jar");

        ModelStub stub = new ModelStub(model(BuildSystem.UNKNOWN, "17", List.of(),
            List.of(), List.of(missingProcessor), List.of()));
        JdtServiceImpl service = load(root, stub);
        IJavaProject javaProject = service.getJavaProject();

        List<LoadWarning> warnings = service.getWarnings();
        assertAll(
            () -> assertEquals(true, AptConfig.isEnabled(javaProject), "APT enabled"),
            () -> assertEquals(List.of(LoadWarning.APT_PROCESSOR_JARS_MISSING),
                warnings.stream().map(LoadWarning::code).toList(), "warning codes"),
            () -> assertEquals("Annotation processors were declared but none of the 1 candidate jar(s) "
                    + "were found on disk; APT is enabled with an empty factory path. Missing: ["
                    + missingProcessor + "]",
                warnings.isEmpty() ? null : warnings.get(0).message(), "warning message"));
    }

    @Test
    @DisplayName("Model warnings surface first and unchanged, followed by the orchestrator's compliance warning")
    void modelWarningsPrecedeComplianceWarnings() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-warnings"));
        LoadWarning modelWarning = new LoadWarning(LoadWarning.BAZEL_NOT_BUILT,
            "bazel-bin holds no jars", "Run 'bazel build //...'");

        ModelStub stub = new ModelStub(model(BuildSystem.BAZEL, "1.6", List.of(),
            List.of(), List.of(), List.of(modelWarning)));
        JdtServiceImpl service = load(root, stub);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        List<LoadWarning> warnings = service.getWarnings();
        assertAll(
            () -> assertEquals(List.of(LoadWarning.BAZEL_NOT_BUILT, LoadWarning.COMPLIANCE_LEVEL_UNSUPPORTED),
                warnings.stream().map(LoadWarning::code).toList(), "warning codes in order"),
            () -> assertEquals(modelWarning, warnings.isEmpty() ? null : warnings.get(0), "model warning"),
            () -> assertEquals("1.8", snapshot.compilerSource(), "COMPILER_SOURCE clamped from 1.6"));
    }

    @Test
    @DisplayName("Without a model level, a model naming a build system gets the runtime level and COMPLIANCE_LEVEL_UNKNOWN")
    void nullLevelFallbackUsesModelBuildSystem() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-no-level"));
        String runtimeLevel = String.valueOf(Runtime.version().feature());

        ModelStub stub = new ModelStub(model(BuildSystem.GRADLE, null, List.of(),
            List.of(), List.of(), List.of()));
        JdtServiceImpl service = load(root, stub);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        List<LoadWarning> warnings = service.getWarnings();
        assertAll(
            () -> assertEquals(BuildSystem.GRADLE, service.getBuildSystem(), "build system"),
            () -> assertEquals(runtimeLevel, snapshot.compilerSource(), "COMPILER_SOURCE"),
            () -> assertEquals(List.of(LoadWarning.COMPLIANCE_LEVEL_UNKNOWN),
                warnings.stream().map(LoadWarning::code).toList(), "warning codes"),
            () -> assertEquals("Could not determine declared Java source level for GRADLE project; "
                    + "defaulting to runtime JVM major version " + runtimeLevel,
                warnings.isEmpty() ? null : warnings.get(0).message(), "warning message"));
    }

    // ========== The service exposes the load's model and applied level ==========

    @Test
    @DisplayName("A load exposes the model the importer resolves and the level it applied; before the load both are null")
    void serviceExposesResolvedModelAndAppliedLevel() throws Exception {
        Path root = helper.getFixturePath("multi-module-maven").toAbsolutePath().normalize();
        JdtServiceImpl service = new JdtServiceImpl();
        BuildModel modelBeforeLoad = service.getBuildModel();
        String levelBeforeLoad = service.getAnalysisLevel();

        service.loadProject(root);

        BuildModel model = service.getBuildModel();
        assertAll(
            () -> assertEquals(null, modelBeforeLoad, "build model before the load"),
            () -> assertEquals(null, levelBeforeLoad, "analysis level before the load"),
            () -> assertEquals(new ProjectImporter().resolveModel(root), model, "the model the importer resolves"),
            // The root pom's maven.compiler.source.
            () -> assertEquals("21", service.getAnalysisLevel(), "analysis level"));
        // The root pom declares api, impl and web; the root itself is the first module. A
        // module's id is its directory relative to the root.
        assertEquals(List.of(
                Map.entry(".", root),
                Map.entry("api", root.resolve("api")),
                Map.entry("impl", root.resolve("impl")),
                Map.entry("web", root.resolve("web"))),
            model.modules().stream().map(module -> Map.entry(module.id(), module.dir())).toList(),
            "module ids and directories");
    }

    @Test
    @DisplayName("The analysis level is the level applied to the project: a model level below JDT's range is reported clamped to 1.8")
    void analysisLevelIsTheClampedAppliedLevel() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-level-clamped"));

        ModelStub stub = new ModelStub(model(BuildSystem.MAVEN, "1.6", List.of(),
            List.of(), List.of(), List.of()));
        JdtServiceImpl service = load(root, stub);

        assertAll(
            () -> assertSame(stub.model, service.getBuildModel(), "build model: the importer's, level 1.6 kept"),
            () -> assertEquals("1.8", service.getAnalysisLevel(), "analysis level"));
    }

    @Test
    @DisplayName("Without a model level, the analysis level is the running JVM's level the load applied")
    void analysisLevelIsTheRuntimeFallback() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("seam-level-fallback"));
        String runtimeLevel = String.valueOf(Runtime.version().feature());

        ModelStub stub = new ModelStub(model(BuildSystem.UNKNOWN, null, List.of(),
            List.of(), List.of(), List.of()));
        JdtServiceImpl service = load(root, stub);

        assertAll(
            () -> assertSame(stub.model, service.getBuildModel(), "build model: the importer's, no level"),
            () -> assertEquals(runtimeLevel, service.getAnalysisLevel(), "analysis level"));
    }

    // ========== Importers compose their per-concern methods into a model ==========

    @Test
    @DisplayName("Plain Java resolves to one convention module at the root, hand-written and generated roots split")
    void plainJavaResolvesRootConventionModule() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("plain-layout"));
        Path srcMain = Files.createDirectories(root.resolve("src/main/java"));
        Path srcTest = Files.createDirectories(root.resolve("src/test/java"));
        Path generated = Files.createDirectories(root.resolve("target/generated-sources/annotations"));
        // A nested conventional layout in a directory no build file declares as a module.
        Files.createDirectories(root.resolve("other/src/main/java"));

        BuildModel expected = new BuildModel(BuildSystem.UNKNOWN, Provenance.CONVENTION,
            List.of(new BuildModule(".", root, List.of(srcMain, srcTest), List.of(generated),
                List.of(), null, false)),
            List.of(), List.of(), null, new BuildInputs(List.of(), List.of()), List.of());

        assertEquals(expected, BuildSystemImporter.NONE.resolve(root));
    }

    @Test
    @DisplayName("Plain Java reports the root's existing compiled-output directories as the root module's, in candidate order")
    void plainJavaReportsRootOutputDirsInCandidateOrder() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("plain-output"));
        Path srcMain = Files.createDirectories(root.resolve("src/main/java"));
        // Three of the four candidates exist; target/test-classes does not.
        Path gradleTest = Files.createDirectories(root.resolve("build/classes/java/test"));
        Path gradleMain = Files.createDirectories(root.resolve("build/classes/java/main"));
        Path mavenMain = Files.createDirectories(root.resolve("target/classes"));

        BuildModel expected = new BuildModel(BuildSystem.UNKNOWN, Provenance.CONVENTION,
            List.of(new BuildModule(".", root, List.of(srcMain), List.of(),
                List.of(mavenMain, gradleMain, gradleTest), null, false)),
            List.of(), List.of(), null, new BuildInputs(List.of(), List.of()), List.of());

        assertEquals(expected, BuildSystemImporter.NONE.resolve(root));
    }

    @Test
    @DisplayName("Only the first module at the project root reports the root's compiled output; other modules report none")
    void onlyFirstRootModuleReportsRootOutputDirs() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("bazel-root-packages"));
        Files.writeString(root.resolve("MODULE.bazel"), "module(name = \"out\")\n");
        // The root and sub are both BUILD packages, so the root is a module twice: once
        // from the module walk, once as a package.
        Files.writeString(root.resolve("BUILD"), "# root package\n");
        Path sub = Files.createDirectories(root.resolve("sub"));
        Files.writeString(sub.resolve("BUILD"), "# sub package\n");
        Path rootClasses = Files.createDirectories(root.resolve("target/classes"));
        Files.createDirectories(sub.resolve("target/classes"));

        BuildModel model = new BazelImporter().resolve(root);

        assertEquals(List.of(
                new BuildModule(".", root, List.of(), List.of(), List.of(rootClasses), null, false),
                new BuildModule(".", root, List.of(), List.of(), List.of(), null, false),
                new BuildModule("sub", sub, List.of(), List.of(), List.of(), null, false)),
            model.modules(), "modules");
    }

    @Test
    @DisplayName("Bazel resolves processor jars as the resolved classpath jars that carry the processor SPI descriptor")
    void bazelResolvesProcessorJarsBySpiScan() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("bazel-spi"));
        Files.writeString(root.resolve("MODULE.bazel"), "module(name = \"spi\")\n");
        Path processor = writeJar(root.resolve("bazel-bin/lib/processor.jar"), Map.of(
            "META-INF/services/javax.annotation.processing.Processor", "p.Processor\n"));
        Path library = writeJar(root.resolve("bazel-bin/lib/library.jar"), Map.of(
            "p/readme.txt", "no processor here\n"));
        // getDependencies canonicalizes every jar it collects.
        Path processorReal = processor.toRealPath();
        Path libraryReal = library.toRealPath();

        BuildModel model = new BazelImporter().resolve(root);

        assertAll(
            () -> assertEquals(BuildSystem.BAZEL, model.buildSystem(), "build system"),
            () -> assertEquals(Provenance.STATIC_READ, model.provenance(), "provenance"),
            () -> assertEquals(List.of(new BuildModule(".", root, List.of(), List.of(), List.of(), null, false)),
                model.modules(), "modules"),
            // Collection order follows the directory walk, which the filesystem decides.
            () -> assertEquals(Set.of(processorReal.toString(), libraryReal.toString()),
                Set.copyOf(model.classpath()), "classpath"),
            () -> assertEquals(2, model.classpath().size(), "classpath size"),
            () -> assertEquals(List.of(processorReal), model.processorJars(), "processor jars"),
            () -> assertEquals(null, model.analysisLevel(), "analysis level"),
            () -> assertEquals(List.of(), model.warnings(), "warnings"));
    }

    // ========== Helpers ==========

    /** Importer that answers only through {@link #resolve}, counting its calls. */
    private static final class ModelStub implements BuildSystemImporter {
        private final BuildModel model;
        private int resolveCalls;

        ModelStub(BuildModel model) {
            this.model = model;
        }

        @Override
        public BuildModel resolve(Path projectRoot) {
            resolveCalls++;
            return model;
        }

        @Override
        public String detectCompilerLevel(Path projectPath) {
            return null;
        }

        @Override
        public List<String> getDependencies(Path projectPath, List<LoadWarning> warnings) {
            return List.of();
        }
    }

    private static JdtServiceImpl load(Path root, ModelStub stub) throws Exception {
        JdtServiceImpl service = new JdtServiceImpl(new ProjectImporter(Map.of(BuildSystem.UNKNOWN, stub)));
        service.loadProject(root);
        return service;
    }

    private static BuildModel model(BuildSystem buildSystem, String level, List<BuildModule> modules,
            List<String> classpath, List<Path> processorJars, List<LoadWarning> warnings) {
        Provenance provenance = buildSystem == BuildSystem.UNKNOWN ? Provenance.CONVENTION : Provenance.STATIC_READ;
        return new BuildModel(buildSystem, provenance, modules, classpath, processorJars, level,
            new BuildInputs(List.of(), List.of()), warnings);
    }

    private static BuildModule module(String id, Path dir, List<Path> sourceRoots, List<Path> generatedRoots) {
        return new BuildModule(id, dir, sourceRoots, generatedRoots, List.of(), null, false);
    }

    private static void writeSource(Path sourceRoot, String packageName, String className) throws IOException {
        Path packageDir = Files.createDirectories(sourceRoot.resolve(packageName));
        Files.writeString(packageDir.resolve(className + ".java"),
            "package " + packageName + ";\n\npublic class " + className + " {\n}\n");
    }

    /** The file {@code type} was read from, or null when the type did not resolve. */
    private static Path location(IType type) {
        if (type == null) {
            return null;
        }
        return Path.of(type.getResource().getLocation().toOSString());
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Path writeJar(Path jar, Map<String, String> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                jarOut.putNextEntry(new JarEntry(entry.getKey()));
                jarOut.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
        }
        return jar;
    }
}
