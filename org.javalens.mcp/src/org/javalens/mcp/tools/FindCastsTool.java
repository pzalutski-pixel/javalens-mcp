package org.javalens.mcp.tools;

import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Find all casts to a type ({@code (Foo) x} expressions).
 *
 * <p>JDT-unique capability: uses {@code CAST_TYPE_REFERENCE} to find only
 * cast expressions, not other type references. LSP cannot distinguish these.
 */
public class FindCastsTool extends AbstractFineGrainReferenceTool {

    public FindCastsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_casts";
    }

    @Override
    public String getDescription() {
        return """
            Find all casts to a type ((Foo) x expressions).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified type name
            OUTPUT: All locations where casting to this type occurs

            Useful for:
            - Identifying unsafe downcasts
            - Finding refactoring opportunities (replace cast with polymorphism)
            - Understanding type conversion patterns

            Requires load_project to be called first.
            """;
    }

    @Override
    protected SearchService.ReferenceKind getReferenceKind() {
        return SearchService.ReferenceKind.CAST;
    }

    @Override
    protected String getTypeNameParamDescription() {
        return "Fully qualified type name to find casts to";
    }

    @Override
    protected String getAdvice() {
        return "Casts may indicate design issues - consider polymorphism";
    }

    @Override
    protected List<String> getSuggestedNextTools() {
        return List.of(
            "find_instanceof_checks to find related type checks",
            "get_type_hierarchy to understand inheritance"
        );
    }
}
