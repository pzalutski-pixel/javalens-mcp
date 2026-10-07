package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.refactoring.descriptors.PullUpDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.rewrite.RefactoringInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Pull a member up into the superclass, removing it from the subclass.
 * Drives JDT's pull-up refactoring via its public descriptor; edits are
 * returned as text, never applied. The superclass is the Java model's
 * ({@code SearchService.hierarchyOf}); JDT's pull up reads its own, so a
 * member is refused where the two differ.
 */
public class PullUpTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(PullUpTool.class);

    public PullUpTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "pull_up";
    }

    @Override
    public String getDescription() {
        return """
            Pull a method or field up into the superclass and remove it from the
            declaring subclass.

            USAGE: Position on the member name in the subclass.
            OUTPUT: editsByFile covering the superclass (member added) and the
            subclass (member removed); warnings from JDT's condition checking.
            Edits are returned as text - apply them yourself.

            The superclass is the Java model's, for local and anonymous classes
            in and after lambda bodies too. A member is refused when JDT's own
            type hierarchy leaves that superclass out (a class declared in a
            lambda body, a local class read by the handle of another of the
            same name), naming it: JDT's pull up would act on another class. A
            member is also refused when the superclass has a subclass declared
            in a lambda body, naming it: JDT's pull up cannot read that class.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file containing the member")
            .required("line", "integer", "Zero-based line number of the member declaration")
            .required("column", "integer", "Zero-based column number (on the member name)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return ToolResponse.invalidParameter("line/column", "Must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);
            if (element != null && element.getElementType() == IJavaElement.INITIALIZER) {
                return ToolResponse.invalidParameter("position",
                    "Symbol at position is an initializer, not a method or field to pull up");
            }
            if (element instanceof IType type && type.isAnonymous()) {
                return ToolResponse.invalidParameter("position", "Symbol at position is the anonymous class "
                    + ElementNames.qualifiedName(type) + ", not a method or field to pull up");
            }
            if (!(element instanceof IMember member) || element instanceof IType) {
                return ToolResponse.invalidParameter("position", "No method or field at position");
            }

            IType declaringType = member.getDeclaringType();
            IType superclass = service.getSearchService().hierarchyOf(declaringType).superclass();
            if (superclass == null || !superclass.exists()
                    || "java.lang.Object".equals(superclass.getFullyQualifiedName())) {
                return ToolResponse.invalidParameter("member",
                    "Declaring type has no project superclass to pull into");
            }
            if (superclass.isBinary()) {
                return ToolResponse.invalidParameter("member",
                    "Superclass is binary (library) - cannot pull members into it");
            }
            // JDT's pull up reads the superclass from its own supertype hierarchy of the declaring
            // type (PullUpRefactoringProcessor.getDeclaringSuperTypeHierarchy :2219-2228), which
            // leaves out a class declared in a lambda body and reads a local class by the handle of
            // another class of the same name (HierarchyResolver.rememberAllTypes :511-541,
            // reportHierarchy :558-616): where it differs, JDT's pull up would act on another class.
            if (!superclass.equals(declaringType.newSupertypeHierarchy(null).getSuperclass(declaringType))) {
                return ToolResponse.invalidParameter("position", "Member at position, " + member.getElementName()
                    + " in " + ElementNames.qualifiedName(declaringType) + ", cannot be pulled up: JDT's pull up reads"
                    + " the superclass of " + ElementNames.qualifiedName(declaringType) + " from its own type hierarchy,"
                    + " which leaves out " + ElementNames.qualifiedName(superclass));
            }
            // JDT's pull up also reads the subclasses of the superclass from an IMPLEMENTORS search
            // and fails on a match whose handle does not exist: a class declared in a lambda body.
            List<String> unopened = new ArrayList<>();
            for (IType subclass : service.getSearchService().implementorsWithoutModelHandles(superclass)) {
                unopened.add(ElementNames.qualifiedName(subclass));
            }
            if (!unopened.isEmpty()) {
                return ToolResponse.invalidParameter("position", "Member at position, " + member.getElementName()
                    + " in " + ElementNames.qualifiedName(declaringType) + ", cannot be pulled up: JDT's pull up reads"
                    + " the subclasses of " + ElementNames.qualifiedName(superclass) + " from a search that names "
                    + String.join(", ", unopened) + " by a handle the Java model does not hold");
            }

            // Script argument layout: the pulled members are element1..N with
            // their count in "pull"; the members deleted from subclasses follow
            // as element(N+1).. with their count in "delete".
            Map<String, String> descriptorArguments = new HashMap<>();
            descriptorArguments.put("input", superclass.getHandleIdentifier());
            descriptorArguments.put("pull", "1");
            descriptorArguments.put("element1", member.getHandleIdentifier());
            descriptorArguments.put("delete", "1");
            descriptorArguments.put("element2", member.getHandleIdentifier());
            descriptorArguments.put("stubs", "0");
            descriptorArguments.put("abstract", "0");
            descriptorArguments.put("replace", "false");
            descriptorArguments.put("instanceof", "false");

            PullUpDescriptor descriptor = new PullUpDescriptor(
                member.getJavaProject().getElementName(),
                "Pull up " + member.getElementName(),
                null,
                descriptorArguments,
                RefactoringDescriptor.STRUCTURAL_CHANGE | RefactoringDescriptor.MULTI_CHANGE);

            RefactoringInvoker.Outcome outcome = RefactoringInvoker.run(descriptor, service);
            if (outcome.refused()) {
                return ToolResponse.invalidParameter("member",
                    String.join("; ", outcome.reasons()));
            }

            int totalEdits = outcome.editsByFile().values().stream().mapToInt(List::size).sum();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("memberName", member.getElementName());
            data.put("fromType", ElementNames.qualifiedName(declaringType));
            data.put("toType", ElementNames.qualifiedName(superclass));
            data.put("totalEdits", totalEdits);
            data.put("filesAffected", outcome.editsByFile().size());
            data.put("editsByFile", outcome.editsByFile());
            if (!outcome.warnings().isEmpty()) {
                data.put("warnings", outcome.warnings());
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalEdits)
                .returnedCount(totalEdits)
                .suggestedNextTools(List.of(
                    "Apply the text edits to complete the pull-up",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error pulling up member: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
