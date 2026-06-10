package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.refactoring.descriptors.ExtractSuperclassDescriptor;
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
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract a new superclass from a class, moving a member up into it. Drives
 * JDT's extract-supertype refactoring via its public descriptor; the new
 * superclass arrives as file content, the source class's edits as text —
 * nothing is applied.
 */
public class ExtractSuperclassTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractSuperclassTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractSuperclassTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "extract_superclass";
    }

    @Override
    public String getDescription() {
        return """
            Extract a new superclass from a class: the member at the position
            moves up into a newly created superclass, and the class extends it.

            USAGE: Position on the member to extract; provide the new superclass name.
            OUTPUT: createdFiles carries the new superclass file content;
            editsByFile carries the source class's edits. Nothing is written -
            create the file and apply the edits yourself.

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
            .required("superclassName", "string", "Name for the new superclass")
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
        String superclassName = getStringParam(arguments, "superclassName");
        if (superclassName == null || superclassName.isBlank()) {
            return ToolResponse.invalidParameter("superclassName", "Required");
        }
        if (!isValidJavaIdentifier(superclassName)) {
            return ToolResponse.invalidParameter("superclassName", "Not a valid Java identifier");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);
            if (!(element instanceof IMember member) || element instanceof IType) {
                return ToolResponse.invalidParameter("position", "No method or field at position");
            }
            IType declaringType = member.getDeclaringType();

            Map<String, String> descriptorArguments = new HashMap<>();
            descriptorArguments.put("input", declaringType.getHandleIdentifier());
            descriptorArguments.put("name", superclassName);
            descriptorArguments.put("extract", "1");
            descriptorArguments.put("element1", member.getHandleIdentifier());
            descriptorArguments.put("delete", "1");
            descriptorArguments.put("element2", member.getHandleIdentifier());
            descriptorArguments.put("types", "1");
            descriptorArguments.put("element3", declaringType.getHandleIdentifier());
            descriptorArguments.put("stubs", "0");
            descriptorArguments.put("abstract", "0");
            descriptorArguments.put("replace", "false");
            descriptorArguments.put("instanceof", "false");

            ExtractSuperclassDescriptor descriptor = new ExtractSuperclassDescriptor(
                member.getJavaProject().getElementName(),
                "Extract superclass " + superclassName,
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
            data.put("superclassName", superclassName);
            data.put("memberName", member.getElementName());
            data.put("fromType", declaringType.getFullyQualifiedName());
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
                    "Create the new superclass file from createdFiles",
                    "Apply the text edits to complete the extraction",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error extracting superclass: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return !RESERVED_WORDS.contains(name);
    }
}
