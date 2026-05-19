package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find places where types or methods are referenced dynamically via Java reflection API.
 * These usages are invisible to static reference searches like find_references.
 */
public class FindReflectionUsageTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindReflectionUsageTool.class);

    // (declaring type, method name). All overloads of each name are searched, so e.g.
    // both Class.forName(String) and Class.forName(String, boolean, ClassLoader) are caught.
    private static final String[][] REFLECTION_METHODS = {
        {"java.lang.Class", "forName"},
        {"java.lang.Class", "getMethod"},
        {"java.lang.Class", "getDeclaredMethod"},
        {"java.lang.Class", "getMethods"},
        {"java.lang.Class", "getDeclaredMethods"},
        {"java.lang.Class", "getField"},
        {"java.lang.Class", "getDeclaredField"},
        {"java.lang.Class", "getFields"},
        {"java.lang.Class", "getDeclaredFields"},
        {"java.lang.Class", "getConstructor"},
        {"java.lang.Class", "getDeclaredConstructor"},
        {"java.lang.Class", "getConstructors"},
        {"java.lang.Class", "getDeclaredConstructors"},
        {"java.lang.Class", "newInstance"},
        {"java.lang.reflect.Method", "invoke"},
        {"java.lang.reflect.Field", "get"},
        {"java.lang.reflect.Field", "set"},
        {"java.lang.reflect.Constructor", "newInstance"},
    };

    public FindReflectionUsageTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_reflection_usage";
    }

    @Override
    public String getDescription() {
        return """
            Find places where Java reflection API is used.

            USAGE: find_reflection_usage()
            OUTPUT: All reflection calls grouped by method type

            Detects calls to:
            - Class.forName(), Class.newInstance()
            - Class.getMethod/getDeclaredMethod/getField/getDeclaredField
            - Class.getConstructor/getDeclaredConstructor
            - Method.invoke(), Field.get/set(), Constructor.newInstance()

            These usages are invisible to static reference searches
            and can break when types or methods are renamed.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("maxResults", "integer", "Maximum results per reflection method (default 100)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 100);

        try {
            List<Map<String, Object>> allCalls = new ArrayList<>();
            Map<String, Integer> summary = new LinkedHashMap<>();
            int grandTotalEncountered = 0;

            for (String[] entry : REFLECTION_METHODS) {
                String typeName = entry[0];
                String methodName = entry[1];
                String label = typeName.substring(typeName.lastIndexOf('.') + 1) + "." + methodName;

                try {
                    IType type = service.findType(typeName);
                    if (type == null) continue;

                    int forThisLabel = 0;
                    int labelTotalEncountered = 0;
                    // Iterate all overloads of methodName on this type. The documented
                    // maxResults is PER REFLECTION METHOD (per label), not per overload.
                    // Track total matches per label so truncated reports accurately:
                    // comparing forThisLabel to maxResults misreports the exact-equal case.
                    labelLoop:
                    for (IMethod method : type.getMethods()) {
                        if (!methodName.equals(method.getElementName())) continue;
                        if (!method.exists()) continue;

                        org.javalens.core.search.SearchResult result =
                            service.getSearchService().findAllReferences(method, maxResults);
                        labelTotalEncountered += result.totalEncountered();

                        List<Map<String, Object>> formatted = formatMatches(result.matches(), service);
                        for (Map<String, Object> match : formatted) {
                            if (forThisLabel >= maxResults) break labelLoop;
                            match.put("reflectionMethod", label);
                            allCalls.add(match);
                            forThisLabel++;
                        }
                    }

                    if (forThisLabel > 0) {
                        summary.put(label, forThisLabel);
                    }
                    grandTotalEncountered += labelTotalEncountered;
                } catch (Exception e) {
                    log.debug("Could not scan for {}.{}: {}", typeName, methodName, e.getMessage());
                }
            }

            boolean truncated = grandTotalEncountered > allCalls.size();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalCalls", grandTotalEncountered);
            data.put("summary", summary);
            data.put("reflectionCalls", allCalls);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(grandTotalEncountered)
                .returnedCount(allCalls.size())
                .truncated(truncated)
                .suggestedNextTools(List.of(
                    "analyze_change_impact to assess risk of renaming reflected types",
                    "get_symbol_info at a reflection call site for context"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
