package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.refactoring.descriptors.ConvertMemberTypeDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.rewrite.RefactoringInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Move a member (nested) type to its own top-level file. Drives JDT's
 * convert-member-type refactoring via its public descriptor; the new file
 * arrives as content, the enclosing type's edits as text — nothing is applied.
 */
public class MoveTypeToNewFileTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(MoveTypeToNewFileTool.class);

    public MoveTypeToNewFileTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "move_type_to_new_file";
    }

    @Override
    public String getDescription() {
        return """
            Move a member (nested) type into its own top-level file. Non-static
            member types gain a field referencing the former enclosing instance.

            USAGE: Position on the nested type's name.
            OUTPUT: createdFiles carries the new top-level file content;
            editsByFile carries the enclosing file's edits. Nothing is written -
            create the file and apply the edits yourself.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file containing the nested type")
            .required("line", "integer", "Zero-based line number of the nested type declaration")
            .required("column", "integer", "Zero-based column number (on the type name)")
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
            if (!(element instanceof IType type)) {
                return ToolResponse.invalidParameter("position", "No type at position");
            }
            if (type.getDeclaringType() == null) {
                return ToolResponse.invalidParameter("type",
                    "Type is already top-level; only member (nested) types can be moved out");
            }

            // No enclosing-instance field/parameter is introduced: the moved
            // type keeps only what it had (static nested types need none, and
            // for inner types JDT reports unresolved enclosing references in
            // the condition check rather than silently breaking).
            Map<String, String> descriptorArguments = new HashMap<>();
            descriptorArguments.put("input", type.getHandleIdentifier());
            descriptorArguments.put("field", "false");
            descriptorArguments.put("final", "false");
            descriptorArguments.put("mandatory", "false");
            descriptorArguments.put("possible", "false");

            ConvertMemberTypeDescriptor descriptor = new ConvertMemberTypeDescriptor(
                type.getJavaProject().getElementName(),
                "Move " + type.getElementName() + " to a new file",
                null,
                descriptorArguments,
                RefactoringDescriptor.STRUCTURAL_CHANGE | RefactoringDescriptor.MULTI_CHANGE);

            RefactoringInvoker.Outcome outcome = RefactoringInvoker.run(descriptor, service);
            if (outcome.refused()) {
                return ToolResponse.invalidParameter("type",
                    String.join("; ", outcome.reasons()));
            }

            int totalEdits = outcome.editsByFile().values().stream().mapToInt(List::size).sum();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", ElementNames.typeName(type));
            data.put("fromType", ElementNames.qualifiedName(type.getDeclaringType()));
            data.put("createdFiles", outcome.createdFiles());
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
                    "Create the new file from createdFiles",
                    "Apply the text edits to complete the move",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error moving type to new file: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
