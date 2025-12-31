package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
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
 * Get all symbols (types, methods, fields) in a source file.
 * Returns a hierarchical view of the file's structure.
 */
public class GetDocumentSymbolsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetDocumentSymbolsTool.class);

    public GetDocumentSymbolsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_document_symbols";
    }

    @Override
    public String getDescription() {
        return """
            Get all symbols (types, methods, fields) in a source file.

            USAGE: Provide a file path to get all symbols in that file
            OUTPUT: Hierarchical list of all types, methods, fields, and nested types

            Returns symbols with their locations, kinds, and modifiers.

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
            "includePrivate", Map.of(
                "type", "boolean",
                "description", "Include private members (default true)"
            ),
            "maxResults", Map.of(
                "type", "integer",
                "description", "Maximum symbols to return (default 500)"
            )
        ));
        schema.put("required", List.of("filePath"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required parameter missing");
        }

        boolean includePrivate = getBooleanParam(arguments, "includePrivate", true);
        int maxResults = getIntParam(arguments, "maxResults", 500);
        maxResults = Math.min(Math.max(maxResults, 1), 2000);

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);

            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            List<Map<String, Object>> symbols = new ArrayList<>();
            int[] symbolCount = {0};

            // Get all top-level types
            IType[] types = cu.getTypes();
            for (IType type : types) {
                if (symbolCount[0] >= maxResults) break;
                Map<String, Object> typeSymbol = createTypeSymbol(type, service, cu, includePrivate, maxResults, symbolCount);
                if (typeSymbol != null) {
                    symbols.add(typeSymbol);
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("file", service.getPathUtils().formatPath(filePath));
            data.put("symbols", symbols);
            data.put("totalSymbols", symbolCount[0]);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(symbolCount[0])
                .returnedCount(symbolCount[0])
                .truncated(symbolCount[0] >= maxResults)
                .suggestedNextTools(List.of(
                    "go_to_definition to navigate to a symbol",
                    "find_references to find usages of a symbol",
                    "get_type_members to see members of a specific type"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting document symbols: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createTypeSymbol(IType type, IJdtService service, ICompilationUnit cu,
                                                  boolean includePrivate, int maxResults, int[] symbolCount) {
        try {
            int flags = type.getFlags();
            if (!includePrivate && Flags.isPrivate(flags)) {
                return null;
            }

            symbolCount[0]++;
            if (symbolCount[0] > maxResults) return null;

            Map<String, Object> symbol = new LinkedHashMap<>();
            symbol.put("name", type.getElementName());

            if (type.isInterface()) {
                symbol.put("kind", "Interface");
            } else if (type.isEnum()) {
                symbol.put("kind", "Enum");
            } else if (type.isAnnotation()) {
                symbol.put("kind", "Annotation");
            } else if (type.isRecord()) {
                symbol.put("kind", "Record");
            } else {
                symbol.put("kind", "Class");
            }

            symbol.put("modifiers", getModifiers(flags));

            // Get line number
            int offset = type.getSourceRange().getOffset();
            symbol.put("line", service.getLineNumber(cu, offset));

            // Get children
            List<Map<String, Object>> children = new ArrayList<>();

            // Fields
            for (IField field : type.getFields()) {
                if (symbolCount[0] >= maxResults) break;
                Map<String, Object> fieldSymbol = createFieldSymbol(field, service, cu, includePrivate, symbolCount);
                if (fieldSymbol != null) {
                    children.add(fieldSymbol);
                }
            }

            // Methods
            for (IMethod method : type.getMethods()) {
                if (symbolCount[0] >= maxResults) break;
                Map<String, Object> methodSymbol = createMethodSymbol(method, service, cu, includePrivate, symbolCount);
                if (methodSymbol != null) {
                    children.add(methodSymbol);
                }
            }

            // Nested types
            for (IType nestedType : type.getTypes()) {
                if (symbolCount[0] >= maxResults) break;
                Map<String, Object> nestedSymbol = createTypeSymbol(nestedType, service, cu, includePrivate, maxResults, symbolCount);
                if (nestedSymbol != null) {
                    children.add(nestedSymbol);
                }
            }

            if (!children.isEmpty()) {
                symbol.put("children", children);
            }

            return symbol;

        } catch (JavaModelException e) {
            log.debug("Error creating type symbol: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> createMethodSymbol(IMethod method, IJdtService service, ICompilationUnit cu,
                                                    boolean includePrivate, int[] symbolCount) {
        try {
            int flags = method.getFlags();
            if (!includePrivate && Flags.isPrivate(flags)) {
                return null;
            }

            symbolCount[0]++;

            Map<String, Object> symbol = new LinkedHashMap<>();
            symbol.put("name", method.getElementName());

            if (method.isConstructor()) {
                symbol.put("kind", "Constructor");
            } else {
                symbol.put("kind", "Method");
            }

            symbol.put("modifiers", getModifiers(flags));

            // Build signature
            StringBuilder sig = new StringBuilder();
            sig.append(method.getElementName()).append("(");
            String[] paramTypes = method.getParameterTypes();
            String[] paramNames = method.getParameterNames();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(Signature.getSimpleName(Signature.toString(paramTypes[i])));
                if (i < paramNames.length) {
                    sig.append(" ").append(paramNames[i]);
                }
            }
            sig.append(")");

            if (!method.isConstructor()) {
                String returnType = Signature.getSimpleName(Signature.toString(method.getReturnType()));
                sig.append(": ").append(returnType);
            }

            symbol.put("signature", sig.toString());

            int offset = method.getSourceRange().getOffset();
            symbol.put("line", service.getLineNumber(cu, offset));

            return symbol;

        } catch (JavaModelException e) {
            log.debug("Error creating method symbol: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> createFieldSymbol(IField field, IJdtService service, ICompilationUnit cu,
                                                   boolean includePrivate, int[] symbolCount) {
        try {
            int flags = field.getFlags();
            if (!includePrivate && Flags.isPrivate(flags)) {
                return null;
            }

            symbolCount[0]++;

            Map<String, Object> symbol = new LinkedHashMap<>();
            symbol.put("name", field.getElementName());

            if (field.isEnumConstant()) {
                symbol.put("kind", "EnumConstant");
            } else if (Flags.isStatic(flags) && Flags.isFinal(flags)) {
                symbol.put("kind", "Constant");
            } else {
                symbol.put("kind", "Field");
            }

            symbol.put("modifiers", getModifiers(flags));

            String fieldType = Signature.getSimpleName(Signature.toString(field.getTypeSignature()));
            symbol.put("type", fieldType);

            int offset = field.getSourceRange().getOffset();
            symbol.put("line", service.getLineNumber(cu, offset));

            return symbol;

        } catch (JavaModelException e) {
            log.debug("Error creating field symbol: {}", e.getMessage());
            return null;
        }
    }

    private List<String> getModifiers(int flags) {
        List<String> modifiers = new ArrayList<>();
        if (Flags.isPublic(flags)) modifiers.add("public");
        if (Flags.isProtected(flags)) modifiers.add("protected");
        if (Flags.isPrivate(flags)) modifiers.add("private");
        if (Flags.isStatic(flags)) modifiers.add("static");
        if (Flags.isFinal(flags)) modifiers.add("final");
        if (Flags.isAbstract(flags)) modifiers.add("abstract");
        if (Flags.isSynchronized(flags)) modifiers.add("synchronized");
        if (Flags.isNative(flags)) modifiers.add("native");
        return modifiers;
    }
}
