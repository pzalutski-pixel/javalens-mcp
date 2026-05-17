package org.javalens.mcp.tools;

import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Find all type argument usages ({@code List<Foo>}, {@code Map<K, Foo>}).
 *
 * <p>JDT-unique capability: uses {@code TYPE_ARGUMENT_TYPE_REFERENCE} to find
 * only generic type arguments, not other type references. LSP cannot
 * distinguish these.
 */
public class FindTypeArgumentsTool extends AbstractFineGrainReferenceTool {

    public FindTypeArgumentsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_type_arguments";
    }

    @Override
    public String getDescription() {
        return """
            Find all usages of a type as a generic type argument (List<Foo>, Map<K, Foo>).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified type name
            OUTPUT: All locations where the type is used as a generic argument

            Useful for:
            - Understanding generic usage patterns
            - Finding all collections/containers of a type
            - API design analysis

            Requires load_project to be called first.
            """;
    }

    @Override
    protected SearchService.ReferenceKind getReferenceKind() {
        return SearchService.ReferenceKind.TYPE_ARGUMENT;
    }

    @Override
    protected String getTypeNameParamDescription() {
        return "Fully qualified type name to find in generic arguments";
    }

    @Override
    protected String getAdvice() {
        return null;
    }

    @Override
    protected List<String> getSuggestedNextTools() {
        return List.of(
            "find_references for all type references",
            "find_type_instantiations to find object creation"
        );
    }
}
