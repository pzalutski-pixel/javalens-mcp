package org.javalens.mcp.tools;

import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Find all usages of an annotation type.
 *
 * <p>JDT-unique capability: uses {@code ANNOTATION_TYPE_REFERENCE} to find
 * only annotation usages, not other type references. LSP cannot distinguish
 * these.
 *
 * <p>Note: input param is named {@code typeName} (not the previous
 * {@code annotation}) for consistency with the other 6 find_* tools.
 */
public class FindAnnotationUsagesTool extends AbstractFineGrainReferenceTool {

    public FindAnnotationUsagesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_annotation_usages";
    }

    @Override
    public String getDescription() {
        return """
            Find all usages of an annotation type in the project.

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified annotation name as `typeName`
            OUTPUT: All locations where the annotation is applied

            Examples:
            - find_annotation_usages(typeName="org.springframework.beans.factory.annotation.Autowired")
            - find_annotation_usages(typeName="org.junit.jupiter.api.Test")
            - find_annotation_usages(typeName="javax.persistence.Entity")

            Requires load_project to be called first.
            """;
    }

    @Override
    protected SearchService.ReferenceKind getReferenceKind() {
        return SearchService.ReferenceKind.ANNOTATION;
    }

    @Override
    protected String getTypeNameParamDescription() {
        return "Fully qualified annotation type name (e.g., 'org.springframework.beans.factory.annotation.Autowired')";
    }

    @Override
    protected String getAdvice() {
        return null;
    }

    @Override
    protected List<String> getSuggestedNextTools() {
        return List.of(
            "get_symbol_info at a usage location for details",
            "find_references for all references (not just annotations)"
        );
    }
}
