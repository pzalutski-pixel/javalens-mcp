package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.refactoring.descriptors.EncapsulateFieldDescriptor;
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
 * Encapsulate a field: generate getter/setter and rewrite all direct accesses
 * to go through them. Drives JDT's self-encapsulate-field refactoring via its
 * public descriptor; edits are returned as text, never applied.
 */
public class EncapsulateFieldTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(EncapsulateFieldTool.class);

    public EncapsulateFieldTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "encapsulate_field";
    }

    @Override
    public String getDescription() {
        return """
            Encapsulate a field: generate a getter/setter pair and rewrite all
            direct accesses (in this and other files) to go through them.

            USAGE: Position on the field name; optionally name the accessors.
            OUTPUT: editsByFile with all required text edits; warnings from JDT's
            own condition checking. Edits are returned as text - apply them yourself.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file containing the field")
            .required("line", "integer", "Zero-based line number of the field declaration")
            .required("column", "integer", "Zero-based column number (on the field name)")
            .optional("getterName", "string", "Getter name (default: getX for field x)")
            .optional("setterName", "string", "Setter name (default: setX for field x)")
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
            if (!(element instanceof IField field)) {
                return ToolResponse.invalidParameter("position", "No field at position");
            }

            String fieldName = field.getElementName();
            String capitalized = Character.toUpperCase(fieldName.charAt(0))
                + (fieldName.length() > 1 ? fieldName.substring(1) : "");
            String getterName = getStringParam(arguments, "getterName", "get" + capitalized);
            String setterName = getStringParam(arguments, "setterName", "set" + capitalized);

            // The descriptor's argument keys are JDT's refactoring-script format
            // (discovered via its own missing-argument validation): accessors are
            // public, no generated comments, declaring-class accesses keep direct
            // field access, accessors appended as the last members.
            Map<String, String> descriptorArguments = new HashMap<>();
            descriptorArguments.put("input", field.getHandleIdentifier());
            descriptorArguments.put("getter", getterName);
            descriptorArguments.put("setter", setterName);
            descriptorArguments.put("declaring", "false");
            descriptorArguments.put("comments", "false");
            descriptorArguments.put("insertion", "0");
            descriptorArguments.put("visibility", "1");

            EncapsulateFieldDescriptor descriptor = new EncapsulateFieldDescriptor(
                field.getJavaProject().getElementName(),
                "Encapsulate " + fieldName,
                null,
                descriptorArguments,
                RefactoringDescriptor.STRUCTURAL_CHANGE | RefactoringDescriptor.MULTI_CHANGE);

            RefactoringInvoker.Outcome outcome = RefactoringInvoker.run(descriptor, service);
            if (outcome.refused()) {
                return ToolResponse.invalidParameter("field",
                    String.join("; ", outcome.reasons()));
            }

            int totalEdits = outcome.editsByFile().values().stream().mapToInt(List::size).sum();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fieldName", fieldName);
            data.put("getterName", getterName);
            data.put("setterName", setterName);
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
                    "Apply the text edits to complete the encapsulation",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error encapsulating field: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
