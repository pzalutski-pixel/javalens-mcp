package org.javalens.mcp.tools;

import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Find all {@code throws} declarations of an exception type in method
 * signatures.
 *
 * <p>JDT-unique capability: uses {@code THROWS_CLAUSE_TYPE_REFERENCE} to find
 * only throws declarations, not other type references. LSP cannot distinguish
 * these.
 *
 * <p>Note: input param is named {@code typeName} (not the previous
 * {@code exceptionType}) for consistency with the other 6 find_* tools, per
 * the {@code feedback_no_backwards_compat_for_ai_consumers} memory.
 */
public class FindThrowsDeclarationsTool extends AbstractFineGrainReferenceTool {

    public FindThrowsDeclarationsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_throws_declarations";
    }

    @Override
    public String getDescription() {
        return """
            Find all throws declarations of an exception type in method signatures.

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified exception type name as `typeName`
            OUTPUT: All methods that declare 'throws ExceptionType'

            Useful for:
            - Understanding exception flow in the codebase
            - Finding all methods that can throw a specific exception
            - Exception handling analysis

            Requires load_project to be called first.
            """;
    }

    @Override
    protected SearchService.ReferenceKind getReferenceKind() {
        return SearchService.ReferenceKind.THROWS_CLAUSE;
    }

    @Override
    protected String getTypeNameParamDescription() {
        return "Fully qualified exception type name (e.g., 'java.io.IOException')";
    }

    @Override
    protected String getAdvice() {
        return null;
    }

    @Override
    protected List<String> getSuggestedNextTools() {
        return List.of(
            "find_catch_blocks to find handlers for this exception",
            "get_type_hierarchy to see exception hierarchy"
        );
    }
}
