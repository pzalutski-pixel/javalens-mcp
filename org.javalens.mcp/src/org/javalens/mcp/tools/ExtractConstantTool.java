package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.text.edits.TextEdit;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.mcp.rewrite.TextEditConverter;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract an expression into a static final constant.
 */
public class ExtractConstantTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractConstantTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractConstantTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "extract_constant";
    }

    @Override
    public String getDescription() {
        return """
            Extract an expression into a static final constant at class level.

            Returns the text edits needed to extract the expression.
            The caller should apply these edits to perform the extraction.

            USAGE: Select expression by providing start and end positions
            OUTPUT: Constant declaration and replacement edits

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("startLine", "integer", "Zero-based start line of expression")
            .required("startColumn", "integer", "Zero-based start column of expression")
            .required("endLine", "integer", "Zero-based end line of expression")
            .required("endColumn", "integer", "Zero-based end column of expression")
            .required("constantName", "string", "Name for the constant (should be UPPER_SNAKE_CASE)")
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
        String constantName = getStringParam(arguments, "constantName");

        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            return ToolResponse.invalidParameter("positions", "All positions must be >= 0");
        }

        if (constantName == null || constantName.isBlank()) {
            return ToolResponse.invalidParameter("constantName", "Required");
        }

        if (!isValidConstantName(constantName)) {
            return ToolResponse.invalidParameter("constantName", "Not a valid Java identifier");
        }

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            // Parse to AST with bindings
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(Deadline.monitor());

            // Calculate offsets
            int startOffset = ast.getPosition(startLine + 1, startColumn);
            int endOffset = ast.getPosition(endLine + 1, endColumn);

            if (startOffset < 0 || endOffset < 0 || startOffset >= endOffset) {
                return ToolResponse.invalidParameter("positions", "Invalid selection range");
            }

            // Find the expression at this range
            NodeFinder finder = new NodeFinder(ast, startOffset, endOffset - startOffset);
            ASTNode coveredNode = finder.getCoveredNode();
            ASTNode coveringNode = finder.getCoveringNode();

            Expression expression = null;
            if (coveredNode instanceof Expression expr) {
                expression = expr;
            } else if (coveringNode instanceof Expression expr) {
                expression = expr;
            }

            if (expression == null) {
                return ToolResponse.invalidParameter("selection", "No extractable expression at selection");
            }

            // A static final field initializer runs at class-load time with no
            // enclosing instance. The expression must not reference anything
            // that only exists at runtime in an instance method body: `this`,
            // `super`, method parameters, locals, instance fields, or implicit-
            // this calls to instance methods.
            String unsafeReason = describeStaticContextViolation(expression);
            if (unsafeReason != null) {
                return ToolResponse.invalidParameter("selection",
                    "Expression cannot be extracted to a static final constant: " + unsafeReason);
            }

            // The constant's declared type: the expression's type as a declaration can name it,
            // an anonymous class's direct supertype, a captured wildcard's bound.
            ITypeBinding typeBinding = ElementNames.declarableType(expression.resolveTypeBinding(), ast.getAST());
            String typeName = typeBinding != null ? ElementNames.typeExpression(typeBinding) : "Object";

            // Find the containing type declaration
            AbstractTypeDeclaration containingType = findContainingType(expression);
            ITypeBinding containingBinding = containingType == null ? null : containingType.resolveBinding();
            if (containingBinding == null) {
                return ToolResponse.invalidParameter("selection", "Cannot find containing type");
            }

            // Get expression text (source slice, also used for response metadata)
            String source = cu.getSource();
            String expressionText = source.substring(expression.getStartPosition(),
                expression.getStartPosition() + expression.getLength());

            // Synthesize the edits structurally with ASTRewrite: a new
            // private static final field on the containing type, placed after
            // the last existing constant (or first in the body), and the
            // selected expression replaced by the constant name. The rewriter
            // renders declaration text and indentation; ImportRewrite brings in
            // the constant's type respecting project import conventions.
            AST astFactory = ast.getAST();
            ASTRewrite rewrite = ASTRewrite.create(astFactory);
            ImportRewrite importRewrite = ImportRewrite.create(ast, true);

            VariableDeclarationFragment fragment = astFactory.newVariableDeclarationFragment();
            fragment.setName(astFactory.newSimpleName(constantName));
            fragment.setInitializer((Expression) rewrite.createStringPlaceholder(
                expressionText, expression.getNodeType()));

            FieldDeclaration constantField = astFactory.newFieldDeclaration(fragment);
            Type typeNode = typeBinding != null
                ? importRewrite.addImport(typeBinding, astFactory)
                : astFactory.newSimpleType(astFactory.newSimpleName("Object"));
            constantField.setType(typeNode);
            constantField.modifiers().addAll(astFactory.newModifiers(
                Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL));

            ListRewrite bodyDeclarations = rewrite.getListRewrite(containingType,
                containingType.getBodyDeclarationsProperty());
            BodyDeclaration lastConstant = findLastConstant(containingType);
            if (lastConstant != null) {
                bodyDeclarations.insertAfter(constantField, lastConstant, null);
            } else {
                bodyDeclarations.insertFirst(constantField, null);
            }
            rewrite.replace(expression, astFactory.newSimpleName(constantName), null);

            TextEdit rewriteEdit = rewrite.rewriteAST();
            List<Map<String, Object>> edits = TextEditConverter.toEditMaps(rewriteEdit, source, ast);
            TextEdit importsEdit = importRewrite.rewriteImports(Deadline.monitor());
            if (importsEdit != null) {
                // An empty container converts to zero edit maps.
                edits.addAll(TextEditConverter.toEditMaps(importsEdit, source, ast));
            }

            // Human-readable summary of what the declaration introduces.
            String declaration = "private static final " + typeName + " " + constantName + " = " + expressionText + ";";

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("constantName", constantName);
            data.put("constantType", typeName);
            data.put("expressionText", expressionText);
            data.put("declaration", declaration);
            data.put("containingType", ElementNames.qualifiedName(containingBinding));
            data.put("edits", edits);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "Apply the text edits to complete the extraction",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error extracting constant: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private AbstractTypeDeclaration findContainingType(ASTNode node) {
        while (node != null) {
            if (node instanceof AbstractTypeDeclaration type) {
                return type;
            }
            node = node.getParent();
        }
        return null;
    }

    /**
     * The last static-final field of the type, used as the insertion anchor so
     * the new constant lands after existing constants; null when the type has
     * none (the constant is then inserted first in the body).
     */
    private BodyDeclaration findLastConstant(AbstractTypeDeclaration type) {
        BodyDeclaration last = null;
        for (Object declObj : type.bodyDeclarations()) {
            if (declObj instanceof FieldDeclaration fd) {
                int modifiers = fd.getModifiers();
                if ((modifiers & Modifier.STATIC) != 0 && (modifiers & Modifier.FINAL) != 0) {
                    last = fd;
                }
            }
        }
        return last;
    }

    /**
     * Returns null when the expression is safe to lift into a static final
     * field initializer; otherwise returns a short human-readable description
     * of the first violation found. Walks the expression looking for any
     * reference that requires the enclosing instance to exist at runtime.
     */
    private String describeStaticContextViolation(Expression expression) {
        // Pre-pass: collect bindings declared inside the expression itself
        // (lambda parameters, locals in nested blocks). References that
        // resolve to these bindings are safe — they don't reach outside.
        final Set<IBinding> localDeclarations = new HashSet<>();
        expression.accept(new ASTVisitor() {
            @Override
            public boolean visit(SingleVariableDeclaration node) {
                IVariableBinding b = node.resolveBinding();
                if (b != null) localDeclarations.add(b.getVariableDeclaration());
                return true;
            }
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                IVariableBinding b = node.resolveBinding();
                if (b != null) localDeclarations.add(b.getVariableDeclaration());
                return true;
            }
        });

        final String[] reason = {null};
        expression.accept(new ASTVisitor() {
            private void flag(String r) {
                if (reason[0] == null) reason[0] = r;
            }
            @Override
            public boolean visit(ThisExpression node) {
                flag("references `this` (no enclosing instance at static-init time)");
                return false;
            }
            @Override
            public boolean visit(SuperFieldAccess node) {
                flag("uses `super` field access (requires an instance)");
                return false;
            }
            @Override
            public boolean visit(SuperMethodInvocation node) {
                flag("uses `super` method invocation (requires an instance)");
                return false;
            }
            @Override
            public boolean visit(SuperMethodReference node) {
                flag("uses `super` method reference (requires an instance)");
                return false;
            }
            @Override
            public boolean visit(MethodInvocation node) {
                if (node.getExpression() == null) {
                    IMethodBinding mb = node.resolveMethodBinding();
                    if (mb != null && !Modifier.isStatic(mb.getModifiers())) {
                        flag("calls instance method `" + node.getName().getIdentifier()
                            + "` via implicit `this`");
                        return false;
                    }
                }
                return true;
            }
            @Override
            public boolean visit(SimpleName node) {
                ASTNode parent = node.getParent();
                // Skip name parts of qualified constructs and declarations —
                // they're either validated by their parent's visitor or are
                // declaration sites, not references.
                if (parent instanceof FieldAccess fa && fa.getName() == node) return true;
                if (parent instanceof QualifiedName qn && qn.getName() == node) return true;
                if (parent instanceof MethodInvocation mi && mi.getName() == node) return true;
                if (parent instanceof SuperMethodInvocation smi && smi.getName() == node) return true;
                if (parent instanceof SuperFieldAccess sfa && sfa.getName() == node) return true;
                if (parent instanceof SingleVariableDeclaration svd && svd.getName() == node) return true;
                if (parent instanceof VariableDeclarationFragment vdf && vdf.getName() == node) return true;

                IBinding binding = node.resolveBinding();
                if (binding instanceof IVariableBinding vb) {
                    IVariableBinding decl = vb.getVariableDeclaration();
                    if (decl != null && localDeclarations.contains(decl)) {
                        return true;
                    }
                    if (vb.isField()) {
                        if (!Modifier.isStatic(vb.getModifiers())) {
                            flag("references instance field `" + node.getIdentifier() + "`");
                            return false;
                        }
                    } else {
                        flag("references local variable or parameter `"
                            + node.getIdentifier() + "`");
                        return false;
                    }
                }
                return true;
            }
        });

        return reason[0];
    }

    private boolean isValidConstantName(String name) {
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
        return !RESERVED_WORDS.contains(name.toLowerCase());
    }
}
