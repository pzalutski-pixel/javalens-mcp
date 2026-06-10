package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find test classes and methods in the project.
 * Detects JUnit 4, JUnit 5, and TestNG annotations.
 *
 * AI-centric: Helps AI locate tests for verification when making code changes.
 */
public class FindTestsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindTestsTool.class);

    public FindTestsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_tests";
    }

    @Override
    public String getDescription() {
        return """
            Find test classes and methods in the project.

            USAGE: find_tests()
            OUTPUT: List of test classes with their test methods

            Supports:
            - JUnit 4 (@Test, @Before, @After, etc.)
            - JUnit 5 (@Test, @BeforeEach, @AfterEach, etc.)
            - TestNG annotations

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("pattern", "string", "Filter test classes by name pattern (glob)")
            .optional("includeDisabled", "boolean", "Include disabled/ignored tests (default false)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String pattern = getStringParam(arguments, "pattern", null);
        boolean includeDisabled = getBooleanParam(arguments, "includeDisabled", false);

        try {
            List<Map<String, Object>> testClasses = new ArrayList<>();
            int totalTestMethods = 0;

            for (Path file : service.getAllJavaFiles()) {
                try {
                    ICompilationUnit cu = service.getCompilationUnit(file);
                    if (cu == null) continue;

                    // Parse to AST
                    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                    parser.setSource(cu);
                    parser.setResolveBindings(true);

                    CompilationUnit ast = (CompilationUnit) parser.createAST(null);
                    if (ast == null) continue;

                    List<Map<String, Object>> testsInFile = findTestsInFile(ast, file, service, includeDisabled);

                    for (Map<String, Object> testClass : testsInFile) {
                        String className = (String) testClass.get("className");

                        // Apply pattern filter
                        if (pattern != null && !matchesGlob(className, pattern)) {
                            continue;
                        }

                        testClasses.add(testClass);
                        @SuppressWarnings("unchecked")
                        List<?> methods = (List<?>) testClass.get("testMethods");
                        totalTestMethods += methods.size();
                    }
                } catch (Exception e) {
                    log.debug("Error finding tests in file {}: {}", file, e.getMessage());
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("testClassCount", testClasses.size());
            data.put("testMethodCount", totalTestMethods);
            data.put("testClasses", testClasses);

            if (testClasses.isEmpty()) {
                data.put("note", "No test classes found. Ensure test source paths are included in project.");
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(testClasses.size())
                .returnedCount(testClasses.size())
                .suggestedNextTools(List.of(
                    "get_document_symbols to explore a test class",
                    "find_references to find what a test covers"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error finding tests: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private List<Map<String, Object>> findTestsInFile(CompilationUnit ast, Path file,
                                                       IJdtService service, boolean includeDisabled) {
        List<Map<String, Object>> testClasses = new ArrayList<>();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                List<Map<String, Object>> testMethods = new ArrayList<>();
                boolean isTestClass = false;
                boolean classDisabled = TestMethodDetector.isDisabled(node);

                // Check methods
                for (MethodDeclaration method : node.getMethods()) {
                    Map<String, Object> testInfo = checkTestMethod(method, ast, file, service);
                    if (testInfo != null) {
                        isTestClass = true;

                        if (classDisabled) {
                            testInfo.put("disabled", true);
                        }

                        boolean methodDisabled = (boolean) testInfo.getOrDefault("disabled", false);
                        if (includeDisabled || !methodDisabled) {
                            testMethods.add(testInfo);
                        }
                    }
                }

                if (isTestClass) {
                    Map<String, Object> classInfo = new LinkedHashMap<>();
                    classInfo.put("className", node.getName().getIdentifier());
                    classInfo.put("filePath", service.getPathUtils().formatPath(file));

                    int classLine = ast.getLineNumber(node.getName().getStartPosition()) - 1;
                    classInfo.put("line", classLine);

                    classInfo.put("testMethodCount", testMethods.size());
                    classInfo.put("testMethods", testMethods);

                    if (classDisabled) {
                        classInfo.put("disabled", true);
                    }

                    // Detect test framework
                    String framework = TestMethodDetector.detectFramework(node);
                    if (framework != null) {
                        classInfo.put("framework", framework);
                    }

                    testClasses.add(classInfo);
                }

                return true;
            }
        });

        return testClasses;
    }

    private Map<String, Object> checkTestMethod(MethodDeclaration method, CompilationUnit ast,
                                                 Path file, IJdtService service) {
        if (!TestMethodDetector.isTestMethod(method)) {
            return null;
        }

        Map<String, Object> testInfo = new LinkedHashMap<>();
        testInfo.put("name", method.getName().getIdentifier());

        int methodLine = ast.getLineNumber(method.getName().getStartPosition()) - 1;
        testInfo.put("line", methodLine);

        String displayName = TestMethodDetector.displayName(method);
        if (displayName != null) {
            testInfo.put("displayName", displayName);
        }
        if (TestMethodDetector.isDisabled(method)) {
            testInfo.put("disabled", true);
        }

        return testInfo;
    }

    private boolean matchesGlob(String name, String pattern) {
        String regex = pattern.replace("*", ".*").replace("?", ".");
        return name.matches("(?i)" + regex);
    }
}
