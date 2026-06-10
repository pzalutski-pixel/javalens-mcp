package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.rewrite.TextEditConverter;
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
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file containing the method")
            .required("line", "integer", "Zero-based line number of method declaration")
            .required("column", "integer", "Zero-based column number")
            .optional("newName", "string", "New method name (optional, omit to keep current)")
            .optional("newReturnType", "string", "New return type (optional, omit to keep current)")
            .optionalCustom("newParameters", Map.of(
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
            ))
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
                method, IJavaSearchConstants.REFERENCES, 1000).matches();

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

            // Synthesize the declaration change with ASTRewrite: name and (for
            // non-constructors — they have no return-type prefix) return type are
            // node replacements, and the parameter list is rebuilt through a
            // ListRewrite, so separators and spacing render by construction.
            // The contract pins ONE edit spanning the whole signature; the
            // fine-grained rewrite edits are aggregated onto that span below.
            boolean methodIsConstructor = method.isConstructor();
            AST declFactory = ast.getAST();
            ASTRewrite declRewrite = ASTRewrite.create(declFactory);
            if (!newName.equals(oldName)) {
                declRewrite.replace(methodDecl.getName(),
                    declRewrite.createStringPlaceholder(newName, ASTNode.SIMPLE_NAME), null);
            }
            if (!methodIsConstructor && methodDecl.getReturnType2() != null
                    && !newReturnType.equals(oldReturnType)) {
                declRewrite.replace(methodDecl.getReturnType2(),
                    declRewrite.createStringPlaceholder(newReturnType, ASTNode.SIMPLE_TYPE), null);
            }
            ListRewrite paramsRewrite = declRewrite.getListRewrite(methodDecl,
                MethodDeclaration.PARAMETERS_PROPERTY);
            for (Object oldParam : methodDecl.parameters()) {
                paramsRewrite.remove((ASTNode) oldParam, null);
            }
            for (ParameterInfo param : newParameters) {
                SingleVariableDeclaration svd = declFactory.newSingleVariableDeclaration();
                svd.setType((Type) declRewrite.createStringPlaceholder(param.type, ASTNode.SIMPLE_TYPE));
                svd.setName(declFactory.newSimpleName(param.name));
                paramsRewrite.insertLast(svd, null);
            }

            int sigStart = getSignatureStart(methodDecl, ast);
            int sigEnd = getSignatureEnd(methodDecl);
            String methodSource = methodCu.getSource();
            String newSignature = TextEditConverter.applyToSlice(
                TextEditConverter.toEditMaps(declRewrite.rewriteAST(), methodSource, ast),
                sigStart, sigEnd, methodSource);

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
            declEdit.put("oldSignature", methodSource.substring(sigStart, sigEnd));
            declEdit.put("newSignature", newSignature);
            declEdit.put("isDeclaration", true);

            editsByFile.computeIfAbsent(methodFilePath, k -> new ArrayList<>()).add(declEdit);

            // Edit 2: Update all call sites
            boolean isConstructor = method.isConstructor();
            List<Map<String, Object>> methodReferences = new ArrayList<>();
            for (SearchMatch match : references) {
                try {
                    updateCallSite(match, oldName, newName, oldParamNames, newParameters,
                        paramMapping, service, editsByFile, methodReferences, isConstructor);
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
            if (!methodIsConstructor) {
                data.put("oldReturnType", oldReturnType);
                data.put("newReturnType", newReturnType);
            }
            data.put("oldParameterCount", oldParamTypes.length);
            data.put("newParameterCount", newParameters.size());
            data.put("newParameters", newParameters.stream()
                .map(p -> Map.of("name", p.name, "type", p.type))
                .toList());
            data.put("totalEdits", totalEdits);
            data.put("filesAffected", editsByFile.size());
            data.put("editsByFile", editsByFile);

            // Method references cannot be automatically rewritten because the
            // functional-interface signature they bind to would no longer match.
            // Surface them as informational entries plus top-level warnings so the
            // AI consumer knows manual rewrite is required.
            if (!methodReferences.isEmpty()) {
                data.put("methodReferences", methodReferences);
                java.util.Set<String> affectedFiles = new java.util.LinkedHashSet<>();
                for (Map<String, Object> ref : methodReferences) {
                    Object filePathObj = ref.get("filePath");
                    if (filePathObj != null) affectedFiles.add(filePathObj.toString());
                }
                List<String> warnings = new ArrayList<>();
                for (String f : affectedFiles) {
                    warnings.add(f + " has method-reference call sites that cannot be " +
                        "automatically updated. Replace each with a lambda or refactor " +
                        "the functional-interface signature.");
                }
                data.put("warnings", warnings);
            }

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

    private void updateCallSite(SearchMatch match, String oldName, String newName,
                                String[] oldParamNames, List<ParameterInfo> newParams,
                                int[] paramMapping, IJdtService service,
                                Map<String, List<Map<String, Object>>> editsByFile,
                                List<Map<String, Object>> methodReferences,
                                boolean isConstructor)
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

        final int matchOffset = match.getOffset();
        final ASTNode[] callNode = {null};

        if (isConstructor) {
            // For constructors the call site can be:
            //   ClassInstanceCreation: `new Foo(...)`
            //   ConstructorInvocation: `this(...)`
            //   SuperConstructorInvocation: `super(...)`
            //   CreationReference: `Foo::new` — cannot be textually rewritten.
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(ClassInstanceCreation node) {
                    if (offsetWithin(node, matchOffset)) {
                        callNode[0] = node;
                        return false;
                    }
                    return true;
                }
                @Override
                public boolean visit(ConstructorInvocation node) {
                    if (offsetWithin(node, matchOffset)) {
                        callNode[0] = node;
                        return false;
                    }
                    return true;
                }
                @Override
                public boolean visit(SuperConstructorInvocation node) {
                    if (offsetWithin(node, matchOffset)) {
                        callNode[0] = node;
                        return false;
                    }
                    return true;
                }
                @Override
                public boolean visit(CreationReference node) {
                    if (offsetWithin(node, matchOffset)) {
                        callNode[0] = node;
                        return false;
                    }
                    return true;
                }
            });
        } else {
            // For regular methods, MethodInvocation is the textually-rewritable form.
            // The three MethodReference subtypes (Expression / Type / Super) bind to
            // the method too — they are call sites that cannot be textually rewritten
            // because the surrounding functional-interface assignment would no longer
            // type-check. We capture them so the response can surface them to the
            // consumer; we do not emit replace edits for them.
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodInvocation node) {
                    if (node.getName().getStartPosition() == matchOffset ||
                        offsetWithin(node, matchOffset)) {
                        if (oldName.equals(node.getName().getIdentifier())) {
                            callNode[0] = node;
                            return false;
                        }
                    }
                    return true;
                }
                @Override
                public boolean visit(SuperMethodInvocation node) {
                    if (node.getName().getStartPosition() == matchOffset ||
                        offsetWithin(node, matchOffset)) {
                        if (oldName.equals(node.getName().getIdentifier())) {
                            callNode[0] = node;
                            return false;
                        }
                    }
                    return true;
                }
                @Override
                public boolean visit(ExpressionMethodReference node) {
                    if (node.getName().getStartPosition() == matchOffset ||
                        offsetWithin(node, matchOffset)) {
                        if (oldName.equals(node.getName().getIdentifier())) {
                            callNode[0] = node;
                            return false;
                        }
                    }
                    return true;
                }
                @Override
                public boolean visit(TypeMethodReference node) {
                    if (node.getName().getStartPosition() == matchOffset ||
                        offsetWithin(node, matchOffset)) {
                        if (oldName.equals(node.getName().getIdentifier())) {
                            callNode[0] = node;
                            return false;
                        }
                    }
                    return true;
                }
                @Override
                public boolean visit(SuperMethodReference node) {
                    if (node.getName().getStartPosition() == matchOffset ||
                        offsetWithin(node, matchOffset)) {
                        if (oldName.equals(node.getName().getIdentifier())) {
                            callNode[0] = node;
                            return false;
                        }
                    }
                    return true;
                }
            });
        }

        if (callNode[0] == null) {
            return;
        }

        // Method references and constructor references cannot be textually rewritten
        // (replacing `Foo::method` with anything keeps it a method reference, and the
        // functional-interface signature it binds to would now be wrong). Record the
        // location as informational; do not emit a replace edit.
        ASTNode found = callNode[0];
        if (found instanceof ExpressionMethodReference
            || found instanceof TypeMethodReference
            || found instanceof SuperMethodReference
            || found instanceof CreationReference) {
            String refFilePath = service.getPathUtils().formatPath(
                Path.of(cu.getResource().getLocation().toOSString()));
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("filePath", refFilePath);
            ref.put("line", ast.getLineNumber(found.getStartPosition()) - 1);
            ref.put("column", ast.getColumnNumber(found.getStartPosition()));
            ref.put("startOffset", found.getStartPosition());
            ref.put("endOffset", found.getStartPosition() + found.getLength());
            ref.put("text", found.toString().trim());
            ref.put("reason", "Method reference cannot be automatically rewritten; the " +
                "functional-interface signature would no longer match. Replace with a " +
                "lambda or refactor manually.");
            methodReferences.add(ref);
            return;
        }

        ASTNode mi = callNode[0];

        // Rebuild the argument list through a ListRewrite: surviving arguments
        // keep their original source text, removed ones disappear, new ones
        // take the default value (or a TODO marker), and the renamed method
        // name is a node replacement. The call's header — qualifier, the
        // super/this/new keywords, the constructed type — is untouched source,
        // so the per-node-type re-rendering is gone by construction. The
        // contract pins ONE edit spanning the whole call; the fine-grained
        // rewrite edits are aggregated onto that span.
        String callSource = cu.getSource();
        @SuppressWarnings("unchecked")
        List<Expression> oldArgs = (List<Expression>) callArguments(mi);

        ASTRewrite callRewrite = ASTRewrite.create(ast.getAST());
        ListRewrite argsRewrite = callRewrite.getListRewrite(mi, argumentsProperty(mi));
        for (Expression oldArg : oldArgs) {
            argsRewrite.remove(oldArg, null);
        }
        for (int newIdx = 0; newIdx < newParams.size(); newIdx++) {
            ParameterInfo newParam = newParams.get(newIdx);
            int oldIdx = -1;
            for (int i = 0; i < paramMapping.length; i++) {
                if (paramMapping[i] == newIdx) {
                    oldIdx = i;
                    break;
                }
            }

            String argText;
            if (oldIdx >= 0 && oldIdx < oldArgs.size()) {
                Expression oldArg = oldArgs.get(oldIdx);
                argText = callSource.substring(oldArg.getStartPosition(),
                    oldArg.getStartPosition() + oldArg.getLength());
            } else if (newParam.defaultValue != null) {
                argText = newParam.defaultValue;
            } else {
                argText = "/* TODO: " + newParam.name + " */";
            }
            argsRewrite.insertLast(
                callRewrite.createStringPlaceholder(argText, ASTNode.SIMPLE_NAME), null);
        }
        SimpleName invocationName = invocationName(mi);
        if (invocationName != null && !newName.equals(oldName)) {
            callRewrite.replace(invocationName,
                callRewrite.createStringPlaceholder(newName, ASTNode.SIMPLE_NAME), null);
        }

        int callStart = mi.getStartPosition();
        int callEnd = callStart + mi.getLength();
        String newCallText = TextEditConverter.applyToSlice(
            TextEditConverter.toEditMaps(callRewrite.rewriteAST(), callSource, ast),
            callStart, callEnd, callSource);

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
        callEdit.put("oldText", callSource.substring(callStart, callEnd));
        callEdit.put("newText", newCallText);
        callEdit.put("isDeclaration", false);

        editsByFile.computeIfAbsent(callFilePath, k -> new ArrayList<>()).add(callEdit);
    }

    /** The arguments list property for each rewritable call-site node type. */
    private static ChildListPropertyDescriptor argumentsProperty(ASTNode node) {
        if (node instanceof MethodInvocation) return MethodInvocation.ARGUMENTS_PROPERTY;
        if (node instanceof SuperMethodInvocation) return SuperMethodInvocation.ARGUMENTS_PROPERTY;
        if (node instanceof ClassInstanceCreation) return ClassInstanceCreation.ARGUMENTS_PROPERTY;
        if (node instanceof ConstructorInvocation) return ConstructorInvocation.ARGUMENTS_PROPERTY;
        return SuperConstructorInvocation.ARGUMENTS_PROPERTY;
    }

    /**
     * The renameable name node of a call site, or null where the call form has
     * none (this/super constructor invocations) or where the "name" is the
     * constructed type (class instance creation), which this tool does not
     * rename at call sites.
     */
    private static SimpleName invocationName(ASTNode node) {
        if (node instanceof MethodInvocation mi) return mi.getName();
        if (node instanceof SuperMethodInvocation smi) return smi.getName();
        return null;
    }

    private static boolean offsetWithin(ASTNode node, int offset) {
        return node.getStartPosition() <= offset &&
               offset < node.getStartPosition() + node.getLength();
    }

    private static List<?> callArguments(ASTNode node) {
        if (node instanceof MethodInvocation mi) return mi.arguments();
        if (node instanceof SuperMethodInvocation smi) return smi.arguments();
        if (node instanceof ClassInstanceCreation cic) return cic.arguments();
        if (node instanceof ConstructorInvocation ci) return ci.arguments();
        if (node instanceof SuperConstructorInvocation sci) return sci.arguments();
        return List.of();
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
