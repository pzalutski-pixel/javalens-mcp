package org.javalens.mcp.tools;

import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Find all {@code instanceof} checks for a type ({@code x instanceof Foo}).
 *
 * <p>JDT-unique capability: uses {@code INSTANCEOF_TYPE_REFERENCE} to find
 * only instanceof checks, not other type references. LSP cannot distinguish
 * these.
 */
public class FindInstanceofChecksTool extends AbstractFineGrainReferenceTool {

    public FindInstanceofChecksTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_instanceof_checks";
    }

    @Override
    public String getDescription() {
        return """
            Find all instanceof checks for a type (x instanceof Foo).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified type name
            OUTPUT: All locations where instanceof checks against this type occur

            Useful for:
            - Identifying type checking patterns
            - Finding polymorphism opportunities (replace instanceof with virtual dispatch)
            - Understanding type discrimination logic

            Requires load_project to be called first.
            """;
    }

    @Override
    protected SearchService.ReferenceKind getReferenceKind() {
        return SearchService.ReferenceKind.INSTANCEOF;
    }

    @Override
    protected String getTypeNameParamDescription() {
        return "Fully qualified type name to find instanceof checks for";
    }

    @Override
    protected String getAdvice() {
        return "Consider visitor pattern or polymorphism to replace instanceof chains";
    }

    @Override
    protected List<String> getSuggestedNextTools() {
        return List.of(
            "find_casts to find related cast expressions",
            "get_type_hierarchy to understand inheritance"
        );
    }
}
