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
import org.javalens.core.MethodFormatter;
import org.javalens.core.ModifierFormatter;
import org.javalens.core.TypeKindResolver;
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
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .optional("includePrivate", "boolean", "Include private members (default true)")
            .optional("maxResults", "integer", "Maximum symbols to return (default 500)")
            .build();
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

            // Count all eligible symbols up-front so totalCount/truncated reflect the
            // pre-clip total. The capped build loop below would otherwise stop walking
            // once it hits maxResults, leaving totalCount equal to returnedCount and
            // truncated misreporting the exact-equal case.
            int totalEligible = countAllEligibleSymbols(cu, includePrivate);

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
            data.put("totalSymbols", totalEligible);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalEligible)
                .returnedCount(symbolCount[0])
                .truncated(totalEligible > symbolCount[0])
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

    /**
     * Walk the CU and count every symbol that would be emitted by the build pass —
     * unconstrained by maxResults. This is used to populate the pre-clip
     * totalCount field of the response envelope. Visibility filtering matches the
     * builder: private-element filtering is gated by {@code includePrivate}.
     */
    private int countAllEligibleSymbols(ICompilationUnit cu, boolean includePrivate) throws JavaModelException {
        int total = 0;
        for (IType type : cu.getTypes()) {
            total += countTypeRecursive(type, includePrivate);
        }
        return total;
    }

    private int countTypeRecursive(IType type, boolean includePrivate) throws JavaModelException {
        int flags = type.getFlags();
        if (!includePrivate && Flags.isPrivate(flags)) return 0;
        int count = 1;
        for (IField field : type.getFields()) {
            if (includePrivate || !Flags.isPrivate(field.getFlags())) count++;
        }
        for (IMethod method : type.getMethods()) {
            if (includePrivate || !Flags.isPrivate(method.getFlags())) count++;
        }
        for (IType nested : type.getTypes()) {
            count += countTypeRecursive(nested, includePrivate);
        }
        return count;
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

            symbol.put("kind", TypeKindResolver.kindOf(type));

            symbol.put("modifiers", ModifierFormatter.format(flags));

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

            symbol.put("modifiers", ModifierFormatter.format(flags));

            symbol.put("signature", MethodFormatter.signature(method));

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

            symbol.put("modifiers", ModifierFormatter.format(flags));

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

}
