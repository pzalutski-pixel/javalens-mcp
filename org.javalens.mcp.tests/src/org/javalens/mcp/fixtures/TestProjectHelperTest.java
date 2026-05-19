package org.javalens.mcp.fixtures;

import org.javalens.core.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the mcp.tests {@link TestProjectHelper} — the lifecycle backbone of
 * every tool test. Mirrors the core.tests TestProjectHelperTest. The mcp.tests version
 * adds a parent-directory fallback in getFixturePath; this test also pins that.
 */
class TestProjectHelperTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("beforeEach creates a unique temp directory accessible via getTempDirectory()")
    void beforeEach_createsTempDir() {
        Path tmp = helper.getTempDirectory();
        assertNotNull(tmp, "Temp directory must be initialized before each test");
        assertTrue(Files.isDirectory(tmp),
            "Temp directory must exist and be a directory; got: " + tmp);
        assertTrue(tmp.getFileName().toString().startsWith("javalens-test-"),
            "Temp dir name should follow the documented prefix; got: " + tmp.getFileName());
    }

    @Test
    @DisplayName("getFixturePath resolves to an existing directory under the configured fixtures root")
    void getFixturePath_resolvesToExistingFixture() {
        Path simple = helper.getFixturePath("simple-maven");
        assertNotNull(simple);
        assertTrue(Files.isDirectory(simple),
            "Expected simple-maven fixture directory to exist; got: " + simple);
        assertTrue(Files.isRegularFile(simple.resolve("pom.xml")),
            "Expected simple-maven/pom.xml as a sentinel; got: " + simple.resolve("pom.xml"));
    }

    @Test
    @DisplayName("copyFixture produces a faithful copy under the temp directory")
    void copyFixture_preservesContent() throws IOException {
        Path copy = helper.copyFixture("simple-maven");

        assertTrue(copy.startsWith(helper.getTempDirectory()),
            "Copy must live under the test's temp directory; got: " + copy);

        Path srcPom = helper.getFixturePath("simple-maven").resolve("pom.xml");
        Path dstPom = copy.resolve("pom.xml");
        assertTrue(Files.isRegularFile(dstPom), "Copied pom.xml must exist");
        assertEquals(Files.readString(srcPom), Files.readString(dstPom),
            "Copied pom.xml content must equal source byte-for-byte");

        long srcCount;
        long dstCount;
        try (Stream<Path> s = Files.walk(helper.getFixturePath("simple-maven"))) {
            srcCount = s.count();
        }
        try (Stream<Path> s = Files.walk(copy)) {
            dstCount = s.count();
        }
        assertEquals(srcCount, dstCount,
            "Copy entry count must equal source entry count; src=" + srcCount
                + " dst=" + dstCount);
    }

    @Test
    @DisplayName("loadProject returns a usable service whose project resolves a known type")
    void loadProject_returnsUsableService() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        assertNotNull(service);
        assertSame(service, helper.getService(),
            "getService() must return the same instance as the last loadProject");
        assertNotNull(service.findType("com.example.Calculator"),
            "Expected Calculator to resolve through the loaded service");
    }

    @Test
    @DisplayName("loadProjectCopy stages a copy under the temp dir before loading")
    void loadProjectCopy_loadsTempCopy() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        assertNotNull(service);
        assertTrue(Files.isDirectory(helper.getTempDirectory().resolve("simple-maven")),
            "Expected simple-maven to be copied under the temp dir before load");
    }

    @Test
    @DisplayName("getService() returns null before any load is performed")
    void getService_nullBeforeLoad() {
        TestProjectHelper fresh = new TestProjectHelper();
        assertNull(fresh.getService(),
            "getService() must return null before any loadProject call");
    }

    @Test
    @DisplayName("loadFixture bundles service + classpath snapshot + warning codes")
    void loadFixture_returnsBundle() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");
        assertNotNull(loaded.service());
        assertNotNull(loaded.classpath());
        assertNotNull(loaded.warnings(),
            "warnings must be present (possibly empty) on every loadFixture");
        // The classpath snapshot should reflect simple-maven (Java 21 declared).
        assertEquals("21", loaded.classpath().compilerSource(),
            "simple-maven declares Java 21; snapshot must reflect that");
    }
}
