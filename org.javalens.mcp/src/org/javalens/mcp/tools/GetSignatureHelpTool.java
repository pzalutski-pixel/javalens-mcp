package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
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
 * Get method signature help at a position.
 * Provides LSP-style signature help for method calls.
 */
public class GetSignatureHelpTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetSignatureHelpTool.class);

    public GetSignatureHelpTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_signature_help";
    }

    @Override
    public String getDescription() {
        return """
            Get method signature help at a position.

            USAGE: Position on a method call or declaration
            OUTPUT: Method signatures with parameter info

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

            // Find the method
            IMethod method = null;
            if (element instanceof IMethod m) {
                method = m;
            } else {
                method = (IMethod) element.getAncestor(IJavaElement.METHOD);
            }

            if (method == null) {
                return ToolResponse.symbolNotFound("No method found at position");
            }

            Map<String, Object> data = new LinkedHashMap<>();

            // Get all overloaded methods with same name
            List<Map<String, Object>> signatures = new ArrayList<>();
            IType declaringType = method.getDeclaringType();

            if (declaringType != null) {
                for (IMethod m : declaringType.getMethods()) {
                    if (m.getElementName().equals(method.getElementName())) {
                        signatures.add(createSignatureInfo(m, service));
                    }
                }
            } else {
                signatures.add(createSignatureInfo(method, service));
            }

            data.put("signatures", signatures);
            data.put("activeSignature", 0);
            data.put("activeParameter", 0);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(signatures.size())
                .returnedCount(signatures.size())
                .suggestedNextTools(List.of(
                    "get_javadoc for full documentation",
                    "find_references to find usages"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting signature help: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createSignatureInfo(IMethod method, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();

        // Build the label
        StringBuilder label = new StringBuilder();
        label.append(method.getElementName()).append("(");

        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();

        List<Map<String, Object>> parameters = new ArrayList<>();

        for (int i = 0; i < paramTypes.length; i++) {
            String type = Signature.getSimpleName(Signature.toString(paramTypes[i]));
            String name = i < paramNames.length ? paramNames[i] : "arg" + i;

            if (i > 0) label.append(", ");
            String paramLabel = type + " " + name;
            label.append(paramLabel);

            Map<String, Object> param = new LinkedHashMap<>();
            param.put("label", paramLabel);
            parameters.add(param);
        }

        label.append(")");

        if (!method.isConstructor()) {
            String returnType = Signature.getSimpleName(Signature.toString(method.getReturnType()));
            label.append(": ").append(returnType);
        }

        info.put("label", label.toString());
        info.put("parameters", parameters);

        // Try to get documentation
        try {
            ISourceRange javadocRange = method.getJavadocRange();
            if (javadocRange != null) {
                ICompilationUnit cu = method.getCompilationUnit();
                if (cu != null) {
                    String source = cu.getSource();
                    if (source != null) {
                        String doc = source.substring(javadocRange.getOffset(),
                            javadocRange.getOffset() + javadocRange.getLength());
                        // Clean up
                        doc = doc.replaceAll("/\\*\\*", "")
                                 .replaceAll("\\*/", "")
                                 .replaceAll("(?m)^\\s*\\*\\s?", "")
                                 .trim();
                        // Get first sentence
                        int endSentence = doc.indexOf(".");
                        if (endSentence > 0 && endSentence < 200) {
                            doc = doc.substring(0, endSentence + 1);
                        } else if (doc.length() > 200) {
                            doc = doc.substring(0, 200) + "...";
                        }
                        info.put("documentation", doc);
                    }
                }
            }
        } catch (Exception e) {
            // Documentation not available
        }

        return info;
    }
}
