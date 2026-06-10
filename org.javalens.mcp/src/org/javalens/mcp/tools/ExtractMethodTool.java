package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.text.edits.TextEdit;
import org.javalens.core.IJdtService;
import org.javalens.mcp.rewrite.TextEditConverter;
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
            List<String> returnVars = new ArrayList<>();

            if (analysis.modifiedAndUsedAfter.size() == 1) {
                VariableInfo var = analysis.modifiedAndUsedAfter.get(0);
                returnType = var.type;
                returnVars.add(var.name);
            }

            List<String> paramNames = new ArrayList<>();
            for (VariableInfo param : analysis.parameters) {
                paramNames.add(param.name);
            }

            // Synthesize the new method declaration with ASTRewrite: a private
            // MethodDeclaration whose signature is built from the variable
            // analysis (type-parameter clause propagated from the containing
            // method so body references to it still resolve; over-declaration
            // is harmless, missing it does not compile), and whose body carries
            // the selection text. ListRewrite places it after the containing
            // method and the rewriter renders declaration text and indentation.
            AST astFactory = ast.getAST();
            ASTRewrite rewrite = ASTRewrite.create(astFactory);

            MethodDeclaration newMethod = astFactory.newMethodDeclaration();
            newMethod.modifiers().addAll(astFactory.newModifiers(Modifier.PRIVATE));
            for (Object tpObj : containingMethod.typeParameters()) {
                TypeParameter tp = (TypeParameter) tpObj;
                String tpText = source.substring(tp.getStartPosition(),
                    tp.getStartPosition() + tp.getLength());
                newMethod.typeParameters().add(
                    rewrite.createStringPlaceholder(tpText, ASTNode.TYPE_PARAMETER));
            }
            newMethod.setName(astFactory.newSimpleName(methodName));
            newMethod.setReturnType2((Type) rewrite.createStringPlaceholder(
                returnType, ASTNode.SIMPLE_TYPE));
            for (VariableInfo param : analysis.parameters) {
                SingleVariableDeclaration svd = astFactory.newSingleVariableDeclaration();
                svd.setType((Type) rewrite.createStringPlaceholder(param.type, ASTNode.SIMPLE_TYPE));
                svd.setName(astFactory.newSimpleName(param.name));
                newMethod.parameters().add(svd);
            }
            Block newBody = astFactory.newBlock();
            newBody.statements().add(rewrite.createStringPlaceholder(
                stripCommonIndent(selectedCode), ASTNode.EXPRESSION_STATEMENT));
            if (!returnVars.isEmpty()) {
                ReturnStatement ret = astFactory.newReturnStatement();
                ret.setExpression(astFactory.newSimpleName(returnVars.get(0)));
                newBody.statements().add(ret);
            }
            newMethod.setBody(newBody);

            ListRewrite bodyDeclarations = rewrite.getListRewrite(containingType,
                containingType.getBodyDeclarationsProperty());
            bodyDeclarations.insertAfter(newMethod, containingMethod, null);

            TextEdit rewriteEdit = rewrite.rewriteAST();
            List<Map<String, Object>> edits = TextEditConverter.toEditMaps(rewriteEdit, source, ast);
            if (edits.size() != 1 || !"insert".equals(edits.get(0).get("type"))) {
                return ToolResponse.internalError(
                    "Unexpected rewrite shape for the new method insertion: " + edits);
            }
            String newMethodCode = ((String) edits.get(0).get("newText")).trim();

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

            // Edit 2: replace the user's exact selection range with the call.
            // The selection is a text range, not an AST node, so this edit is
            // range-based by design — its newText is the call expression.
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
            data.put("newMethodCode", newMethodCode);
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
     * Strips the common leading whitespace from the selection's continuation
     * lines (the first line arrives already trimmed) so the placeholder text
     * carries only the selection's RELATIVE nesting; the rewriter applies the
     * target indentation when rendering.
     */
    private String stripCommonIndent(String code) {
        String[] lines = code.split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            int w = 0;
            while (w < line.length() && (line.charAt(w) == ' ' || line.charAt(w) == '\t')) {
                w++;
            }
            common = Math.min(common, w);
        }
        if (common == Integer.MAX_VALUE || common == 0) {
            return code;
        }
        StringBuilder sb = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            sb.append('\n').append(line.isBlank() ? line : line.substring(common));
        }
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
