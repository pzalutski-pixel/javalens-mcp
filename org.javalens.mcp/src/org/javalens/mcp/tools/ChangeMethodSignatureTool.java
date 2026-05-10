package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
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
import java.util.Set;
import java.util.function.Supplier;

/**
 * Change method signature (parameters, return type, name) and update all call sites.
 */
public class ChangeMethodSignatureTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ChangeMethodSignatureTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ChangeMethodSignatureTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "change_method_signature";
    }

    @Override
    public String getDescription() {
        return """
            Change method signature (parameters, return type, or name) and update all call sites.

            Returns text edits for the method declaration and all call sites.
            The caller should apply these edits to perform the change.

            USAGE: Position on method declaration, provide changes
            OUTPUT: Edits for declaration and all call sites

            PARAMETER OPERATIONS:
            - Add new parameter with default value for existing calls
            - Remove parameter (will remove from calls)
            - Rename parameter
            - Reorder parameters (specify all parameters in new order)

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of(
            "type", "string",
            "description", "Path to source file containing the method"
        ));
        properties.put("line", Map.of(
            "type", "integer",
            "description", "Zero-based line number of method declaration"
        ));
        properties.put("column", Map.of(
            "type", "integer",
            "description", "Zero-based column number"
        ));
        properties.put("newName", Map.of(
            "type", "string",
            "description", "New method name (optional, omit to keep current)"
        ));
        properties.put("newReturnType", Map.of(
            "type", "string",
            "description", "New return type (optional, omit to keep current)"
        ));
        properties.put("newParameters", Map.of(
            "type", "array",
            "description", "New parameter list. Each item: {name, type, defaultValue?}. Order matters.",
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "name", Map.of("type", "string", "description", "Parameter name"),
                    "type", Map.of("type", "string", "description", "Parameter type"),
                    "defaultValue", Map.of("type", "string", "description", "Default value for new params at call sites")
                )
            )
        ));

        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return schema;
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

        String newName = getStringParam(arguments, "newName");
        String newReturnType = getStringParam(arguments, "newReturnType");

        // Parse new parameters
        List<ParameterInfo> newParameters = null;
        if (arguments.has("newParameters") && arguments.get("newParameters").isArray()) {
            newParameters = new ArrayList<>();
            for (JsonNode param : arguments.get("newParameters")) {
                String pName = param.has("name") ? param.get("name").asText() : null;
                String pType = param.has("type") ? param.get("type").asText() : null;
                String pDefault = param.has("defaultValue") ? param.get("defaultValue").asText() : null;

                if (pName == null || pType == null) {
                    return ToolResponse.invalidParameter("newParameters",
                        "Each parameter must have 'name' and 'type'");
                }
                newParameters.add(new ParameterInfo(pName, pType, pDefault));
            }
        }

        // Validate at least one change is specified
        if (newName == null && newReturnType == null && newParameters == null) {
            return ToolResponse.invalidParameter("changes",
                "At least one of newName, newReturnType, or newParameters must be specified");
        }

        if (newName != null && !isValidJavaIdentifier(newName)) {
            return ToolResponse.invalidParameter("newName", "Not a valid Java identifier");
        }

        try {
            Path path = Path.of(filePath);

            // Get the method at position
            IJavaElement element = service.getElementAtPosition(path, line, column);
            if (!(element instanceof IMethod method)) {
                return ToolResponse.symbolNotFound("No method found at position");
            }

            String oldName = method.getElementName();
            if (newName == null) {
                newName = oldName;
            }

            // Get current parameters
            String[] oldParamTypes = method.getParameterTypes();
            String[] oldParamNames = method.getParameterNames();
            String oldReturnType = Signature.toString(method.getReturnType());

            if (newReturnType == null) {
                newReturnType = oldReturnType;
            }

            // If no parameter changes specified, keep existing
            if (newParameters == null) {
                newParameters = new ArrayList<>();
                for (int i = 0; i < oldParamTypes.length; i++) {
                    newParameters.add(new ParameterInfo(
                        oldParamNames[i],
                        Signature.toString(oldParamTypes[i]),
                        null
                    ));
                }
            }

            // Build parameter mapping for call site updates
            // Map old param index -> new param index (or -1 if removed)
            int[] paramMapping = buildParameterMapping(oldParamNames, newParameters);

            // Get all references to this method
            List<SearchMatch> references = service.getSearchService().findReferences(
                method, IJavaSearchConstants.REFERENCES, 1000);

            // Collect all edits
            Map<String, List<Map<String, Object>>> editsByFile = new LinkedHashMap<>();

            // Edit 1: Update method declaration
            ICompilationUnit methodCu = method.getCompilationUnit();
            if (methodCu == null) {
                return ToolResponse.invalidParameter("method", "Cannot access method source");
            }

            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(methodCu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            MethodDeclaration methodDecl = findMethodDeclaration(ast, method);
            if (methodDecl == null) {
                return ToolResponse.invalidParameter("method", "Cannot find method in AST");
            }

            // Build new method signature
            String newSignature = buildMethodSignature(newName, newReturnType, newParameters, method.isConstructor());
            int sigStart = getSignatureStart(methodDecl, ast);
            int sigEnd = getSignatureEnd(methodDecl);

            String methodFilePath = service.getPathUtils().formatPath(
                Path.of(methodCu.getResource().getLocation().toOSString()));

            Map<String, Object> declEdit = new LinkedHashMap<>();
            declEdit.put("type", "replace");
            declEdit.put("startLine", ast.getLineNumber(sigStart) - 1);
            declEdit.put("startColumn", ast.getColumnNumber(sigStart));
            declEdit.put("endLine", ast.getLineNumber(sigEnd) - 1);
            declEdit.put("endColumn", ast.getColumnNumber(sigEnd));
            declEdit.put("startOffset", sigStart);
            declEdit.put("endOffset", sigEnd);
            declEdit.put("oldSignature", getSignatureText(methodCu, sigStart, sigEnd));
            declEdit.put("newSignature", newSignature);
            declEdit.put("isDeclaration", true);

            editsByFile.computeIfAbsent(methodFilePath, k -> new ArrayList<>()).add(declEdit);

            // Edit 2: Update all call sites
            for (SearchMatch match : references) {
                try {
                    updateCallSite(match, oldName, newName, oldParamNames, newParameters,
                        paramMapping, service, editsByFile);
                } catch (Exception e) {
                    log.debug("Error updating call site: {}", e.getMessage());
                }
            }

            // Count total edits
            int totalEdits = editsByFile.values().stream()
                .mapToInt(List::size)
                .sum();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("oldName", oldName);
            data.put("newName", newName);
            data.put("oldReturnType", oldReturnType);
            data.put("newReturnType", newReturnType);
            data.put("oldParameterCount", oldParamTypes.length);
            data.put("newParameterCount", newParameters.size());
            data.put("newParameters", newParameters.stream()
                .map(p -> Map.of("name", p.name, "type", p.type))
                .toList());
            data.put("totalEdits", totalEdits);
            data.put("filesAffected", editsByFile.size());
            data.put("editsByFile", editsByFile);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalEdits)
                .returnedCount(totalEdits)
                .suggestedNextTools(List.of(
                    "Apply the text edits to complete the signature change",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error changing method signature: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private List<String> buildNewArgs(List<Expression> oldArgs, List<ParameterInfo> newParams, int[] paramMapping) {
        List<String> newArgs = new ArrayList<>();
        for (int newIdx = 0; newIdx < newParams.size(); newIdx++) {
            ParameterInfo newParam = newParams.get(newIdx);
            int oldIdx = -1;
            for (int i = 0; i < paramMapping.length; i++) {
                if (paramMapping[i] == newIdx) {
                    oldIdx = i;
                    break;
                }
            }
            if (oldIdx >= 0 && oldIdx < oldArgs.size()) {
                newArgs.add(oldArgs.get(oldIdx).toString());
            } else if (newParam.defaultValue != null) {
                newArgs.add(newParam.defaultValue);
            } else {
                newArgs.add("/* TODO: " + newParam.name + " */");
            }
        }
        return newArgs;
    }

    private int[] buildParameterMapping(String[] oldNames, List<ParameterInfo> newParams) {
        int[] mapping = new int[oldNames.length];

        for (int oldIdx = 0; oldIdx < oldNames.length; oldIdx++) {
            mapping[oldIdx] = -1; // Default to removed

            // Find this param in new list
            for (int newIdx = 0; newIdx < newParams.size(); newIdx++) {
                if (oldNames[oldIdx].equals(newParams.get(newIdx).name)) {
                    mapping[oldIdx] = newIdx;
                    break;
                }
            }
        }

        return mapping;
    }

    private MethodDeclaration findMethodDeclaration(CompilationUnit ast, IMethod method) {
        final MethodDeclaration[] result = {null};
        final String methodName = method.getElementName();

        try {
            ISourceRange nameRange = method.getNameRange();
            final int nameOffset = nameRange != null ? nameRange.getOffset() : -1;

            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration node) {
                    if (methodName.equals(node.getName().getIdentifier())) {
                        if (nameOffset >= 0 && node.getName().getStartPosition() == nameOffset) {
                            result[0] = node;
                            return false;
                        }
                        if (result[0] == null) {
                            result[0] = node;
                        }
                    }
                    return true;
                }
            });
        } catch (JavaModelException e) {
            log.debug("Error finding method: {}", e.getMessage());
        }

        return result[0];
    }

    private String buildMethodSignature(String name, String returnType, List<ParameterInfo> params, boolean isConstructor) {
        StringBuilder sig = new StringBuilder();
        if (!isConstructor) {
            sig.append(returnType).append(" ");
        }
        sig.append(name).append("(");

        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(params.get(i).type).append(" ").append(params.get(i).name);
        }

        sig.append(")");
        return sig.toString();
    }

    private int getSignatureStart(MethodDeclaration decl, CompilationUnit ast) {
        // Return type start
        if (decl.getReturnType2() != null) {
            return decl.getReturnType2().getStartPosition();
        }
        // For constructors, use name start
        return decl.getName().getStartPosition();
    }

    private int getSignatureEnd(MethodDeclaration decl) {
        // End of parameter list (closing parenthesis)
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = decl.parameters();
        if (!params.isEmpty()) {
            SingleVariableDeclaration lastParam = params.get(params.size() - 1);
            return lastParam.getStartPosition() + lastParam.getLength() + 1; // +1 for ')'
        }
        // No parameters - find the closing paren
        return decl.getName().getStartPosition() + decl.getName().getLength() + 2; // +2 for '()'
    }

    private String getSignatureText(ICompilationUnit cu, int start, int end) {
        try {
            String source = cu.getSource();
            if (source != null && start >= 0 && end <= source.length()) {
                return source.substring(start, end);
            }
        } catch (JavaModelException e) {
            log.debug("Error getting signature: {}", e.getMessage());
        }
        return "";
    }

    private void updateCallSite(SearchMatch match, String oldName, String newName,
                                String[] oldParamNames, List<ParameterInfo> newParams,
                                int[] paramMapping, IJdtService service,
                                Map<String, List<Map<String, Object>>> editsByFile)
            throws JavaModelException {

        Object element = match.getElement();
        if (!(element instanceof IJavaElement javaElement)) {
            return;
        }

        ICompilationUnit cu = (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu == null) {
            return;
        }

        // Parse the call site file
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        // Find the MethodInvocation or ClassInstanceCreation at this offset
        final MethodInvocation[] invocation = {null};
        final ClassInstanceCreation[] constructorCall = {null};
        final int matchOffset = match.getOffset();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (node.getName().getStartPosition() == matchOffset ||
                    (node.getStartPosition() <= matchOffset &&
                     matchOffset < node.getStartPosition() + node.getLength())) {
                    if (oldName.equals(node.getName().getIdentifier())) {
                        invocation[0] = node;
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                if (node.getStartPosition() <= matchOffset &&
                    matchOffset < node.getStartPosition() + node.getLength()) {
                    constructorCall[0] = node;
                    return false;
                }
                return true;
            }
        });

        if (invocation[0] == null && constructorCall[0] == null) {
            return;
        }

        int callStart;
        int callEnd;
        String oldCallText;
        String newCallText;

        if (invocation[0] != null) {
            MethodInvocation mi = invocation[0];
            @SuppressWarnings("unchecked")
            List<Expression> oldArgs = mi.arguments();
            List<String> newArgs = buildNewArgs(oldArgs, newParams, paramMapping);

            StringBuilder newCall = new StringBuilder();
            if (mi.getExpression() != null) {
                newCall.append(mi.getExpression().toString()).append(".");
            }
            newCall.append(newName).append("(").append(String.join(", ", newArgs)).append(")");

            callStart = mi.getStartPosition();
            callEnd = mi.getStartPosition() + mi.getLength();
            oldCallText = mi.toString();
            newCallText = newCall.toString();
        } else {
            ClassInstanceCreation cic = constructorCall[0];
            @SuppressWarnings("unchecked")
            List<Expression> oldArgs = cic.arguments();
            List<String> newArgs = buildNewArgs(oldArgs, newParams, paramMapping);

            String typeName = cic.getType().toString();
            newCallText = "new " + typeName + "(" + String.join(", ", newArgs) + ")";

            callStart = cic.getStartPosition();
            callEnd = cic.getStartPosition() + cic.getLength();
            oldCallText = cic.toString();
        }

        // Create edit
        String callFilePath = service.getPathUtils().formatPath(
            Path.of(cu.getResource().getLocation().toOSString()));

        Map<String, Object> callEdit = new LinkedHashMap<>();
        callEdit.put("type", "replace");
        callEdit.put("startLine", ast.getLineNumber(callStart) - 1);
        callEdit.put("startColumn", ast.getColumnNumber(callStart));
        callEdit.put("endLine", ast.getLineNumber(callEnd) - 1);
        callEdit.put("endColumn", ast.getColumnNumber(callEnd));
        callEdit.put("startOffset", callStart);
        callEdit.put("endOffset", callEnd);
        callEdit.put("oldText", oldCallText);
        callEdit.put("newText", newCallText);
        callEdit.put("isDeclaration", false);

        editsByFile.computeIfAbsent(callFilePath, k -> new ArrayList<>()).add(callEdit);
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

    private static class ParameterInfo {
        final String name;
        final String type;
        final String defaultValue;

        ParameterInfo(String name, String type, String defaultValue) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
        }
    }
}
