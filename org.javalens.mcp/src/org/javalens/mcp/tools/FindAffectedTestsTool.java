package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.javalens.core.IJdtService;
import org.javalens.core.graph.ProjectGraph;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Connect a symbol to the tests that exercise it: reverse closure over the
 * project graph from the symbol, filtered to detected test methods. Answers
 * "which tests should I run after changing this?".
 */
public class FindAffectedTestsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindAffectedTestsTool.class);

    public FindAffectedTestsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_affected_tests";
    }

    @Override
    public String getDescription() {
        return """
            Find the test methods that exercise a symbol, directly or
            transitively.

            USAGE: find_affected_tests(filePath="path/to/File.java", line=10, column=5)
            OUTPUT: Test methods (JUnit 4/5, TestNG) from which the symbol is
            reachable, with locations - the set of tests to run after changing it.

            The caller walk follows calls, instantiations, field accesses, and
            override declarations (a test calling through an interface or
            superclass covers the implementation). Non-test intermediate
            methods are walked through but not reported. Disabled tests are
            included with disabled=true (they cover the code but will not run).
            A symbol no test reaches returns an empty set.

            Supports project methods, fields, and types as the target symbol.

            Options:
            - maxResults: cap the reported list (default 100)

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "File containing the symbol")
            .required("line", "integer", "Zero-based line number")
            .required("column", "integer", "Zero-based column number")
            .optional("maxResults", "integer", "Maximum test methods to return (default 100)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse paramCheck = requireParam(arguments, "filePath");
        if (paramCheck != null) return paramCheck;

        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", 0);
        int column = getIntParam(arguments, "column", 0);
        int maxResults = getIntParam(arguments, "maxResults", 100);
        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults", "must be >= 0");
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (element == null) {
                return ToolResponse.symbolNotFound(
                    "No symbol found at " + filePathStr + ":" + line + ":" + column);
            }

            ProjectGraph graph = service.getProjectGraphService().getGraph();
            String key = graph.keyOf(element);
            if (key == null || graph.node(key) == null) {
                return ToolResponse.invalidParameter("position",
                    "affected-test analysis supports project methods, fields, and types; "
                        + "no graph node for '" + element.getElementName() + "'");
            }

            Set<String> affected = new HashSet<>(graph.transitiveCallers(key));
            affected.add(key); // a test method covers itself

            List<Map<String, Object>> entries = new ArrayList<>();
            for (TestMethodDetector.TestMethod test : TestMethodDetector.collectTestMethods(service)) {
                if (test.element() == null) {
                    continue;
                }
                String testKey = graph.keyOf(test.element());
                if (testKey == null || !affected.contains(testKey)) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("className", test.className());
                entry.put("methodName", test.methodName());
                entry.put("key", testKey);
                entry.put("filePath", service.getPathUtils().formatPath(test.file()));
                entry.put("line", test.line());
                if (test.framework() != null) {
                    entry.put("framework", test.framework());
                }
                if (test.disabled()) {
                    entry.put("disabled", true);
                }
                entries.add(entry);
            }
            entries.sort(Comparator
                .comparing((Map<String, Object> e) -> (String) e.get("filePath"))
                .thenComparing(e -> (Integer) e.get("line")));

            int total = entries.size();
            boolean truncated = total > maxResults;
            List<Map<String, Object>> returned = truncated ? entries.subList(0, maxResults) : entries;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", element.getElementName());
            data.put("symbolType", element.getClass().getSimpleName().replace("Sourced", "").replace("Impl", ""));
            data.put("testMethodCount", total);
            data.put("testMethods", returned);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(returned.size())
                .truncated(truncated)
                .suggestedNextTools(List.of(
                    "analyze_change_impact with transitive=true for the full blast radius",
                    "find_tests for the project's whole test inventory"))
                .build());

        } catch (Exception e) {
            log.error("Error finding affected tests: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
