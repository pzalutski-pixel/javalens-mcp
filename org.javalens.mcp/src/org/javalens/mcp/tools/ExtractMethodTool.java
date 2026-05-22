package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract a code block into a new method.
 */
public class ExtractMethodTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractMethodTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractMethodTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "extract_method";
    }

    @Override
    public String getDescription() {
        return """
            Extract a code block into a new method.

            USAGE: Select code range, provide method name
            OUTPUT: Text edits for method declaration and call site

            The tool analyzes the selected code to:
            - Determine which variables become parameters
            - Determine return type based on variables modified
            - Generate appropriate method signature

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("startLine", "integer", "Zero-based start line of code to extract")
            .required("startColumn", "integer", "Zero-based start column")
            .required("endLine", "integer", "Zero-based end line of code to extract")
            .required("endColumn", "integer", "Zero-based end column")
            .required("methodName", "string", "Name for the new method")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }

        int startLine = getIntParam(arguments, "startLine", -1);
        int startColumn = getIntParam(arguments, "startColumn", -1);
        int endLine = getIntParam(arguments, "endLine", -1);
        int endColumn = getIntParam(arguments, "endColumn", -1);
        String methodName = getStringParam(arguments, "methodName");

        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            return ToolResponse.invalidParameter("positions", "All positions must be >= 0");
        }

        if (methodName == null || methodName.isBlank()) {
            return ToolResponse.invalidParameter("methodName", "Required");
        }

        if (!isValidJavaIdentifier(methodName)) {
            return ToolResponse.invalidParameter("methodName", "Not a valid Java identifier");
        }

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            String source = cu.getSource();
            if (source == null) {
                return ToolResponse.internalError("Cannot read source");
            }

            // Parse to AST with bindings
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            // Calculate offsets
            int startOffset = ast.getPosition(startLine + 1, startColumn);
            int endOffset = ast.getPosition(endLine + 1, endColumn);

            if (startOffset < 0 || endOffset < 0 || startOffset >= endOffset) {
                return ToolResponse.invalidParameter("positions", "Invalid selection range");
            }

            // Find the containing method
            MethodDeclaration containingMethod = findContainingMethod(ast, startOffset);
            if (containingMethod == null) {
                return ToolResponse.invalidParameter("selection", "Selection must be inside a method body");
            }

            // Find the containing type
            TypeDeclaration containingType = findContainingType(ast, startOffset);
            if (containingType == null) {
                return ToolResponse.invalidParameter("selection", "Cannot find containing type");
            }

            // Get the selected source code
            String selectedCode = source.substring(startOffset, endOffset).trim();

            // Analyze variables
            VariableAnalysis analysis = analyzeVariables(ast, containingMethod, startOffset, endOffset);

            // Determine return type
            String returnType = "void";
            String returnStatement = "";
            List<String> returnVars = new ArrayList<>();

            if (!analysis.modifiedAndUsedAfter.isEmpty()) {
                if (analysis.modifiedAndUsedAfter.size() == 1) {
                    VariableInfo var = analysis.modifiedAndUsedAfter.get(0);
                    returnType = var.type;
                    returnStatement = "\n        return " + var.name + ";";
                    returnVars.add(var.name);
                }
            }

            // Build parameter list
            StringBuilder params = new StringBuilder();
            List<String> paramNames = new ArrayList<>();
            for (int i = 0; i < analysis.parameters.size(); i++) {
                if (i > 0) params.append(", ");
                VariableInfo param = analysis.parameters.get(i);
                params.append(param.type).append(" ").append(param.name);
                paramNames.add(param.name);
            }

            // Get indentation
            String methodIndent = getIndentation(source, containingMethod.getStartPosition());
            String bodyIndent = methodIndent + "    ";

            // Format the extracted code with proper indentation
            String formattedBody = formatExtractedBody(selectedCode, bodyIndent);

            // Build the new method. If the containing method declares
            // method-level type parameters (`<T extends Bound>`), the
            // extracted body may reference them — propagate the clause to
            // the new method so the references resolve. Over-declaration
            // (copying params the body doesn't use) is harmless; missing
            // them produces uncompilable code.
            String containingTypeParams = formatContainingMethodTypeParameters(containingMethod);

            StringBuilder newMethod = new StringBuilder();
            newMethod.append("\n\n").append(methodIndent);
            newMethod.append("private ");
            if (!containingTypeParams.isEmpty()) {
                newMethod.append(containingTypeParams).append(" ");
            }
            newMethod.append(returnType).append(" ").append(methodName);
            newMethod.append("(").append(params).append(") {\n");
            newMethod.append(formattedBody);
            newMethod.append(returnStatement);
            newMethod.append("\n").append(methodIndent).append("}");

            // Build the method call. If the returned variable was declared
            // inside the selection, its original declaration was extracted
            // along with the body — the call site must redeclare it so the
            // post-selection code that references it continues to compile.
            StringBuilder methodCall = new StringBuilder();
            if (!returnType.equals("void") && !returnVars.isEmpty()) {
                String returnedName = returnVars.get(0);
                if (analysis.declaredInSelection.contains(returnedName)) {
                    methodCall.append(returnType).append(" ");
                }
                methodCall.append(returnedName).append(" = ");
            }
            methodCall.append(methodName).append("(");
            methodCall.append(String.join(", ", paramNames));
            methodCall.append(");");

            // Calculate insertion point for new method
            int insertOffset = containingMethod.getStartPosition() + containingMethod.getLength();
            int insertLine = ast.getLineNumber(insertOffset) - 1;

            // Build edits
            List<Map<String, Object>> edits = new ArrayList<>();

            // Edit 1: Insert new method
            Map<String, Object> insertEdit = new LinkedHashMap<>();
            insertEdit.put("type", "insert");
            insertEdit.put("line", insertLine);
            insertEdit.put("offset", insertOffset);
            insertEdit.put("newText", newMethod.toString());
            edits.add(insertEdit);

            // Edit 2: Replace selection with method call
            Map<String, Object> replaceEdit = new LinkedHashMap<>();
            replaceEdit.put("type", "replace");
            replaceEdit.put("startLine", startLine);
            replaceEdit.put("startColumn", startColumn);
            replaceEdit.put("endLine", endLine);
            replaceEdit.put("endColumn", endColumn);
            replaceEdit.put("startOffset", startOffset);
            replaceEdit.put("endOffset", endOffset);
            replaceEdit.put("oldText", selectedCode);
            replaceEdit.put("newText", methodCall.toString());
            edits.add(replaceEdit);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("methodName", methodName);
            data.put("returnType", returnType);
            data.put("parameters", analysis.parameters.stream()
                .map(p -> Map.of("name", p.name, "type", p.type))
                .toList());
            data.put("newMethodCode", newMethod.toString().trim());
            data.put("methodCall", methodCall.toString());
            data.put("edits", edits);

            if (analysis.modifiedAndUsedAfter.size() > 1) {
                data.put("warning", "Multiple variables are modified and used after selection. " +
                    "Consider extracting smaller pieces or using a result object.");
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "Apply the text edits to complete the extraction",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error extracting method: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Formats the containing method's type-parameter list as a Java source
     * declaration clause like {@code <T extends Number, U>}, or returns the
     * empty string when there are no parameters.
     */
    private String formatContainingMethodTypeParameters(MethodDeclaration method) {
        @SuppressWarnings("unchecked")
        List<TypeParameter> tps = method.typeParameters();
        if (tps == null || tps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<");
        for (int i = 0; i < tps.size(); i++) {
            if (i > 0) sb.append(", ");
            TypeParameter tp = tps.get(i);
            sb.append(tp.getName().getIdentifier());
            @SuppressWarnings("unchecked")
            List<org.eclipse.jdt.core.dom.Type> bounds = tp.typeBounds();
            if (bounds != null && !bounds.isEmpty()) {
                sb.append(" extends ");
                for (int b = 0; b < bounds.size(); b++) {
                    if (b > 0) sb.append(" & ");
                    sb.append(bounds.get(b).toString());
                }
            }
        }
        sb.append(">");
        return sb.toString();
    }

    private MethodDeclaration findContainingMethod(CompilationUnit ast, int offset) {
        MethodDeclaration[] result = new MethodDeclaration[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                int start = node.getStartPosition();
                int end = start + node.getLength();
                if (offset >= start && offset <= end) {
                    result[0] = node;
                }
                return true;
            }
        });
        return result[0];
    }

    private TypeDeclaration findContainingType(CompilationUnit ast, int offset) {
        TypeDeclaration[] result = new TypeDeclaration[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                int start = node.getStartPosition();
                int end = start + node.getLength();
                if (offset >= start && offset <= end) {
                    result[0] = node;
                }
                return true;
            }
        });
        return result[0];
    }

    private VariableAnalysis analyzeVariables(CompilationUnit ast, MethodDeclaration method,
                                              int selectionStart, int selectionEnd) {
        VariableAnalysis analysis = new VariableAnalysis();

        Set<String> declaredBefore = new HashSet<>();
        Set<String> usedInSelection = new HashSet<>();
        Set<String> declaredInSelection = new HashSet<>();
        Set<String> modifiedInSelection = new HashSet<>();
        Set<String> usedAfterSelection = new HashSet<>();
        Map<String, String> varTypes = new HashMap<>();

        // First pass: collect variable declarations
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                int pos = node.getStartPosition();
                String name = node.getName().getIdentifier();
                IVariableBinding binding = node.resolveBinding();
                String type = binding != null ? binding.getType().getName() : "Object";
                varTypes.put(name, type);

                if (pos < selectionStart) {
                    declaredBefore.add(name);
                } else if (pos >= selectionStart && pos <= selectionEnd) {
                    declaredInSelection.add(name);
                }
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                int pos = node.getStartPosition();
                String name = node.getName().getIdentifier();
                String type = node.getType().toString();
                varTypes.put(name, type);

                if (pos < selectionStart) {
                    declaredBefore.add(name);
                }
                return true;
            }
        });

        // Second pass: find usage and modification
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                int pos = node.getStartPosition();
                String name = node.getIdentifier();

                if (node.getParent() instanceof VariableDeclarationFragment vdf && vdf.getName() == node) {
                    return true;
                }

                boolean inSelection = pos >= selectionStart && pos <= selectionEnd;
                boolean afterSelection = pos > selectionEnd;

                if (inSelection) {
                    usedInSelection.add(name);
                    if (isAssignmentTarget(node)) {
                        modifiedInSelection.add(name);
                    }
                }

                if (afterSelection) {
                    usedAfterSelection.add(name);
                }

                return true;
            }
        });

        // Variables declared before and used in selection -> parameters
        for (String var : usedInSelection) {
            if (declaredBefore.contains(var) && !declaredInSelection.contains(var)) {
                analysis.parameters.add(new VariableInfo(var, varTypes.getOrDefault(var, "Object")));
            }
        }

        // Variables modified in selection and used after -> return values.
        // declaredInSelection ∪ modifiedInSelection also counts: a variable
        // declared and initialized inside the selection that is then read
        // after must be lifted back to the caller via the return value, and
        // the original declaration is gone — the caller will need to
        // redeclare it (handled at the call-site construction).
        for (String var : modifiedInSelection) {
            if (usedAfterSelection.contains(var)) {
                analysis.modifiedAndUsedAfter.add(new VariableInfo(var, varTypes.getOrDefault(var, "Object")));
            }
        }
        for (String var : declaredInSelection) {
            if (usedAfterSelection.contains(var)
                    && analysis.modifiedAndUsedAfter.stream().noneMatch(v -> v.name.equals(var))) {
                analysis.modifiedAndUsedAfter.add(new VariableInfo(var, varTypes.getOrDefault(var, "Object")));
            }
        }

        analysis.declaredInSelection.addAll(declaredInSelection);
        return analysis;
    }

    private boolean isAssignmentTarget(SimpleName node) {
        var parent = node.getParent();
        if (parent instanceof Assignment assign) {
            return assign.getLeftHandSide() == node;
        }
        return parent instanceof PostfixExpression || parent instanceof PrefixExpression;
    }

    private String getIndentation(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < offset && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }
        return indent.toString();
    }

    private String formatExtractedBody(String code, String indent) {
        String[] lines = code.split("\n");
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            result.append(indent).append(line.trim()).append("\n");
        }
        return result.toString().stripTrailing();
    }

    private boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return !RESERVED_WORDS.contains(name);
    }

    private static class VariableAnalysis {
        List<VariableInfo> parameters = new ArrayList<>();
        List<VariableInfo> modifiedAndUsedAfter = new ArrayList<>();
        Set<String> declaredInSelection = new HashSet<>();
    }

    private static class VariableInfo {
        String name;
        String type;

        VariableInfo(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }
}
