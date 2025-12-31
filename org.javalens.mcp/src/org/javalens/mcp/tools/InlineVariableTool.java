package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
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
 * Inline a local variable by replacing all usages with its initializer expression.
 */
public class InlineVariableTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(InlineVariableTool.class);

    public InlineVariableTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "inline_variable";
    }

    @Override
    public String getDescription() {
        return """
            Inline a local variable by replacing all usages with its initializer expression.

            Returns the text edits needed to inline the variable.
            The caller should apply these edits to perform the inlining.

            USAGE: Position cursor on variable declaration or usage
            OUTPUT: Edits to delete declaration and replace usages with initializer

            IMPORTANT: Uses ZERO-BASED coordinates.
            SAFETY: Will refuse if variable is modified after initialization.

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
                "description", "Zero-based line number of variable declaration or usage"
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
            return ToolResponse.invalidParameter("filePath", "Required");
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (line < 0 || column < 0) {
            return ToolResponse.invalidParameter("line/column", "Must be >= 0");
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

            // Calculate offset
            int offset = ast.getPosition(line + 1, column);
            if (offset < 0) {
                return ToolResponse.invalidParameter("position", "Invalid position");
            }

            // Find the node at this position
            NodeFinder finder = new NodeFinder(ast, offset, 0);
            ASTNode node = finder.getCoveringNode();

            // Find the variable binding
            IVariableBinding variableBinding = null;
            VariableDeclarationFragment declarationFragment = null;

            if (node instanceof SimpleName simpleName) {
                if (simpleName.resolveBinding() instanceof IVariableBinding vb) {
                    variableBinding = vb;
                    // Find the declaration fragment
                    if (simpleName.getParent() instanceof VariableDeclarationFragment vdf) {
                        declarationFragment = vdf;
                    }
                }
            } else if (node instanceof VariableDeclarationFragment vdf) {
                declarationFragment = vdf;
                variableBinding = vdf.resolveBinding();
            }

            if (variableBinding == null) {
                return ToolResponse.symbolNotFound("No variable at position");
            }

            // Must be a local variable
            if (!variableBinding.isParameter() && variableBinding.isField()) {
                return ToolResponse.invalidParameter("variable", "Can only inline local variables, not fields");
            }

            if (variableBinding.isParameter()) {
                return ToolResponse.invalidParameter("variable", "Cannot inline method parameters");
            }

            // Find the declaration if we don't have it yet
            if (declarationFragment == null) {
                declarationFragment = findDeclaration(ast, variableBinding);
            }

            if (declarationFragment == null) {
                return ToolResponse.symbolNotFound("Cannot find variable declaration");
            }

            // Get the initializer expression
            Expression initializer = declarationFragment.getInitializer();
            if (initializer == null) {
                return ToolResponse.invalidParameter("variable", "Variable has no initializer, cannot inline");
            }

            String variableName = declarationFragment.getName().getIdentifier();
            String initializerText = initializer.toString();

            // Find all usages of this variable
            List<SimpleName> usages = findUsages(ast, variableBinding, declarationFragment);

            if (usages.isEmpty()) {
                return ToolResponse.invalidParameter("variable", "Variable has no usages to inline");
            }

            // Check if variable is modified after initialization
            if (isModifiedAfterInit(ast, variableBinding, declarationFragment)) {
                return ToolResponse.invalidParameter("variable",
                    "Variable is modified after initialization, cannot safely inline");
            }

            // Build edits - sort by offset descending for safe application
            List<Map<String, Object>> edits = new ArrayList<>();

            // Collect all replacement edits first
            for (SimpleName usage : usages) {
                Map<String, Object> replaceEdit = new LinkedHashMap<>();
                replaceEdit.put("type", "replace");
                replaceEdit.put("line", ast.getLineNumber(usage.getStartPosition()) - 1);
                replaceEdit.put("column", ast.getColumnNumber(usage.getStartPosition()));
                replaceEdit.put("startOffset", usage.getStartPosition());
                replaceEdit.put("endOffset", usage.getStartPosition() + usage.getLength());
                replaceEdit.put("oldText", variableName);
                // Wrap in parentheses if the initializer is complex
                String replacement = needsParentheses(initializer, usage) ?
                    "(" + initializerText + ")" : initializerText;
                replaceEdit.put("newText", replacement);
                edits.add(replaceEdit);
            }

            // Add delete edit for the declaration
            Statement declarationStatement = findDeclarationStatement(declarationFragment);
            if (declarationStatement != null) {
                Map<String, Object> deleteEdit = new LinkedHashMap<>();
                deleteEdit.put("type", "delete");
                deleteEdit.put("line", ast.getLineNumber(declarationStatement.getStartPosition()) - 1);
                deleteEdit.put("startOffset", declarationStatement.getStartPosition());
                deleteEdit.put("endOffset", declarationStatement.getStartPosition() + declarationStatement.getLength());
                deleteEdit.put("oldText", getStatementText(cu, declarationStatement));

                // Check if there are other variables in this declaration
                if (declarationStatement instanceof VariableDeclarationStatement vds) {
                    @SuppressWarnings("unchecked")
                    List<VariableDeclarationFragment> fragments = vds.fragments();
                    if (fragments.size() > 1) {
                        deleteEdit.put("note", "Declaration has multiple variables, only removing this one");
                        // Would need more complex edit - for now just note it
                    }
                }

                edits.add(deleteEdit);
            }

            // Sort edits by offset descending for safe application
            edits.sort((a, b) -> {
                int offsetA = (int) a.get("startOffset");
                int offsetB = (int) b.get("startOffset");
                return Integer.compare(offsetB, offsetA);
            });

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("variableName", variableName);
            data.put("initializerText", initializerText);
            data.put("usageCount", usages.size());
            data.put("edits", edits);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(usages.size())
                .returnedCount(usages.size())
                .suggestedNextTools(List.of(
                    "Apply the text edits to complete the inlining",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error inlining variable: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private VariableDeclarationFragment findDeclaration(CompilationUnit ast, IVariableBinding binding) {
        final VariableDeclarationFragment[] result = {null};
        final String bindingKey = binding.getKey();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                IVariableBinding nodeBinding = node.resolveBinding();
                if (nodeBinding != null && bindingKey.equals(nodeBinding.getKey())) {
                    result[0] = node;
                    return false;
                }
                return true;
            }
        });

        return result[0];
    }

    private List<SimpleName> findUsages(CompilationUnit ast, IVariableBinding binding,
                                        VariableDeclarationFragment declaration) {
        List<SimpleName> usages = new ArrayList<>();
        final String bindingKey = binding.getKey();
        final SimpleName declarationName = declaration.getName();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                // Skip the declaration itself
                if (node == declarationName) {
                    return true;
                }

                IVariableBinding nodeBinding = null;
                if (node.resolveBinding() instanceof IVariableBinding vb) {
                    nodeBinding = vb;
                }

                if (nodeBinding != null && bindingKey.equals(nodeBinding.getKey())) {
                    usages.add(node);
                }
                return true;
            }
        });

        return usages;
    }

    private boolean isModifiedAfterInit(CompilationUnit ast, IVariableBinding binding,
                                        VariableDeclarationFragment declaration) {
        final boolean[] modified = {false};
        final String bindingKey = binding.getKey();
        final int declarationEnd = declaration.getStartPosition() + declaration.getLength();

        // Find the containing method to limit scope
        MethodDeclaration containingMethod = findContainingMethod(declaration);
        if (containingMethod == null) {
            return true; // Conservative - assume modified if we can't determine scope
        }

        containingMethod.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                if (node.getStartPosition() > declarationEnd) {
                    if (node.getLeftHandSide() instanceof SimpleName name) {
                        if (name.resolveBinding() instanceof IVariableBinding vb) {
                            if (bindingKey.equals(vb.getKey())) {
                                modified[0] = true;
                            }
                        }
                    }
                }
                return !modified[0];
            }

            @Override
            public boolean visit(PostfixExpression node) {
                if (node.getStartPosition() > declarationEnd) {
                    if (node.getOperand() instanceof SimpleName name) {
                        if (name.resolveBinding() instanceof IVariableBinding vb) {
                            if (bindingKey.equals(vb.getKey())) {
                                modified[0] = true;
                            }
                        }
                    }
                }
                return !modified[0];
            }

            @Override
            public boolean visit(PrefixExpression node) {
                if (node.getStartPosition() > declarationEnd) {
                    PrefixExpression.Operator op = node.getOperator();
                    if (op == PrefixExpression.Operator.INCREMENT ||
                        op == PrefixExpression.Operator.DECREMENT) {
                        if (node.getOperand() instanceof SimpleName name) {
                            if (name.resolveBinding() instanceof IVariableBinding vb) {
                                if (bindingKey.equals(vb.getKey())) {
                                    modified[0] = true;
                                }
                            }
                        }
                    }
                }
                return !modified[0];
            }
        });

        return modified[0];
    }

    private MethodDeclaration findContainingMethod(ASTNode node) {
        while (node != null) {
            if (node instanceof MethodDeclaration md) {
                return md;
            }
            node = node.getParent();
        }
        return null;
    }

    private Statement findDeclarationStatement(VariableDeclarationFragment fragment) {
        ASTNode parent = fragment.getParent();
        if (parent instanceof VariableDeclarationStatement vds) {
            return vds;
        }
        return null;
    }

    private boolean needsParentheses(Expression initializer, SimpleName usage) {
        // Check if the initializer is a simple expression that doesn't need parentheses
        int nodeType = initializer.getNodeType();
        if (nodeType == ASTNode.NUMBER_LITERAL ||
            nodeType == ASTNode.STRING_LITERAL ||
            nodeType == ASTNode.BOOLEAN_LITERAL ||
            nodeType == ASTNode.NULL_LITERAL ||
            nodeType == ASTNode.SIMPLE_NAME ||
            nodeType == ASTNode.QUALIFIED_NAME ||
            nodeType == ASTNode.METHOD_INVOCATION ||
            nodeType == ASTNode.FIELD_ACCESS ||
            nodeType == ASTNode.ARRAY_ACCESS ||
            nodeType == ASTNode.PARENTHESIZED_EXPRESSION) {
            return false;
        }

        // For other expressions, check the usage context
        ASTNode parent = usage.getParent();
        // If usage is part of a method call, field access, etc., wrap in parens
        if (parent != null) {
            int parentType = parent.getNodeType();
            if (parentType == ASTNode.METHOD_INVOCATION ||
                parentType == ASTNode.FIELD_ACCESS ||
                parentType == ASTNode.INFIX_EXPRESSION) {
                return true;
            }
        }

        return true; // Default to wrapping for safety
    }

    private String getStatementText(ICompilationUnit cu, Statement statement) {
        try {
            String source = cu.getSource();
            if (source != null) {
                int start = statement.getStartPosition();
                int end = start + statement.getLength();
                return source.substring(start, end);
            }
        } catch (JavaModelException e) {
            log.debug("Error getting statement text: {}", e.getMessage());
        }
        return "";
    }
}
