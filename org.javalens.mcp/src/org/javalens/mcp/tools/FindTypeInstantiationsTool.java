package org.javalens.mcp.tools;

import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Find all instantiations of a type ({@code new Foo()} calls).
 *
 * <p>JDT-unique capability: uses {@code CLASS_INSTANCE_CREATION_TYPE_REFERENCE}
 * to find only instantiations, not other type references. LSP cannot
 * distinguish these.
 */
public class FindTypeInstantiationsTool extends AbstractFineGrainReferenceTool {

    public FindTypeInstantiationsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_type_instantiations";
    }

    @Override
    public String getDescription() {
        return """
            Find all instantiations of a type (new Foo() calls).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified type name
            OUTPUT: All locations where the type is instantiated with 'new'

            Useful for:
            - Understanding object creation patterns
            - Identifying factory method candidates
            - Finding coupling points

            Requires load_project to be called first.
            """;
    }

    @Override
    protected SearchService.ReferenceKind getReferenceKind() {
        return SearchService.ReferenceKind.INSTANTIATION;
    }

    @Override
    protected String getTypeNameParamDescription() {
        return "Fully qualified type name (e.g., 'java.util.ArrayList')";
    }

    @Override
    protected String getAdvice() {
        return null;
    }

    @Override
    protected List<String> getSuggestedNextTools() {
        return List.of(
            "get_type_hierarchy to understand inheritance",
            "find_references for all references"
        );
    }
}
