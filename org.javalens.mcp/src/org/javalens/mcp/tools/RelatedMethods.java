package org.javalens.mcp.tools;

import org.eclipse.jdt.core.IMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * The methods a refactoring changes together with a method whose name or signature it changes:
 * {@code SearchService.rippleOf}, every method the method overrides, every method that overrides
 * it and every method a subtype inherits together with it, repeated until none is new. A method of
 * a class file cannot change with the others, so a method related to one is refused, as JDT's
 * rename refuses it (RenameMethodProcessor.checkRelatedMethods :512-530, with
 * RenameMethodRefactoring_no_binary, refactoring.properties:473: "Related method ''{0}''
 * (declared in ''{1}'') is binary. Refactoring cannot be performed.").
 */
final class RelatedMethods {

    private RelatedMethods() {
    }

    /**
     * Why {@code method} cannot change with {@code ripple}, its related methods: each method of a
     * class file among them, in their order, as "related method m (declared in pkg.Type) is
     * binary"; null when there is none.
     */
    static String binaryRefusal(IMethod method, List<IMethod> ripple) {
        List<String> reasons = new ArrayList<>();
        for (IMethod related : ripple) {
            if (!related.equals(method) && related.isBinary()) {
                reasons.add("related method " + related.getElementName() + " (declared in "
                    + ElementNames.qualifiedName(related.getDeclaringType()) + ") is binary");
            }
        }
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }
}
