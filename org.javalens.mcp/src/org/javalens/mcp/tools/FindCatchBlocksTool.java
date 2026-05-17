package org.javalens.mcp.tools;

import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Find all catch blocks for an exception type ({@code catch(Foo e)}).
 *
 * <p>JDT-unique capability: uses {@code CATCH_TYPE_REFERENCE} to find only
 * catch blocks, not other type references. LSP cannot distinguish these.
 *
 * <p>Note: input param is named {@code typeName} (not the previous
 * {@code exceptionType}) for consistency with the other 6 find_* tools.
 */
public class FindCatchBlocksTool extends AbstractFineGrainReferenceTool {

    public FindCatchBlocksTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_catch_blocks";
    }

    @Override
    public String getDescription() {
        return """
            Find all catch blocks for an exception type (catch(ExceptionType e)).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified exception type name as `typeName`
            OUTPUT: All catch blocks that handle this exception type

            Useful for:
            - Understanding exception handling patterns
            - Finding all handlers for a specific exception
            - Exception handling analysis and refactoring

            Requires load_project to be called first.
            """;
    }

    @Override
    protected SearchService.ReferenceKind getReferenceKind() {
        return SearchService.ReferenceKind.CATCH;
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
            "find_throws_declarations to find sources of this exception",
            "get_type_hierarchy to see exception hierarchy"
        );
    }
}
