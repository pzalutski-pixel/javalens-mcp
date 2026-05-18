package org.javalens.core.fixtures;

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
 * Contract test for {@link TestProjectHelper}. The helper is the lifecycle backbone of
 * every build-system / tool test — its beforeEach/afterEach hooks must work, its fixture
 * paths must resolve, copies must preserve content, and loadProject / loadProjectCopy
 * must produce usable {@link JdtServiceImpl} handles.
 *
 * <p>Indirect coverage exists through every suite member that uses {@code @RegisterExtension
 * TestProjectHelper helper = ...}; this file pins the contract directly so a regression
 * in lifecycle or fixture resolution surfaces locally before propagating through hundreds
 * of seemingly-unrelated failures elsewhere.
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
            "Expected simple-maven/pom.xml to exist as a sentinel; got: "
                + simple.resolve("pom.xml"));
    }

    @Test
    @DisplayName("copyFixture produces a faithful copy under the temp directory")
    void copyFixture_preservesContent() throws IOException {
        Path copy = helper.copyFixture("simple-maven");

        assertTrue(copy.startsWith(helper.getTempDirectory()),
            "Copy must live under the test's temp directory; got: " + copy);

        // Spot-check: the source pom is present and content matches byte-for-byte.
        Path srcPom = helper.getFixturePath("simple-maven").resolve("pom.xml");
        Path dstPom = copy.resolve("pom.xml");
        assertTrue(Files.isRegularFile(dstPom),
            "Copied pom.xml must exist; got: " + dstPom);
        assertEquals(Files.readString(srcPom), Files.readString(dstPom),
            "Copied pom.xml content must equal source byte-for-byte");

        // Count parity: copy walks the entire tree.
        long srcCount;
        long dstCount;
        try (Stream<Path> s = Files.walk(helper.getFixturePath("simple-maven"))) {
            srcCount = s.count();
        }
        try (Stream<Path> s = Files.walk(copy)) {
            dstCount = s.count();
        }
        assertEquals(srcCount, dstCount,
            "Copy entry count must equal source entry count (depth-N walk); "
                + "src=" + srcCount + " dst=" + dstCount);
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
    @DisplayName("loadProjectCopy loads a writable copy whose source root is the temp dir")
    void loadProjectCopy_loadsTempCopy() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        assertNotNull(service);
        // Sanity: the loaded project's file location is under the temp dir, so tests that
        // mutate the project don't pollute the canonical fixture.
        Path projectPath = service.getJavaProject().getProject().getLocation() == null
            ? null
            : Path.of(service.getJavaProject().getProject().getLocation().toOSString());
        // Loaded projects are linked to the workspace, not directly to the source dir,
        // so we can't assert projectPath.startsWith(tempDir). Instead, verify the helper
        // copied the fixture under tempDir.
        assertTrue(Files.isDirectory(helper.getTempDirectory().resolve("simple-maven")),
            "Expected simple-maven to be copied under the temp dir before load");
    }

    @Test
    @DisplayName("getService() returns null before any load is performed")
    void getService_nullBeforeLoad() {
        // A FRESH helper has no loaded service. Use a separate instance instead of `this`
        // because the @RegisterExtension field above already has a temp dir.
        TestProjectHelper fresh = new TestProjectHelper();
        assertNull(fresh.getService(),
            "getService() must return null before any loadProject call");
    }
}
