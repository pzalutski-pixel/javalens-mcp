package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find places where types or methods are referenced dynamically via Java reflection API.
 * These usages are invisible to static reference searches like find_references.
 */
public class FindReflectionUsageTool extends AbstractTool {

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

            maxResults caps the calls listed for each reflection method, all its
            overloads together (default 100), in source order: by file, then
            position. summary counts every call of each reflection method, and
            totalCalls and meta.totalCount count every call; meta.truncated is true
            when calls are left out. potentialMatches lists, the first maxResults
            of each reflection method in source order, the calls JDT could not
            confirm, each with its reflection method.

            """ + POTENTIAL_MATCHES + """
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

        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults",
                "Must be >= 0; got: " + maxResults);
        }

        try {
            List<Map<String, Object>> allCalls = new ArrayList<>();
            List<Map<String, Object>> potentialCalls = new ArrayList<>();
            Map<String, Integer> summary = new LinkedHashMap<>();
            int grandTotalEncountered = 0;
            int potentialTotal = 0;

            for (String[] entry : REFLECTION_METHODS) {
                String typeName = entry[0];
                String methodName = entry[1];
                String label = typeName.substring(typeName.lastIndexOf('.') + 1) + "." + methodName;

                // A failed lookup or search fails the answer, never leaves the method's calls out
                IType type = service.findType(typeName);
                if (type == null) continue;

                // Every call of every overload of methodName on this type: maxResults caps
                // the calls listed per reflection method (per label), not per overload,
                // and the label's count is every call.
                List<SearchMatch> labelMatches = new ArrayList<>();
                List<SearchMatch> labelPotential = new ArrayList<>();
                int labelTotal = 0;
                for (IMethod method : type.getMethods()) {
                    if (!methodName.equals(method.getElementName())) continue;
                    if (!method.exists()) continue;

                    // Source-scoped: reflection-method references that matter are
                    // the project's own call sites. The full-scope search leaked
                    // classpath and workspace-metadata (target/work) matches.
                    SearchResult result = service.getSearchService()
                        .findAllReferencesInSources(method, SearchService.ALL_MATCHES);
                    labelTotal += result.totalEncountered();
                    labelMatches.addAll(result.matches());
                    labelPotential.addAll(result.potentialMatches());
                    potentialTotal += result.potentialTotal();
                }

                // The first maxResults calls in source order, whatever overload each calls,
                // and as many of the calls the search could not confirm
                for (Map<String, Object> match : formatMatches(firstInSourceOrder(labelMatches, maxResults), service)) {
                    match.put("reflectionMethod", label);
                    allCalls.add(match);
                }
                for (Map<String, Object> match : formatMatches(firstInSourceOrder(labelPotential, maxResults), service)) {
                    match.put("reflectionMethod", label);
                    potentialCalls.add(match);
                }

                if (labelTotal > 0) {
                    summary.put(label, labelTotal);
                }
                grandTotalEncountered += labelTotal;
            }

            boolean truncated = grandTotalEncountered > allCalls.size();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalCalls", grandTotalEncountered);
            data.put("summary", summary);
            data.put("reflectionCalls", allCalls);
            if (potentialTotal > 0) {
                Map<String, Object> potentialMatches = new LinkedHashMap<>();
                potentialMatches.put("count", potentialTotal);
                potentialMatches.put("locations", potentialCalls);
                data.put("potentialMatches", potentialMatches);
            }

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

    /** The first {@code max} of {@code matches} in source order: by file, then position. */
    private static List<SearchMatch> firstInSourceOrder(List<SearchMatch> matches, int max) {
        matches.sort(Comparator
            .comparing((SearchMatch match) -> match.getResource().getFullPath().toString())
            .thenComparingInt(SearchMatch::getOffset));
        return matches.subList(0, Math.min(max, matches.size()));
    }
}
