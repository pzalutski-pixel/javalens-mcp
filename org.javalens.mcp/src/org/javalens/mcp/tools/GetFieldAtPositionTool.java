package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Get field information at a specific position.
 */
public class GetFieldAtPositionTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetFieldAtPositionTool.class);

    public GetFieldAtPositionTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_field_at_position";
    }

    @Override
    public String getDescription() {
        return """
            Get field information at a specific position.

            USAGE: Position on a field reference or declaration
            OUTPUT: Field type, modifiers, constant value if applicable

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            )
        ));
        schema.put("required", List.of("filePath", "line", "column"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required parameter missing");
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            IField field = null;
            if (element instanceof IField f) {
                field = f;
            } else {
                field = (IField) element.getAncestor(IJavaElement.FIELD);
            }

            if (field == null) {
                return ToolResponse.symbolNotFound("No field found at position");
            }

            Map<String, Object> data = createFieldInfo(field, service);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "find_references to find all usages",
                    "find_field_writes to find write accesses",
                    "get_type_at_position for field type info"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting field at position: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createFieldInfo(IField field, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("name", field.getElementName());
        info.put("type", Signature.getSimpleName(Signature.toString(field.getTypeSignature())));

        int flags = field.getFlags();
        info.put("modifiers", getModifiers(flags));

        info.put("isEnumConstant", field.isEnumConstant());

        boolean isConstant = Flags.isStatic(flags) && Flags.isFinal(flags);
        info.put("isConstant", isConstant);

        // Get constant value if available
        Object constant = field.getConstant();
        if (constant != null) {
            info.put("constantValue", constant.toString());
        }

        // Declaring type
        IType declaringType = field.getDeclaringType();
        if (declaringType != null) {
            info.put("declaringType", declaringType.getFullyQualifiedName());
        }

        // Source location
        ICompilationUnit cu = field.getCompilationUnit();
        if (cu != null && cu.getResource() != null) {
            IPath location = cu.getResource().getLocation();
            if (location != null) {
                info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }

            if (field.getSourceRange() != null) {
                int offset = field.getSourceRange().getOffset();
                info.put("line", service.getLineNumber(cu, offset));
            }
        }

        return info;
    }

    private List<String> getModifiers(int flags) {
        List<String> modifiers = new ArrayList<>();
        if (Flags.isPublic(flags)) modifiers.add("public");
        if (Flags.isProtected(flags)) modifiers.add("protected");
        if (Flags.isPrivate(flags)) modifiers.add("private");
        if (Flags.isStatic(flags)) modifiers.add("static");
        if (Flags.isFinal(flags)) modifiers.add("final");
        if (Flags.isTransient(flags)) modifiers.add("transient");
        if (Flags.isVolatile(flags)) modifiers.add("volatile");
        return modifiers;
    }
}
