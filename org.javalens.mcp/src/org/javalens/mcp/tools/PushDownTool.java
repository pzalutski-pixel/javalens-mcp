package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.refactoring.descriptors.PushDownDescriptor;
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
 * Push a member down into the direct subclasses, removing it from the
 * declaring class. Drives JDT's push-down refactoring via its public
 * descriptor; edits are returned as text, never applied.
 */
public class PushDownTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(PushDownTool.class);

    public PushDownTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "push_down";
    }

    @Override
    public String getDescription() {
        return """
            Push a method or field down into the declaring class's subclasses
            and remove it from the declaring class.

            USAGE: Position on the member name in the superclass.
            OUTPUT: editsByFile covering the superclass (member removed) and each
            subclass (member added); warnings from JDT's condition checking.
            Edits are returned as text - apply them yourself.

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
            if (!(element instanceof IMember member) || element instanceof IType) {
                return ToolResponse.invalidParameter("position", "No method or field at position");
            }

            IType declaringType = member.getDeclaringType();
            ITypeHierarchy hierarchy = declaringType.newTypeHierarchy(null);
            IType[] subclasses = hierarchy.getSubclasses(declaringType);
            if (subclasses == null || subclasses.length == 0) {
                return ToolResponse.invalidParameter("member",
                    "Declaring type has no subclasses to push into");
            }

            Map<String, String> descriptorArguments = new HashMap<>();
            descriptorArguments.put("input", declaringType.getHandleIdentifier());
            descriptorArguments.put("element1", member.getHandleIdentifier());

            PushDownDescriptor descriptor = new PushDownDescriptor(
                member.getJavaProject().getElementName(),
                "Push down " + member.getElementName(),
                null,
                descriptorArguments,
                RefactoringDescriptor.STRUCTURAL_CHANGE | RefactoringDescriptor.MULTI_CHANGE);

            RefactoringInvoker.Outcome outcome = RefactoringInvoker.run(descriptor, service);
            if (outcome.refused()) {
                return ToolResponse.invalidParameter("member",
                    String.join("; ", outcome.reasons()));
            }

            int totalEdits = outcome.editsByFile().values().stream().mapToInt(List::size).sum();

            List<String> targetTypes = new ArrayList<>();
            for (IType subclass : subclasses) {
                targetTypes.add(subclass.getFullyQualifiedName());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("memberName", member.getElementName());
            data.put("fromType", declaringType.getFullyQualifiedName());
            data.put("toTypes", targetTypes);
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
                    "Apply the text edits to complete the push-down",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error pushing down member: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
