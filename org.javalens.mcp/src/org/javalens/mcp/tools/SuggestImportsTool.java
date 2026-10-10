package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find import candidates for an unresolved type name.
 *
 * Searches across project sources, JDK, and libraries to find types
 * matching the simple name, then ranks them by relevance.
 */
public class SuggestImportsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(SuggestImportsTool.class);

    public SuggestImportsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "suggest_imports";
    }

    @Override
    public String getDescription() {
        return """
            Find import candidates for unresolved type.

            USAGE: suggest_imports(typeName="List")
            OUTPUT: List of matching types with fully qualified names and relevance

            Searches project sources, JDK, and libraries for types matching
            the simple name. Every candidate is ranked by relevance (java.util
            types ranked higher than java.awt, etc.; equally relevant ones by
            fully qualified name), then the first maxResults are returned.
            totalCandidates and meta.totalCount count every candidate;
            meta.truncated is true when candidates are left out.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("typeName", "string", "Simple type name to find imports for (e.g., 'List', 'Map')")
            .optional("maxResults", "integer", "Maximum candidates to return (default 20)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        int maxResults = getIntParam(arguments, "maxResults", 20);

        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName", "Required");
        }
        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults",
                "Must be >= 0; got: " + maxResults);
        }

        try {
            List<Map<String, Object>> candidates = new ArrayList<>();

            // Create search scope including sources, JDK, and libraries
            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                new IJavaElement[] { service.getJavaProject() },
                IJavaSearchScope.SOURCES |
                IJavaSearchScope.APPLICATION_LIBRARIES |
                IJavaSearchScope.SYSTEM_LIBRARIES
            );

            SearchEngine engine = new SearchEngine();

            // Collect every matching type: the most relevant can come last in search order. Each
            // is read once the search is done, so a type JDT cannot read fails the answer.
            List<IType> matched = new ArrayList<>();
            TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {
                @Override
                public void acceptTypeNameMatch(TypeNameMatch match) {
                    matched.add(match.getType());
                }
            };

            // Search for exact type name match
            engine.searchAllTypeNames(
                null,  // any package
                SearchPattern.R_EXACT_MATCH,
                typeName.toCharArray(),
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                IJavaSearchConstants.TYPE,
                scope,
                requestor,
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                Deadline.monitor()
            );

            for (IType type : matched) {
                String fqn = type.getFullyQualifiedName();
                String packageName = type.getPackageFragment().getElementName();

                // An import names a type by its package (JLS 7.5): a type in the
                // default package cannot be imported.
                if (packageName.isEmpty()) {
                    continue;
                }

                // Skip internal/impl packages
                if (packageName.contains(".internal.") ||
                    packageName.contains(".impl.") ||
                    packageName.startsWith("sun.") ||
                    packageName.startsWith("com.sun.")) {
                    continue;
                }

                int relevance = calculateRelevance(fqn, packageName);

                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("fullyQualifiedName", fqn);
                candidate.put("packageName", packageName);
                candidate.put("simpleName", ElementNames.typeName(type));
                candidate.put("relevance", relevance);
                candidate.put("isInterface", type.isInterface());
                candidate.put("isClass", type.isClass());
                candidate.put("isEnum", type.isEnum());
                // An import names a member type by its canonical name, dots between
                // the enclosing type and the member type (JLS 6.7, 7.5.1).
                candidate.put("fixId", "add_import:" + type.getFullyQualifiedName('.'));

                candidates.add(candidate);
            }

            // Rank every candidate: by relevance (descending), then by fully qualified name
            candidates.sort(Comparator.comparingInt((Map<String, Object> c) -> (int) c.get("relevance"))
                .reversed()
                .thenComparing(c -> (String) c.get("fullyQualifiedName")));

            // Limit results
            int totalCandidates = candidates.size();
            boolean truncated = totalCandidates > maxResults;
            List<Map<String, Object>> result = truncated
                ? candidates.subList(0, maxResults)
                : candidates;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", typeName);
            data.put("totalCandidates", totalCandidates);
            data.put("candidates", result);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalCandidates)
                .returnedCount(result.size())
                .truncated(truncated)
                .suggestedNextTools(List.of(
                    "apply_quick_fix with fixId to add the import",
                    "get_quick_fixes to see all available fixes"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error suggesting imports for '{}': {}", typeName, e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Calculate relevance score for an import candidate.
     * Higher scores indicate more commonly used packages.
     */
    private int calculateRelevance(String fqn, String packageName) {
        // java.util.* is most commonly needed
        if (packageName.equals("java.util")) return 100;
        if (packageName.startsWith("java.util.")) return 95;

        // java.io.* is very common
        if (packageName.equals("java.io")) return 90;
        if (packageName.startsWith("java.io.")) return 85;

        // java.nio.* is common
        if (packageName.equals("java.nio.file")) return 88;
        if (packageName.startsWith("java.nio.")) return 82;

        // java.lang.* (usually auto-imported but sometimes needed)
        if (packageName.equals("java.lang")) return 80;

        // java.time.* is modern API
        if (packageName.startsWith("java.time")) return 85;

        // java.math.* is specialized but standard
        if (packageName.startsWith("java.math")) return 75;

        // Other java.* packages
        if (packageName.startsWith("java.")) return 70;

        // javax.* packages
        if (packageName.startsWith("javax.")) return 65;

        // Common frameworks (Spring, JUnit, etc.)
        if (packageName.startsWith("org.springframework.")) return 60;
        if (packageName.startsWith("org.junit.")) return 60;
        if (packageName.startsWith("org.mockito.")) return 55;
        if (packageName.startsWith("org.slf4j.")) return 55;

        // jakarta.* (newer jakarta EE)
        if (packageName.startsWith("jakarta.")) return 50;

        // org.* packages in general
        if (packageName.startsWith("org.")) return 45;

        // com.* packages
        if (packageName.startsWith("com.")) return 40;

        // Default for other packages
        return 30;
    }
}
