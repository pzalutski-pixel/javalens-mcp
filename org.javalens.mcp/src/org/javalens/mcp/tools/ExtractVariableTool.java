package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.text.edits.TextEdit;
import org.javalens.core.IJdtService;
import org.javalens.mcp.rewrite.TextEditConverter;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract an expression into a local variable.
 */
public class ExtractVariableTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractVariableTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractVariableTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "extract_variable";
    }

    @Override
    public String getDescription() {
        return """
            Extract an expression at the given position into a local variable.

            Returns the text edits needed to extract the expression.
            The caller should apply these edits to perform the extraction.

            USAGE: Select expression by providing start and end positions
            OUTPUT: Variable declaration and replacement edits

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
            .optional("variableName", "string", "Name for the new variable (optional, will suggest if not provided)")
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
        String variableName = getStringParam(arguments, "variableName");

        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            return ToolResponse.invalidParameter("positions", "All positions must be >= 0");
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
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

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

            // Get the type of the expression
            ITypeBinding typeBinding = expression.resolveTypeBinding();
            String typeName = typeBinding != null ? typeBinding.getName() : "var";

            // Generate variable name if not provided
            if (variableName == null || variableName.isBlank()) {
                variableName = suggestVariableName(expression, typeBinding);
            }

            if (!isValidJavaIdentifier(variableName)) {
                return ToolResponse.invalidParameter("variableName", "Not a valid Java identifier");
            }

            // Find the containing statement to insert before
            Statement containingStatement = findContainingStatement(expression);
            if (containingStatement == null) {
                return ToolResponse.invalidParameter("selection", "Cannot find containing statement");
            }

            // Refuse extraction when the expression sits in a position that
            // would be re-evaluated each iteration (loop conditions/updaters)
            // or conditionally (short-circuit `&&`/`||` non-left operands,
            // ternary then/else branches). Hoisting to a single declaration
            // before the containing statement changes runtime behavior.
            String semanticsViolation = describeEvaluationContextViolation(expression, containingStatement);
            if (semanticsViolation != null) {
                return ToolResponse.invalidParameter("selection",
                    "Extracting this expression would change evaluation semantics: " + semanticsViolation);
            }

            // Get expression text (source slice, also used for response metadata)
            String source = cu.getSource();
            String expressionText = source.substring(expression.getStartPosition(),
                expression.getStartPosition() + expression.getLength());

            // The insertion point must live in a statement list; otherwise the
            // declaration would land in an unblocked single-statement body and
            // not compile.
            ASTNode statementParent = containingStatement.getParent();
            if (!(statementParent instanceof Block)) {
                return ToolResponse.invalidParameter("selection",
                    "Containing statement is not inside a block; extraction would not compile");
            }

            // Synthesize the edits structurally with ASTRewrite — the rewriter
            // renders the declaration, indentation, and replacement from AST
            // modifications instead of hand-built strings. ImportRewrite brings
            // in the variable's type respecting project import conventions.
            AST astFactory = ast.getAST();
            ASTRewrite rewrite = ASTRewrite.create(astFactory);
            ImportRewrite importRewrite = ImportRewrite.create(ast, true);

            VariableDeclarationFragment fragment = astFactory.newVariableDeclarationFragment();
            fragment.setName(astFactory.newSimpleName(variableName));
            fragment.setInitializer((Expression) rewrite.createStringPlaceholder(
                expressionText, expression.getNodeType()));

            VariableDeclarationStatement declStmt = astFactory.newVariableDeclarationStatement(fragment);
            Type typeNode = typeBinding != null
                ? importRewrite.addImport(typeBinding, astFactory)
                : astFactory.newSimpleType(astFactory.newSimpleName("var"));
            declStmt.setType(typeNode);

            ListRewrite statements = rewrite.getListRewrite(statementParent, Block.STATEMENTS_PROPERTY);
            statements.insertBefore(declStmt, containingStatement, null);
            rewrite.replace(expression, astFactory.newSimpleName(variableName), null);

            TextEdit rewriteEdit = rewrite.rewriteAST();
            List<Map<String, Object>> edits = TextEditConverter.toEditMaps(rewriteEdit, source, ast);
            TextEdit importsEdit = importRewrite.rewriteImports(new NullProgressMonitor());
            if (importsEdit != null && (importsEdit.hasChildren() || importsEdit.getLength() > 0
                    || !importsEdit.getClass().getSimpleName().equals("MultiTextEdit"))) {
                edits.addAll(TextEditConverter.toEditMaps(importsEdit, source, ast));
            }

            // Human-readable summary of what the declaration introduces.
            String declaration = typeName + " " + variableName + " = " + expressionText + ";";

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("variableName", variableName);
            data.put("variableType", typeName);
            data.put("expressionText", expressionText);
            data.put("declaration", declaration);
            data.put("edits", edits);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "Apply the text edits to complete the extraction",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error extracting variable: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Walks from {@code expression} up to (but not including) {@code containingStatement},
     * checking each parent for a position where the child is re-evaluated
     * (loop conditions/updaters) or conditionally evaluated (short-circuit
     * operators, ternary branches). Returns a short reason string on the
     * first violation, or null when extraction is safe.
     */
    private String describeEvaluationContextViolation(Expression expression, Statement containingStatement) {
        ASTNode current = expression;
        while (current != null && current != containingStatement) {
            ASTNode parent = current.getParent();
            if (parent == null) break;

            if (parent instanceof ForStatement fs) {
                if (current == fs.getExpression()) {
                    return "expression is a for-loop condition (re-evaluated each iteration)";
                }
                if (fs.updaters().contains(current)) {
                    return "expression is a for-loop updater (re-evaluated each iteration)";
                }
            } else if (parent instanceof WhileStatement ws) {
                if (current == ws.getExpression()) {
                    return "expression is a while-loop condition (re-evaluated each iteration)";
                }
            } else if (parent instanceof DoStatement ds) {
                if (current == ds.getExpression()) {
                    return "expression is a do-while condition (re-evaluated each iteration)";
                }
            } else if (parent instanceof InfixExpression ie) {
                InfixExpression.Operator op = ie.getOperator();
                if (op == InfixExpression.Operator.CONDITIONAL_AND
                        || op == InfixExpression.Operator.CONDITIONAL_OR) {
                    // The left operand is always evaluated; the right and any
                    // extended operands are short-circuited.
                    if (current != ie.getLeftOperand()) {
                        return "expression sits on the conditional side of `"
                            + op.toString() + "` (would lose short-circuit guard)";
                    }
                }
            } else if (parent instanceof ConditionalExpression ce) {
                if (current == ce.getThenExpression() || current == ce.getElseExpression()) {
                    return "expression is a then/else branch of a ternary (conditionally evaluated)";
                }
            }

            current = parent;
        }
        return null;
    }

    private Statement findContainingStatement(ASTNode node) {
        while (node != null) {
            if (node instanceof Statement stmt && !(node instanceof Block)) {
                return stmt;
            }
            node = node.getParent();
        }
        return null;
    }

    private String suggestVariableName(Expression expression, ITypeBinding typeBinding) {
        String suggested = null;

        if (typeBinding != null) {
            String typeName = typeBinding.getName();
            if (typeName.length() > 0) {
                int genericIndex = typeName.indexOf('<');
                if (genericIndex > 0) {
                    typeName = typeName.substring(0, genericIndex);
                }
                suggested = Character.toLowerCase(typeName.charAt(0)) +
                       (typeName.length() > 1 ? typeName.substring(1) : "");
            }
        }

        if (suggested == null && expression instanceof MethodInvocation mi) {
            String methodName = mi.getName().getIdentifier();
            if (methodName.startsWith("get") && methodName.length() > 3) {
                suggested = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else {
                suggested = methodName + "Result";
            }
        }

        if (suggested == null && expression instanceof ClassInstanceCreation cic) {
            String typeName = cic.getType().toString();
            suggested = Character.toLowerCase(typeName.charAt(0)) +
                   (typeName.length() > 1 ? typeName.substring(1) : "");
        }

        if (suggested == null) {
            suggested = "extracted";
        }

        // Fall back to generic names if suggested name is a reserved word
        if (RESERVED_WORDS.contains(suggested)) {
            if (typeBinding != null && typeBinding.isPrimitive()) {
                return suggested + "Value";  // e.g., "intValue", "booleanValue"
            }
            return "value";
        }

        return suggested;
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
}
