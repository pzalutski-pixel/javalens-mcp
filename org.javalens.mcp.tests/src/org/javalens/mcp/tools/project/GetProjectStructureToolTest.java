package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetProjectStructureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetProjectStructureToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetProjectStructureTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetProjectStructureTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("returns complete project structure")
    void returnsCompleteProjectStructure() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify project info — non-blank identity
        String projectName = (String) data.get("projectName");
        assertNotNull(projectName, "projectName missing");
        assertFalse(projectName.isBlank(), "projectName non-blank; got: " + data);
        String projectRoot = (String) data.get("projectRoot");
        assertNotNull(projectRoot, "projectRoot missing");
        assertFalse(projectRoot.isBlank(), "projectRoot non-blank; got: " + data);
        assertTrue((Integer) data.get("totalPackages") > 0);
        assertTrue((Integer) data.get("totalFiles") > 0);

        // Verify source roots
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceRoots = (List<Map<String, Object>>) data.get("sourceRoots");
        assertFalse(sourceRoots.isEmpty());

        Map<String, Object> srcRoot = sourceRoots.get(0);
        String srcPath = (String) srcRoot.get("path");
        assertNotNull(srcPath, "source root path missing");
        assertFalse(srcPath.isBlank(), "source root path non-blank; got: " + srcRoot);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> packages = (List<Map<String, Object>>) srcRoot.get("packages");
        assertFalse(packages.isEmpty());
        Map<String, Object> firstPkg = packages.get(0);
        String pkgName = (String) firstPkg.get("name");
        assertNotNull(pkgName, "package name missing");
        assertFalse(pkgName.isBlank(), "package name non-blank; got: " + firstPkg);
        // The default package is rendered with fileCount=0 when empty; non-default
        // packages should report a non-negative count.
        assertTrue(((Number) firstPkg.get("fileCount")).intValue() >= 0,
            "fileCount >= 0; got: " + firstPkg);
    }

    @Test @DisplayName("supports optional parameters")
    void supportsOptionalParameters() {
        // Test includeFiles
        ObjectNode withFiles = objectMapper.createObjectNode();
        withFiles.put("includeFiles", true);
        ToolResponse r1 = tool.execute(withFiles);
        assertTrue(r1.isSuccess());

        // Test maxDepth
        ObjectNode withDepth = objectMapper.createObjectNode();
        withDepth.put("maxDepth", 2);
        assertTrue(tool.execute(withDepth).isSuccess());
    }

    @Test @DisplayName("works with no parameters")
    void worksWithNoParameters() {
        assertTrue(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("simple-maven exposes both src/main/java and src/test/java as source roots")
    @SuppressWarnings("unchecked")
    void simpleMaven_exposesMainAndTestSourceRoots() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        List<Map<String, Object>> sourceRoots =
            (List<Map<String, Object>>) getData(r).get("sourceRoots");
        java.util.Set<String> rootPaths = new java.util.HashSet<>();
        for (Map<String, Object> root : sourceRoots) {
            String path = ((String) root.get("path")).replace('\\', '/');
            rootPaths.add(path);
        }

        // TestProjectHelper imports source folders as linked folders named
        // "src-{index}-{path-with-dashes}", so simple-maven's src/main/java becomes
        // ".../src-N-src-main-java" and src/test/java becomes ".../src-N-src-test-java".
        boolean hasMain = rootPaths.stream().anyMatch(p -> p.endsWith("src-main-java"));
        boolean hasTest = rootPaths.stream().anyMatch(p -> p.endsWith("src-test-java"));
        assertTrue(hasMain,
            "src/main/java source folder must appear; got: " + rootPaths);
        assertTrue(hasTest,
            "src/test/java source folder must appear; got: " + rootPaths);
    }

    @Test
    @DisplayName("includeFiles=true populates files[] with .java filenames per package")
    @SuppressWarnings("unchecked")
    void includeFilesTrue_addsFilesListToEachPackage() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("includeFiles", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        // Find the com.example package within the main source root.
        List<Map<String, Object>> sourceRoots =
            (List<Map<String, Object>>) getData(r).get("sourceRoots");
        Map<String, Object> mainRoot = sourceRoots.stream()
            .filter(rt -> ((String) rt.get("path")).replace('\\', '/').endsWith("src-main-java"))
            .findFirst()
            .orElseThrow();
        List<Map<String, Object>> packages = (List<Map<String, Object>>) mainRoot.get("packages");
        Map<String, Object> comExample = packages.stream()
            .filter(p -> "com.example".equals(p.get("name")))
            .findFirst()
            .orElseThrow();

        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) comExample.get("files");
        assertNotNull(files, "includeFiles=true must add files[] to each package");
        // Calculator.java lives in com.example — its file name must appear.
        assertTrue(files.contains("Calculator.java"),
            "files[] for com.example must include Calculator.java; got: " + files);
    }

    @Test
    @DisplayName("includeFiles=false (default) omits files[]")
    @SuppressWarnings("unchecked")
    void includeFilesFalse_omitsFilesList() {
        // Default: includeFiles unset → false.
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        List<Map<String, Object>> sourceRoots =
            (List<Map<String, Object>>) getData(r).get("sourceRoots");
        Map<String, Object> mainRoot = sourceRoots.stream()
            .filter(rt -> ((String) rt.get("path")).replace('\\', '/').endsWith("src-main-java"))
            .findFirst()
            .orElseThrow();
        List<Map<String, Object>> packages = (List<Map<String, Object>>) mainRoot.get("packages");

        for (Map<String, Object> pkg : packages) {
            assertFalse(pkg.containsKey("files"),
                "includeFiles=false must omit `files` key; offending pkg: " + pkg);
        }
    }

    @Test
    @DisplayName("maxDepth filters packages deeper than the threshold")
    @SuppressWarnings("unchecked")
    void maxDepth_filtersDeepPackages() {
        // simple-maven has com.example.cycledemo.a (depth 4) and com.example.cycledemo.b
        // (depth 4). With maxDepth=2 only depth <= 2 packages survive — so the cycledemo
        // sub-packages must be filtered out and com.example (depth 2) must remain.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxDepth", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        List<Map<String, Object>> sourceRoots =
            (List<Map<String, Object>>) getData(r).get("sourceRoots");
        java.util.Set<String> allPackageNames = new java.util.HashSet<>();
        for (Map<String, Object> root : sourceRoots) {
            List<Map<String, Object>> pkgs = (List<Map<String, Object>>) root.get("packages");
            for (Map<String, Object> pkg : pkgs) {
                allPackageNames.add((String) pkg.get("name"));
            }
        }

        assertTrue(allPackageNames.contains("com.example"),
            "com.example (depth 2) must remain under maxDepth=2; got: " + allPackageNames);
        assertFalse(allPackageNames.contains("com.example.cycledemo.a"),
            "com.example.cycledemo.a (depth 4) must be filtered under maxDepth=2; got: "
                + allPackageNames);
        assertFalse(allPackageNames.contains("com.example.cycledemo.b"),
            "com.example.cycledemo.b (depth 4) must be filtered under maxDepth=2; got: "
                + allPackageNames);
    }

    @Test
    @DisplayName("maxDepth is clamped to [1, 20]")
    @SuppressWarnings("unchecked")
    void maxDepth_clampedToRange() {
        // maxDepth=0 is clamped UP to 1 → depth-1 packages (single-segment names like
        // "com" alone) would survive. simple-maven's packages all start with "com" but
        // the segments are "com.example..." which is depth >= 2 → none survive at 1.
        ObjectNode argsZero = objectMapper.createObjectNode();
        argsZero.put("maxDepth", 0);
        ToolResponse r1 = tool.execute(argsZero);
        assertTrue(r1.isSuccess(),
            "maxDepth=0 must not crash; clamped to 1. Got error: " +
                (r1.getError() != null ? r1.getError().getMessage() : "n/a"));

        // maxDepth=50 is clamped DOWN to 20 — no exception, all packages within depth 20
        // survive (simple-maven has nothing deeper than ~4).
        ObjectNode argsHuge = objectMapper.createObjectNode();
        argsHuge.put("maxDepth", 50);
        ToolResponse r2 = tool.execute(argsHuge);
        assertTrue(r2.isSuccess(),
            "maxDepth=50 must not crash; clamped to 20.");

        List<Map<String, Object>> sourceRoots =
            (List<Map<String, Object>>) getData(r2).get("sourceRoots");
        Map<String, Object> mainRoot = sourceRoots.stream()
            .filter(rt -> ((String) rt.get("path")).replace('\\', '/').endsWith("src-main-java"))
            .findFirst()
            .orElseThrow();
        List<Map<String, Object>> packages = (List<Map<String, Object>>) mainRoot.get("packages");
        // simple-maven's deepest packages (cycledemo.a, cycledemo.b) are depth 4 — must
        // all be present under maxDepth=20.
        boolean hasDeep = packages.stream()
            .anyMatch(p -> "com.example.cycledemo.a".equals(p.get("name")));
        assertTrue(hasDeep,
            "Deeper packages must survive maxDepth=50 (clamped to 20); got: " + packages);
    }

    @Test
    @DisplayName("default-package fixture: top-level NoPackage.java is reported in '(default package)' with fileCount=1 and files=[NoPackage.java]")
    @SuppressWarnings("unchecked")
    void defaultPackage_renderedAsParenthesizedLabel() throws Exception {
        // Load the default-package fixture which has a single top-level .java file with
        // no package declaration.
        JdtServiceImpl svc = helper.loadProject("default-package");
        GetProjectStructureTool localTool = new GetProjectStructureTool(() -> svc);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("includeFiles", true);

        ToolResponse r = localTool.execute(args);
        assertTrue(r.isSuccess());

        List<Map<String, Object>> sourceRoots =
            (List<Map<String, Object>>) getData(r).get("sourceRoots");
        Map<String, Object> defaultPkg = sourceRoots.stream()
            .flatMap(rt -> ((List<Map<String, Object>>) rt.get("packages")).stream())
            .filter(pkg -> "(default package)".equals(pkg.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Default package must be reported as '(default package)'; got: " + sourceRoots));
        // Pin the payload: fileCount=1 and files=[NoPackage.java].
        assertEquals(1, defaultPkg.get("fileCount"),
            "Default package must report fileCount=1; got: " + defaultPkg);
        assertEquals(List.of("NoPackage.java"), defaultPkg.get("files"),
            "Default package files=[NoPackage.java]; got: " + defaultPkg);
    }

    @Test
    @DisplayName("Parent package with subpackages but no direct .java files surfaces with fileCount=0 and no 'files' key even when includeFiles=true")
    @SuppressWarnings("unchecked")
    void parentPackageWithSubpackagesNoFiles_omitsFilesKey() throws Exception {
        // many-packages has 21 leaf packages com.example.pkg00..pkg20, each with one
        // Marker.java. The intermediate packages "com" and "com.example" have NO direct
        // .java files but DO have subpackages — they survive the skip-empty check (line
        // 83-85) because hasSubpackages()=true. Inside createPackageInfo, the
        // `if (includeFiles && units.length > 0)` guard means the `files` key is NOT
        // added for these — pinning the units.length==0 branch with includeFiles=true.
        JdtServiceImpl svc = helper.loadProject("many-packages");
        GetProjectStructureTool localTool = new GetProjectStructureTool(() -> svc);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("includeFiles", true);

        ToolResponse r = localTool.execute(args);
        assertTrue(r.isSuccess());

        List<Map<String, Object>> sourceRoots =
            (List<Map<String, Object>>) getData(r).get("sourceRoots");
        Map<String, Object> comExample = sourceRoots.stream()
            .flatMap(rt -> ((List<Map<String, Object>>) rt.get("packages")).stream())
            .filter(pkg -> "com.example".equals(pkg.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "com.example parent must surface (has subpackages, no direct files); got: "
                    + sourceRoots));
        assertEquals(0, comExample.get("fileCount"),
            "Parent com.example must report fileCount=0; got: " + comExample);
        assertFalse(comExample.containsKey("files"),
            "includeFiles=true with units.length==0 must NOT add `files` key; got: "
                + comExample);
    }

    @Test
    @DisplayName("many-packages fixture: 21 declared packages plus the empty parent are all surfaced")
    @SuppressWarnings("unchecked")
    void manyPackages_allTwentyOnePackagesSurfaced() throws Exception {
        JdtServiceImpl svc = helper.loadProject("many-packages");
        GetProjectStructureTool localTool = new GetProjectStructureTool(() -> svc);

        ToolResponse r = localTool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        List<Map<String, Object>> sourceRoots =
            (List<Map<String, Object>>) getData(r).get("sourceRoots");
        java.util.Set<String> names = sourceRoots.stream()
            .flatMap(rt -> ((List<Map<String, Object>>) rt.get("packages")).stream())
            .map(pkg -> (String) pkg.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        // Each pkg## (00..20) must appear.
        for (int i = 0; i <= 20; i++) {
            String expected = String.format("com.example.pkg%02d", i);
            assertTrue(names.contains(expected),
                expected + " must appear in the project structure; got: " + names);
        }
    }
}
