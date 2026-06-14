package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindTestsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindTestsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindTestsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds tests comprehensively")
    void findsTestsComprehensively() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Counts non-negative
        assertTrue(((Number) data.get("testClassCount")).intValue() >= 0,
            "testClassCount >= 0; got: " + data);
        assertTrue(((Number) data.get("testMethodCount")).intValue() >= 0,
            "testMethodCount >= 0; got: " + data);

        // Test classes structure
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) data.get("testClasses");
        assertNotNull(testClasses, "testClasses list missing");

        if (!testClasses.isEmpty()) {
            Map<String, Object> tc = testClasses.get(0);
            String cn = (String) tc.get("className");
            assertNotNull(cn, "className missing: " + tc);
            assertFalse(cn.isBlank(), "className non-blank; got: " + tc);
            String fp = (String) tc.get("filePath");
            assertNotNull(fp, "filePath missing: " + tc);
            assertTrue(fp.endsWith(".java"), "filePath ends with .java; got: " + tc);
            @SuppressWarnings("unchecked")
            List<?> testMethods = (List<?>) tc.get("testMethods");
            assertNotNull(testMethods, "testMethods list missing: " + tc);
            assertFalse(testMethods.isEmpty(),
                "test class with no methods wouldn't be enumerated; got: " + tc);
        }
    }

    @Test @DisplayName("supports filtering options")
    void supportsFilteringOptions() {
        // Pattern filter
        ObjectNode withPattern = objectMapper.createObjectNode();
        withPattern.put("pattern", "*Test");
        assertTrue(tool.execute(withPattern).isSuccess());

        // Include disabled
        ObjectNode withDisabled = objectMapper.createObjectNode();
        withDisabled.put("includeDisabled", true);
        assertTrue(tool.execute(withDisabled).isSuccess());
    }

    @Test @DisplayName("returns success with no parameters")
    void returnsSuccessWithNoParameters() {
        assertTrue(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("SampleTest is found with its @Test-annotated methods (excluding @Disabled by default)")
    void sampleTest_foundWithKnownTestMethods() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) getData(r).get("testClasses");

        // Use exact-equals match; multiple classes now end with "SampleTest"
        // (Junit4SampleTest, TestngSampleTest) so endsWith would be ambiguous.
        Map<String, Object> sampleTest = testClasses.stream()
            .filter(tc -> "SampleTest".equals(tc.get("className")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "SampleTest must be detected; got: " +
                    testClasses.stream().map(tc -> tc.get("className")).toList()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testMethods = (List<Map<String, Object>>) sampleTest.get("testMethods");
        java.util.Set<String> methodNames = testMethods.stream()
            .map(tm -> (String) tm.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        // SampleTest's @Test/@ParameterizedTest/@TestFactory methods, EXCLUDING the two
        // @Disabled methods (testDivision, anotherDisabledTest) by default. Non-test
        // helpers (helperMethod, privateHelper) and the @Nested group's own methods
        // are excluded — the exact set is the isolation oracle.
        assertEquals(java.util.Set.of(
            "testAddition", "testSubtraction", "testMultiplication",
            "testWithCustomDisplayName", "testParameterized", "dynamicTestsFromFactory"),
            methodNames);
    }

    @Test
    @DisplayName("includeDisabled=true surfaces @Disabled test methods")
    void includeDisabled_surfacesDisabledMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("includeDisabled", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) getData(r).get("testClasses");
        java.util.Set<String> allMethodNames = testClasses.stream()
            .filter(tc -> "SampleTest".equals(tc.get("className")))
            .flatMap(tc -> {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> methods = (List<Map<String, Object>>) tc.get("testMethods");
                return methods.stream();
            })
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(allMethodNames.contains("testDivision"),
            "testDivision (@Disabled) must appear when includeDisabled=true; got: " + allMethodNames);
    }

    @Test
    @DisplayName("@TestFactory method on SampleTest is detected as a test (JUnit 5 dynamic test factory)")
    void testFactory_isDetected() {
        // dynamicTestsFromFactory is annotated @TestFactory — a JUnit 5
        // dynamic-test producer. It IS a test method per the JUnit 5
        // platform; the tool's contract claims JUnit 5 support so this
        // annotation must be recognised.
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) getData(r).get("testClasses");
        Map<String, Object> sampleTest = testClasses.stream()
            .filter(tc -> "SampleTest".equals(tc.get("className")))
            .findFirst()
            .orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) sampleTest.get("testMethods");
        java.util.Set<String> names = methods.stream()
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(names.contains("dynamicTestsFromFactory"),
            "dynamicTestsFromFactory is annotated @TestFactory and must be detected as a test; got: "
                + names);
    }

    @Test
    @DisplayName("@ParameterizedTest method on SampleTest is detected as a test")
    void parameterizedTest_isDetected() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) getData(r).get("testClasses");
        Map<String, Object> sampleTest = testClasses.stream()
            .filter(tc -> "SampleTest".equals(tc.get("className")))
            .findFirst()
            .orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) sampleTest.get("testMethods");
        java.util.Set<String> names = methods.stream()
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(names.contains("testParameterized"),
            "testParameterized is annotated @ParameterizedTest and must be detected as a test; got: "
                + names);
    }

    @Test
    @DisplayName("@Nested inner class is reported as its own test class with both nested tests")
    void nestedClass_isReportedAsSeparateTestClass() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) getData(r).get("testClasses");
        Map<String, Object> nested = testClasses.stream()
            .filter(tc -> "NestedGroup".equals(tc.get("className")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "NestedGroup (an @Nested inner class with @Test methods) must appear as a test class; got: "
                    + testClasses.stream().map(tc -> tc.get("className")).toList()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) nested.get("testMethods");
        java.util.Set<String> names = methods.stream()
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        assertEquals(java.util.Set.of("nestedTestOne", "nestedTestTwo"), names,
            "NestedGroup declares exactly two @Test methods; got: " + names);
    }

    @Test
    @DisplayName("Junit4SampleTest is detected with framework=JUnit4 via @Before/@After heuristic")
    void junit4_classDetectedWithFrameworkAttribution() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) getData(r).get("testClasses");
        Map<String, Object> j4 = testClasses.stream()
            .filter(tc -> "Junit4SampleTest".equals(tc.get("className")))
            .findFirst()
            .orElseThrow();

        // Framework attribution falls through to the @Before/@After lifecycle heuristic
        // because the simple-name annotation `@Test` (after `import org.junit.Test`) does
        // not match the JUnit5 jupiter check.
        assertEquals("JUnit4", j4.get("framework"),
            "Junit4SampleTest uses @Before/@After — must be classified as JUnit4; got: " + j4);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) j4.get("testMethods");
        java.util.Set<String> names = methods.stream()
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        // testIgnored is excluded by default (includeDisabled=false). The non-ignored
        // @Test methods are exactly testAddition and testSubtraction.
        assertEquals(java.util.Set.of("testAddition", "testSubtraction"), names,
            "Junit4 @Ignore tests are filtered by default; got: " + names);
    }

    @Test
    @DisplayName("TestngSampleTest is detected with framework=TestNG via fully qualified annotation names")
    void testng_classDetectedWithFrameworkAttribution() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) getData(r).get("testClasses");
        Map<String, Object> tng = testClasses.stream()
            .filter(tc -> "TestngSampleTest".equals(tc.get("className")))
            .findFirst()
            .orElseThrow();

        // Framework attribution matches "testng" in the annotation's fully qualified type
        // name (e.g., `@org.testng.annotations.Test`).
        assertEquals("TestNG", tng.get("framework"),
            "TestngSampleTest uses @org.testng.annotations.* — must be classified as TestNG; got: " + tng);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) tng.get("testMethods");
        java.util.Set<String> names = methods.stream()
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        assertEquals(java.util.Set.of("scenarioOne", "scenarioTwo"), names,
            "TestngSampleTest declares scenarioOne and scenarioTwo; got: " + names);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("testClassCount equals testClasses.size()")
    void testClassCount_equalsListSize() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int count = ((Number) data.get("testClassCount")).intValue();
        @SuppressWarnings("unchecked")
        List<?> testClasses = (List<?>) data.get("testClasses");
        assertEquals(count, testClasses.size());
    }

    @Test
    @DisplayName("Each test class entry has className, filePath, line, testMethodCount, testMethods")
    void testClassEntry_shape() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) getData(r).get("testClasses");
        for (Map<String, Object> tc : classes) {
            for (String key : List.of("className", "filePath", "line", "testMethodCount", "testMethods")) {
                assertNotNull(tc.get(key), key + " missing on test class entry: " + tc);
            }
        }
    }

    @Test
    @DisplayName("Each test method entry has name and line")
    void testMethodEntry_shape() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) getData(r).get("testClasses");
        for (Map<String, Object> tc : classes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> methods = (List<Map<String, Object>>) tc.get("testMethods");
            for (Map<String, Object> m : methods) {
                assertNotNull(m.get("name"), "name missing on test method: " + m);
                assertNotNull(m.get("line"), "line missing on test method: " + m);
            }
        }
    }

    @Test
    @DisplayName("SampleTest is classified as JUnit5 (uses @BeforeEach)")
    void sampleTest_frameworkIsJUnit5() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) getData(r).get("testClasses");
        Map<String, Object> sampleTest = classes.stream()
            .filter(tc -> "SampleTest".equals(tc.get("className")))
            .findFirst().orElseThrow();
        assertEquals("JUnit5", sampleTest.get("framework"),
            "SampleTest uses @BeforeEach — must be classified as JUnit5; got: " + sampleTest);
    }

    @Test
    @DisplayName("Pattern filter excludes non-matching test classes (Junit4*)")
    void patternFilter_excludesNonMatching() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("pattern", "Junit4*");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) getData(r).get("testClasses");
        for (Map<String, Object> tc : classes) {
            String name = (String) tc.get("className");
            assertTrue(name.startsWith("Junit4"),
                "Pattern `Junit4*` must only match classes beginning with Junit4; got: " + name);
        }
    }

    @Test
    @DisplayName("testWithCustomDisplayName carries the @DisplayName value")
    void displayName_reported() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) getData(r).get("testClasses");
        Map<String, Object> sampleTest = classes.stream()
            .filter(tc -> "SampleTest".equals(tc.get("className")))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) sampleTest.get("testMethods");
        Map<String, Object> custom = methods.stream()
            .filter(m -> "testWithCustomDisplayName".equals(m.get("name")))
            .findFirst().orElseThrow();
        assertEquals("Custom display name for this test", custom.get("displayName"),
            "testWithCustomDisplayName must carry its exact @DisplayName value; got: " + custom);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: TestngSampleTest is framework=TestNG with scenarioOne/Two")
    void envelope_testng_exactMethods() {
        JsonNode payload = envelope.assertEnvelopeFidelity("find_tests", envelope.args());

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_tests failed through the envelope: " + payload);
        JsonNode tng = null;
        for (JsonNode tc : payload.get("data").get("testClasses")) {
            if ("TestngSampleTest".equals(tc.get("className").asText())) tng = tc;
        }
        assertNotNull(tng, "TestngSampleTest must be detected through the envelope");
        assertEquals("TestNG", tng.get("framework").asText(),
            "TestNG framework attribution must survive the envelope");
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (JsonNode m : tng.get("testMethods")) names.add(m.get("name").asText());
        assertEquals(java.util.Set.of("scenarioOne", "scenarioTwo"), names,
            "the exact TestNG method set must survive the envelope");
    }
}
