package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestEnvironment;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug C — multi-module Maven classpath aggregation.
 *
 * <p>The original {@code getMavenDependencies} passed an absolute
 * {@code -Dmdep.outputFile=<temp>}. In a reactor build every child module wrote to the same
 * file, so only the last child's classpath survived. Users with three modules each declaring
 * unique dependencies saw classpaths missing two-thirds of the libraries they expected.
 *
 * <p>The fix uses a relative filename so each child writes its own
 * {@code <module>/target/javalens-classpath.txt}, then walks the tree and unions every file
 * via a {@code LinkedHashSet}.
 *
 * <p>This test stages a real reactor build: it copies the {@code multi-module-maven} fixture,
 * runs {@code mvn install -DskipTests} once to seed the local repo (without it
 * {@code dependency:build-classpath} fails on impl/web because they reference sibling
 * artifacts that don't exist in {@code ~/.m2}), then loads the project and asserts the
 * classpath includes the unique external dep declared by each of the three modules.
 */
class MultiModuleMavenTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("classpath includes a unique dep from every reactor module")
    void everyReactorModuleContributesItsOwnDeps() throws Exception {
        String mvn = resolveMavenBinary();
        TestEnvironment.requireOrSkip(mvn,
            "Maven binary on PATH or in ~/.m2/wrapper/dists (multi-module classpath aggregation)");

        // Point ProjectImporter at the same Maven binary the test will use. This is a
        // test-only override; production reads the default mvn / mvn.cmd from PATH.
        String previousOverride = System.getProperty("javalens.maven.binary");
        System.setProperty("javalens.maven.binary", mvn);
        try {
            Path projectRoot = helper.copyFixture("multi-module-maven");

            // Seed ~/.m2 with sibling artifacts so impl/web can resolve com.example:api/impl.
            // -DskipTests keeps install short. -B is batch mode; -fae fails-at-end.
            runMaven(mvn, projectRoot, "install", "-DskipTests", "-B", "-fae", "-q");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            assertTrue(snapshot.hasLibraryMatching(".*slf4j-api.*\\.jar"),
                "Expected slf4j-api (declared in api module) on classpath. Libraries: " + snapshot.libraries());
            assertTrue(snapshot.hasLibraryMatching(".*commons-lang3.*\\.jar"),
                "Expected commons-lang3 (declared in impl module) on classpath. Libraries: " + snapshot.libraries());
            assertTrue(snapshot.hasLibraryMatching(".*spring-core.*\\.jar"),
                "Expected spring-core (declared in web module) on classpath. Libraries: " + snapshot.libraries());
        } finally {
            if (previousOverride == null) System.clearProperty("javalens.maven.binary");
            else System.setProperty("javalens.maven.binary", previousOverride);
        }
    }

    @Test
    @DisplayName("cross-module find_references resolves callers in sibling reactor modules")
    void crossModuleFindReferencesResolvesAcrossSiblings() throws Exception {
        String mvn = resolveMavenBinary();
        TestEnvironment.requireOrSkip(mvn,
            "Maven binary (cross-module navigation needs the reactor loaded)");

        String previousOverride = System.getProperty("javalens.maven.binary");
        System.setProperty("javalens.maven.binary", mvn);
        try {
            Path projectRoot = helper.copyFixture("multi-module-maven");
            runMaven(mvn, projectRoot, "install", "-DskipTests", "-B", "-fae", "-q");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);

            // Probe: does navigation cross module boundaries on the existing flat design?
            // Greeter is declared in :api; GreeterImpl in :impl implements it. Looking up
            // references to Greeter must surface GreeterImpl across the module boundary.
            IType greeter = service.findType("com.example.api.Greeter");
            assertTrue(greeter != null,
                "Expected to resolve com.example.api.Greeter (declared in :api module)");

            List<SearchMatch> refs = service.getSearchService()
                .findReferences(greeter, IJavaSearchConstants.REFERENCES, 100);

            boolean foundInImpl = refs.stream().anyMatch(m -> {
                String resourcePath = m.getResource() != null ? m.getResource().getFullPath().toString() : "";
                return resourcePath.contains("GreeterImpl");
            });
            assertTrue(foundInImpl,
                "Expected to find GreeterImpl as a reference to Greeter across the module boundary. " +
                "Got " + refs.size() + " matches.");
        } finally {
            if (previousOverride == null) System.clearProperty("javalens.maven.binary");
            else System.setProperty("javalens.maven.binary", previousOverride);
        }
    }

    private static void runMaven(String mvnBinary, Path projectRoot, String... goals)
            throws IOException, InterruptedException {
        java.util.List<String> command = buildSubprocessCommand(mvnBinary, goals);
        ProcessBuilder pb = new ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(true);
        propagateJavaHome(pb);
        Process p = pb.start();
        StringBuilder captured = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (captured.length() < 8192) captured.append(line).append('\n');
            }
        }
        boolean done = p.waitFor(5, TimeUnit.MINUTES);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("mvn " + String.join(" ", goals) + " timed out\n" + captured);
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException("mvn " + String.join(" ", goals)
                + " failed with exit code " + p.exitValue() + "\n" + captured);
        }
    }

    /**
     * Locate a Maven binary the test can invoke. Prefers {@code mvn} on PATH; falls back to
     * a Maven Wrapper distribution under {@code ~/.m2/wrapper/dists}, which is present in
     * this repo because {@code ./mvnw} extracts mvn there on first build.
     */
    /**
     * On Windows, write env-set commands and the actual mvn invocation to a temp
     * {@code .cmd} script and execute that. Routing through ProcessBuilder's argv
     * with {@code cmd /c "set X=Y && mvn ..."} is unreliable: Java's command-line
     * quoting for embedded {@code "} interacts unpredictably with cmd.exe's parser
     * for compound commands. Writing a script file sidesteps every layer of quoting.
     */
    private static java.util.List<String> buildSubprocessCommand(String binary, String[] goals) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        java.util.List<String> command = new java.util.ArrayList<>();
        if (isWindows) {
            String override = System.getenv("JAVALENS_TESTS_CHILD_JAVA_HOME");
            if (override != null) override = override.trim();
            String javaHome = (override != null && !override.isBlank())
                ? override : System.getProperty("java.home");
            javaHome = javaHome.trim();
            try {
                Path script = Files.createTempFile("javalens-cmd-", ".cmd");
                StringBuilder sb = new StringBuilder();
                sb.append("@echo off\r\n");
                sb.append("set \"JAVA_HOME=").append(javaHome).append("\"\r\n");
                sb.append("set \"Path=").append(javaHome).append("\\bin;%Path%\"\r\n");
                sb.append("echo [diag] JAVA_HOME=%JAVA_HOME%\r\n");
                sb.append("echo [diag] java.exe exists check:\r\n");
                sb.append("if exist \"%JAVA_HOME%\\bin\\java.exe\" (echo [diag]   YES) else (echo [diag]   NO)\r\n");
                sb.append("dir \"%JAVA_HOME%\\bin\\java.exe\" 2>&1\r\n");
                sb.append("echo [diag] Path[0..200]=%Path:~0,200%\r\n");
                sb.append("\"").append(binary).append("\"");
                for (String g : goals) sb.append(' ').append(g);
                sb.append("\r\n");
                Files.writeString(script, sb.toString());
                command.add(script.toString());
            } catch (IOException e) {
                throw new RuntimeException("Failed to write temp cmd script", e);
            }
        } else {
            command.add(binary);
            for (String g : goals) command.add(g);
        }
        return command;
    }

    private static void propagateJavaHome(ProcessBuilder pb) {
        String override = System.getenv("JAVALENS_TESTS_CHILD_JAVA_HOME");
        if (override != null) override = override.trim();
        String javaHome = (override != null && !override.isBlank())
            ? override : System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) return;
        javaHome = javaHome.trim();
        java.util.Map<String, String> env = pb.environment();
        env.put("JAVA_HOME", javaHome);
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String pathKey = isWindows ? "Path" : "PATH";
        String javaBin = javaHome + java.io.File.separator + "bin";
        String existing = env.getOrDefault(pathKey, "");
        env.put(pathKey, existing.isEmpty() ? javaBin : javaBin + java.io.File.pathSeparator + existing);
    }

    private static String resolveMavenBinary() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String binaryName = isWindows ? "mvn.cmd" : "mvn";

        try {
            Process p = new ProcessBuilder(binaryName, "-v").redirectErrorStream(true).start();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            p.waitFor();
            if (p.exitValue() == 0) return binaryName;
        } catch (IOException | InterruptedException ignored) {
            if (Thread.interrupted()) Thread.currentThread().interrupt();
        }

        Path wrapperDists = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists");
        if (!Files.isDirectory(wrapperDists)) return null;
        try (Stream<Path> distros = Files.list(wrapperDists)) {
            for (Path distro : distros.toList()) {
                try (Stream<Path> hashes = Files.list(distro)) {
                    for (Path hashDir : hashes.toList()) {
                        Path bin = hashDir.resolve("bin").resolve(binaryName);
                        if (Files.isRegularFile(bin)) return bin.toString();
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }
}
