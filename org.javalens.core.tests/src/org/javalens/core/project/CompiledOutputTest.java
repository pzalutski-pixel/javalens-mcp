package org.javalens.core.project;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.CompilationProblems;
import org.javalens.core.fixtures.GradleBinary;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the compiled-output rule: a module's compiled output goes on the classpath only when
 * it holds a top-level class with no source in any of the model's linked source roots, and
 * the load then names it with {@link LoadWarning#COMPILED_OUTPUT_INCLUDED}. Output whose
 * every class has a linked source stays off, so each linked type is seen once.
 *
 * <p>The count tests use placeholder class files, because the count never reads class-file
 * contents. The load tests compile real classes with the JDK compiler, because JDT reads
 * every class on the classpath.
 */
class CompiledOutputTest {

    private static final String REMEDIATION =
        "Rebuild the project after editing its sources so its compiled classes stay current.";

    /**
     * Output entries of the count tests: of the top-level classes, only {@code p/A} has a
     * source, so {@code p/B} and {@code q/LibKt} are the two source-less classes. The
     * nested class and the two descriptors are not top-level classes.
     */
    private static final List<String> MIXED_ENTRIES = List.of(
        "p/A.class", "p/A$Inner.class", "p/package-info.class", "module-info.class",
        "p/B.class", "q/LibKt.class");

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    // ========== Counting source-less classes ==========

    @Test
    @DisplayName("A class directory counts its top-level classes with no .java source; nested, package-info and module-info classes are not counted")
    void directoryCountsSourcelessTopLevelClasses() throws Exception {
        Path base = Files.createDirectories(helper.getTempDirectory().resolve("count-directory"));
        Path sourceRoot = mixedSourceRoot(base);
        Path output = base.resolve("classes");
        for (String entry : MIXED_ENTRIES) {
            writePlaceholder(output.resolve(entry));
        }

        assertEquals(OptionalInt.of(2), CompiledOutput.sourcelessClassCount(output, List.of(sourceRoot)));
    }

    @Test
    @DisplayName("A jar counts its top-level classes with no .java source exactly as a class directory does")
    void jarCountsSourcelessTopLevelClasses() throws Exception {
        Path base = Files.createDirectories(helper.getTempDirectory().resolve("count-jar"));
        Path sourceRoot = mixedSourceRoot(base);
        Path jar = writeJar(base.resolve("lib/output.jar"), MIXED_ENTRIES);

        assertEquals(OptionalInt.of(2), CompiledOutput.sourcelessClassCount(jar, List.of(sourceRoot)));
    }

    @Test
    @DisplayName("Output whose every top-level class has a source in one of the roots counts zero")
    void outputWithEverySourceLinkedCountsZero() throws Exception {
        Path base = Files.createDirectories(helper.getTempDirectory().resolve("count-all-linked"));
        Path main = base.resolve("src");
        Path generated = base.resolve("generated");
        writeFile(main.resolve("p/A.java"), "package p;\n\npublic class A {\n}\n");
        writeFile(generated.resolve("g/G.java"), "package g;\n\npublic class G {\n}\n");
        Path output = base.resolve("classes");
        for (String entry : List.of("p/A.class", "p/A$1.class", "g/G.class", "g/package-info.class",
                "module-info.class")) {
            writePlaceholder(output.resolve(entry));
        }

        assertEquals(OptionalInt.of(0), CompiledOutput.sourcelessClassCount(output, List.of(main, generated)));
    }

    @Test
    @DisplayName("A jar class whose name is not a valid path has no source")
    void jarClassWithInvalidPathNameHasNoSource() throws Exception {
        Path base = Files.createDirectories(helper.getTempDirectory().resolve("count-invalid-name"));
        Path sourceRoot = base.resolve("src");
        writeFile(sourceRoot.resolve("p/A.java"), "package p;\n\npublic class A {\n}\n");
        // A NUL character is not valid in a path on any OS.
        Path jar = writeJar(base.resolve("lib/output.jar"), List.of("p/A.class", "r/Odd\0Name.class"));

        assertEquals(OptionalInt.of(1), CompiledOutput.sourcelessClassCount(jar, List.of(sourceRoot)));
    }

    @Test
    @DisplayName("An output that cannot be read has no count")
    void unreadableOutputHasNoCount() throws Exception {
        Path base = Files.createDirectories(helper.getTempDirectory().resolve("count-unreadable"));
        Path sourceRoot = base.resolve("src");
        writeFile(sourceRoot.resolve("p/A.java"), "package p;\n\npublic class A {\n}\n");
        Path notAJar = base.resolve("lib/output.jar");
        writeFile(notAJar, "these bytes are not a zip archive\n");

        assertEquals(OptionalInt.empty(), CompiledOutput.sourcelessClassCount(notAJar, List.of(sourceRoot)));
    }

    // ========== A load applies the rule ==========

    @Test
    @DisplayName("Compiled output of a pure Java project stays off the classpath: each type resolves from source and is seen once")
    void pureJavaOutputStaysOffTheClasspath() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("pure-java"));
        Path srcMain = root.resolve("src/main/java");
        Path aSource = writeFile(srcMain.resolve("p/A.java"),
            "package p;\n\npublic class A {\n    public static class Inner {\n    }\n}\n");
        Path classes = root.resolve("target/classes");
        // Writes p/A.class and p/A$Inner.class, both from the linked source.
        compile(classes, aSource);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertAll(
            // Plain Java reports the root's existing output directory as the root module's.
            () -> assertEquals(List.of(new BuildModule(".", root, List.of(srcMain), List.of(),
                    List.of(classes), null, false)),
                service.getBuildModel().modules(), "model modules"),
            () -> assertEquals(List.of(), snapshot.libraries(), "library entries"),
            () -> assertEquals(List.of(), service.getWarnings(), "warnings"),
            () -> assertEquals(false, service.findType("p.A").isBinary(), "p.A is binary"),
            () -> assertEquals(List.of("p.A source"), typeMatches(service.getJavaProject(), "p", "A"),
                "every type named p.A the search engine finds"));
    }

    @Test
    @DisplayName("Compiled output holding a class with no linked source stays on the classpath with COMPILED_OUTPUT_INCLUDED, and Java references to that class resolve")
    void sourcelessOutputStaysOnTheClasspath() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("sourceless-class"));
        Path srcMain = root.resolve("src/main/java");
        Path aSource = writeFile(srcMain.resolve("p/A.java"), "package p;\n\npublic class A {\n}\n");
        Path userSource = writeFile(srcMain.resolve("p/User.java"),
            "package p;\n\nimport q.Lib;\n\npublic class User {\n"
                + "    public int use() {\n        return new Lib().value();\n    }\n}\n");
        // References a class that no source root and no output holds.
        Path brokenSource = writeFile(srcMain.resolve("p/Broken.java"),
            "package p;\n\nimport q.Missing;\n\npublic class Broken {\n}\n");
        // Lib's source lies in a folder no source root covers, so its class has no linked source.
        Path libSource = writeFile(root.resolve("elsewhere/q/Lib.java"),
            "package q;\n\npublic class Lib {\n    public int value() {\n        return 1;\n    }\n}\n");
        Path classes = root.resolve("target/classes");
        compile(classes, aSource, libSource);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());
        IType lib = service.findType("q.Lib");

        assertAll(
            () -> assertEquals(List.of(classes), snapshot.libraries(), "library entries"),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.COMPILED_OUTPUT_INCLUDED,
                    "Compiled output target/classes holds 1 class with no linked source, so it is on the "
                        + "classpath; results for module '.' may include compiled duplicates of linked "
                        + "sources and reflect the last build",
                    REMEDIATION, ".")),
                service.getWarnings(), "warnings"),
            () -> assertEquals(true, lib.isBinary(), "q.Lib is binary"),
            () -> assertEquals(classes, Path.of(lib.getPackageFragment().getParent().getPath().toOSString()),
                "package fragment root holding q.Lib"),
            () -> assertEquals(List.of(), CompilationProblems.ids(service.getCompilationUnit(userSource)),
                "problems in User.java"),
            () -> assertEquals(List.of(IProblem.ImportNotFound),
                CompilationProblems.ids(service.getCompilationUnit(brokenSource)),
                "problems in Broken.java"),
            // The kept output also holds p.A, which the load names as a possible duplicate.
            () -> assertEquals(List.of("p.A binary", "p.A source"), typeMatches(service.getJavaProject(), "p", "A"),
                "every type named p.A the search engine finds"));
    }

    @Test
    @DisplayName("Gradle reports one classes directory per language: the Java one, whose classes all have linked sources, stays off; the Groovy one, holding a class with no linked source, stays on with COMPILED_OUTPUT_INCLUDED")
    void gradleGroovyOutputStaysAndJavaOutputIsDropped() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("Gradle per-language classes directories")) {
            Path root = Files.createDirectories(helper.getTempDirectory().resolve("groovy-output"));
            writeFile(root.resolve("settings.gradle"), "rootProject.name = 'groovy-output'\n");
            writeFile(root.resolve("build.gradle"), "plugins {\n    id 'groovy'\n}\n\n"
                + "java {\n    sourceCompatibility = JavaVersion.VERSION_17\n}\n");
            Path srcMain = root.resolve("src/main/java");
            Path linkedSource = writeFile(srcMain.resolve("p/Linked.java"), "package p;\n\npublic class Linked {\n}\n");
            Path userSource = writeFile(srcMain.resolve("p/User.java"),
                "package p;\n\npublic class User {\n"
                    + "    public int use() {\n        return new FromGroovy().value();\n    }\n}\n");
            // Stands in for what groovyc writes: a class whose source no linked root holds.
            Path groovySource = writeFile(root.resolve("elsewhere/p/FromGroovy.java"),
                "package p;\n\npublic class FromGroovy {\n    public int value() {\n        return 1;\n    }\n}\n");
            Path javaClasses = root.resolve("build/classes/java/main");
            Path groovyClasses = root.resolve("build/classes/groovy/main");
            compile(javaClasses, linkedSource);
            compile(groovyClasses, groovySource);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());
            IType fromGroovy = service.findType("p.FromGroovy");

            assertAll(
                () -> assertEquals(List.of(javaClasses, groovyClasses, root.resolve("build/classes/java/test"),
                        root.resolve("build/classes/groovy/test")),
                    service.getBuildModel().modules().get(0).outputDirs(), "classes directories Gradle reports"),
                () -> assertEquals(List.of(groovyClasses), snapshot.libraries(), "library entries"),
                () -> assertEquals(List.of(new LoadWarning(LoadWarning.COMPILED_OUTPUT_INCLUDED,
                        "Compiled output build/classes/groovy/main holds 1 class with no linked source, so it is "
                            + "on the classpath; results for module ':' may include compiled duplicates of linked "
                            + "sources and reflect the last build",
                        REMEDIATION, ":")),
                    service.getWarnings(), "warnings"),
                () -> assertEquals(true, fromGroovy.isBinary(), "p.FromGroovy is binary"),
                () -> assertEquals(groovyClasses,
                    Path.of(fromGroovy.getPackageFragment().getParent().getPath().toOSString()),
                    "package fragment root holding p.FromGroovy"),
                () -> assertEquals(List.of(), CompilationProblems.ids(service.getCompilationUnit(userSource)),
                    "problems in User.java"),
                () -> assertEquals(List.of("p.Linked source"), typeMatches(service.getJavaProject(), "p", "Linked"),
                    "every type named p.Linked the search engine finds"));
        }
    }

    @Test
    @DisplayName("Kept outputs follow the model's classpath in module order, each with its warning; source-only and absent outputs are skipped")
    void keptOutputsFollowModuleOrder() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("output-order"));
        Path dependency = writeJar(root.resolve("libs/dependency.jar"), List.of());

        Path alphaSrc = root.resolve("alpha/src");
        Path alphaGen = root.resolve("alpha/gen");
        Path aSource = writeFile(alphaSrc.resolve("a/A.java"), "package a;\n\npublic class A {\n}\n");
        Path gSource = writeFile(alphaGen.resolve("g/G.java"), "package g;\n\npublic class G {\n}\n");
        Path betaSrc = root.resolve("beta/src");
        writeFile(betaSrc.resolve("b2/Beta.java"), "package b2;\n\npublic class Beta {\n}\n");
        Path gammaSrc = root.resolve("gamma/src");
        Path cSource = writeFile(gammaSrc.resolve("c/C.java"), "package c;\n\npublic class C {\n}\n");
        Path k1Source = writeFile(root.resolve("elsewhere/k/K1.java"), "package k;\n\npublic class K1 {\n}\n");
        Path k2Source = writeFile(root.resolve("elsewhere/k/K2.java"), "package k;\n\npublic class K2 {\n}\n");
        Path bSource = writeFile(root.resolve("elsewhere/b/B.java"), "package b;\n\npublic class B {\n}\n");

        // alpha: A (source in alpha/src), G (source in alpha/gen), K1 and K2 (no source).
        Path alphaClasses = root.resolve("alpha/classes");
        compile(alphaClasses, aSource, gSource, k1Source, k2Source);
        // beta: A (source in alpha's root) and B (no source).
        Path betaClasses = root.resolve("beta/classes");
        compile(betaClasses, aSource, bSource);
        // gamma: C (source in gamma/src) only.
        Path gammaClasses = root.resolve("gamma/classes");
        compile(gammaClasses, cSource);

        JdtServiceImpl service = load(root, stubModel(
            List.of(
                new BuildModule("alpha", root.resolve("alpha"), List.of(alphaSrc), List.of(alphaGen),
                    List.of(root.resolve("alpha/never-built"), alphaClasses), null, false),
                new BuildModule("beta", root.resolve("beta"), List.of(betaSrc), List.of(),
                    List.of(betaClasses), null, false),
                new BuildModule("gamma", root.resolve("gamma"), List.of(gammaSrc), List.of(),
                    List.of(gammaClasses), null, false)),
            List.of(dependency.toString())));
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertAll(
            () -> assertEquals(List.of(dependency, alphaClasses, betaClasses), snapshot.libraries(),
                "library entries"),
            () -> assertEquals(List.of(
                    new LoadWarning(LoadWarning.COMPILED_OUTPUT_INCLUDED,
                        "Compiled output alpha/classes holds 2 classes with no linked source, so it is on the "
                            + "classpath; results for module 'alpha' may include compiled duplicates of linked "
                            + "sources and reflect the last build",
                        REMEDIATION, "alpha"),
                    new LoadWarning(LoadWarning.COMPILED_OUTPUT_INCLUDED,
                        "Compiled output beta/classes holds 1 class with no linked source, so it is on the "
                            + "classpath; results for module 'beta' may include compiled duplicates of linked "
                            + "sources and reflect the last build",
                        REMEDIATION, "beta")),
                service.getWarnings(), "warnings"));
    }

    @Test
    @DisplayName("An output that cannot be read stays on the classpath with COMPILED_OUTPUT_INCLUDED saying it could not be read, and LIBRARY_INDEX_INCOMPLETE naming it, since JDT cannot index it")
    void unreadableOutputIsKeptAndReportedUnreadable() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("unreadable-output"));
        Path moduleSrc = root.resolve("m/src");
        writeFile(moduleSrc.resolve("p/A.java"), "package p;\n\npublic class A {\n}\n");
        Path notAJar = writeFile(root.resolve("m/lib/output.jar"), "these bytes are not a zip archive\n");

        JdtServiceImpl service = load(root, stubModel(
            List.of(new BuildModule("m", root.resolve("m"), List.of(moduleSrc), List.of(), List.of(notAJar),
                null, false)),
            List.of()));
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertAll(
            () -> assertEquals(List.of(notAJar), snapshot.libraries(), "library entries"),
            () -> assertEquals(List.of(
                    new LoadWarning(LoadWarning.COMPILED_OUTPUT_INCLUDED,
                        "Compiled output m/lib/output.jar could not be read, so it is on the classpath; results for "
                            + "module 'm' may include compiled duplicates of linked sources and reflect the last build",
                        REMEDIATION, "m"),
                    new LoadWarning(LoadWarning.LIBRARY_INDEX_INCOMPLETE,
                        "The search index of 1 library could not be built: m/lib/output.jar; "
                            + "search-based results do not include its types",
                        "Make sure each library is a readable archive or class folder, then reload the project; "
                            + "if the warning persists, restart JavaLens.")),
                service.getWarnings(), "warnings"));
    }

    @Test
    @DisplayName("An output the model's classpath already holds is added once, with no COMPILED_OUTPUT_INCLUDED")
    void outputOnModelClasspathIsNotAddedAgain() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("output-on-classpath"));
        Path moduleSrc = root.resolve("m/src");
        Path aSource = writeFile(moduleSrc.resolve("p/A.java"), "package p;\n\npublic class A {\n}\n");
        Path libSource = writeFile(root.resolve("elsewhere/q/Lib.java"), "package q;\n\npublic class Lib {\n}\n");
        // Lib has no linked source, so the rule alone would keep this output.
        Path classes = root.resolve("m/classes");
        compile(classes, aSource, libSource);

        JdtServiceImpl service = load(root, stubModel(
            List.of(new BuildModule("m", root.resolve("m"), List.of(moduleSrc), List.of(), List.of(classes),
                null, false)),
            List.of(classes.toString())));
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertAll(
            () -> assertEquals(List.of(classes), snapshot.libraries(), "library entries"),
            () -> assertEquals(List.of(), service.getWarnings(), "warnings"));
    }

    @Test
    @DisplayName("When resolving the model fails, the fallback root module keeps the root's source-less output with COMPILED_OUTPUT_INCLUDED")
    void fallbackModelKeepsRootSourcelessOutput() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("fallback-output"));
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Path srcMain = root.resolve("src/main/java");
        writeFile(srcMain.resolve("p/P.java"), "package p;\n\npublic class P {\n}\n");
        Path libSource = writeFile(root.resolve("elsewhere/q/Lib.java"), "package q;\n\npublic class Lib {\n}\n");
        Path classes = root.resolve("target/classes");
        compile(classes, libSource);
        String runtimeLevel = String.valueOf(Runtime.version().feature());

        JdtServiceImpl service = new JdtServiceImpl(
            new ProjectImporter(Map.of(BuildSystem.MAVEN, new ThrowingImporter())));
        service.loadProject(root);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertAll(
            () -> assertEquals(List.of(new BuildModule(".", root, List.of(srcMain), List.of(), List.of(classes),
                    null, false)),
                service.getBuildModel().modules(), "model modules"),
            () -> assertEquals(List.of(classes), snapshot.libraries(), "library entries"),
            () -> assertEquals(List.of(
                    new LoadWarning(LoadWarning.MODULE_LAYOUT_ASSUMED,
                        "Resolving the MAVEN build model failed (IllegalStateException: boom); "
                            + "source roots were assumed from conventional layouts at the project root",
                        "Check the project's build files, then reload the project.",
                        "."),
                    new LoadWarning(LoadWarning.COMPILED_OUTPUT_INCLUDED,
                        "Compiled output target/classes holds 1 class with no linked source, so it is on the "
                            + "classpath; results for module '.' may include compiled duplicates of linked "
                            + "sources and reflect the last build",
                        REMEDIATION, "."),
                    new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_UNKNOWN,
                        "Could not determine declared Java source level for MAVEN project; "
                            + "defaulting to runtime JVM major version " + runtimeLevel,
                        "Declare maven.compiler.source/release in pom.xml, sourceCompatibility in build.gradle, "
                            + "or javacopts -source/--release in BUILD.bazel so language-level features parse "
                            + "against the intended grammar.")),
                service.getWarnings(), "warnings"));
    }

    // ========== Helpers ==========

    /** Importer whose resolution fails outright. */
    private static final class ThrowingImporter implements BuildSystemImporter {
        @Override
        public BuildModel resolve(Path projectRoot) {
            throw new IllegalStateException("boom");
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

    /** A plain-Java model at level 17 with these modules and classpath, and no warnings. */
    private static BuildModel stubModel(List<BuildModule> modules, List<String> classpath) {
        return new BuildModel(BuildSystem.UNKNOWN, Provenance.CONVENTION, modules, classpath, List.of(), "17",
            new BuildInputs(List.of(), List.of()), List.of());
    }

    /** Load {@code root} through an importer that resolves to {@code model}. */
    private static JdtServiceImpl load(Path root, BuildModel model) throws CoreException {
        JdtServiceImpl service = new JdtServiceImpl(
            new ProjectImporter(Map.of(BuildSystem.UNKNOWN, new ModelStub(model))));
        service.loadProject(root);
        return service;
    }

    /** Importer that answers only through {@link #resolve}. */
    private static final class ModelStub implements BuildSystemImporter {
        private final BuildModel model;

        ModelStub(BuildModel model) {
            this.model = model;
        }

        @Override
        public BuildModel resolve(Path projectRoot) {
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

    /**
     * Source root of the count tests: the source of {@code p/A}, plus a Kotlin source of
     * {@code q/LibKt}, which is not a .java source.
     */
    private static Path mixedSourceRoot(Path base) throws IOException {
        Path sourceRoot = base.resolve("src");
        writeFile(sourceRoot.resolve("p/A.java"), "package p;\n\npublic class A {\n}\n");
        writeFile(sourceRoot.resolve("q/Lib.kt"), "package q\n\nfun lib() = 1\n");
        return sourceRoot;
    }

    /**
     * Every type with this package and simple name that the search engine finds in the
     * project, as "qualified-name source" or "qualified-name binary", sorted.
     */
    private static List<String> typeMatches(IJavaProject project, String packageName, String typeName)
            throws CoreException {
        List<String> matches = new ArrayList<>();
        int exact = SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE;
        new SearchEngine().searchAllTypeNames(
            packageName.toCharArray(), exact, typeName.toCharArray(), exact,
            IJavaSearchConstants.TYPE,
            SearchEngine.createJavaSearchScope(new IJavaElement[] { project }),
            new TypeNameMatchRequestor() {
                @Override
                public void acceptTypeNameMatch(TypeNameMatch match) {
                    IType type = match.getType();
                    matches.add(type.getFullyQualifiedName() + (type.isBinary() ? " binary" : " source"));
                }
            },
            IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, null);
        matches.sort(null);
        return matches;
    }

    /** Compile {@code sources} with the JDK compiler into {@code outputDir}. */
    private static void compile(Path outputDir, Path... sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Files.createDirectories(outputDir);
        StringWriter diagnostics = new StringWriter();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(diagnostics, fileManager, null,
                List.of("--release", "17", "-d", outputDir.toString()), null,
                fileManager.getJavaFileObjectsFromPaths(List.of(sources))).call();
            if (!compiled) {
                throw new IllegalStateException("javac failed: " + diagnostics);
            }
        }
    }

    private static Path writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static void writePlaceholder(Path classFile) throws IOException {
        writeFile(classFile, "placeholder\n");
    }

    private static Path writeJar(Path jar, List<String> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out)) {
            for (String entry : entries) {
                jarOut.putNextEntry(new JarEntry(entry));
                jarOut.write("placeholder\n".getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
        }
        return jar;
    }
}
