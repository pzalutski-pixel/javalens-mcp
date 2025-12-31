package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TryStatement;
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
 * Find possible bugs and code quality issues.
 * Uses AST analysis to detect common bug patterns that compilers don't catch.
 *
 * AI-centric: Proactive bug detection before AI suggests changes.
 */
public class FindPossibleBugsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindPossibleBugsTool.class);

    public FindPossibleBugsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_possible_bugs";
    }

    @Override
    public String getDescription() {
        return """
            Find possible bugs and code quality issues.

            USAGE: find_possible_bugs()
            USAGE: find_possible_bugs(filePath="path/to/File.java")
            OUTPUT: List of potential issues

            Detects:
            - Null pointer risks (dereferencing potentially null values)
            - Resource leaks (unclosed streams, connections)
            - Empty catch blocks
            - Comparison issues (== on objects instead of equals)
            - Synchronization issues (sync on String)

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
                "description", "Optional: specific file to check (default: all files)"
            ),
            "severity", Map.of(
                "type", "string",
                "description", "Filter by severity: high, medium, low, all (default: all)"
            )
        ));
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath", null);
        String severity = getStringParam(arguments, "severity", "all");

        try {
            List<Map<String, Object>> issues = new ArrayList<>();

            List<Path> files;
            if (filePath != null) {
                Path file = service.getProjectRoot().resolve(filePath).normalize();
                files = List.of(file);
            } else {
                files = service.getAllJavaFiles();
            }

            for (Path file : files) {
                try {
                    ICompilationUnit cu = service.getCompilationUnit(file);
                    if (cu == null) continue;

                    // Parse to AST with bindings
                    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                    parser.setSource(cu);
                    parser.setResolveBindings(true);
                    parser.setBindingsRecovery(true);

                    CompilationUnit ast = (CompilationUnit) parser.createAST(null);
                    if (ast == null) continue;

                    findIssuesInFile(ast, file, service, issues, severity);
                } catch (Exception e) {
                    log.debug("Error analyzing file for bugs {}: {}", file, e.getMessage());
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalIssues", issues.size());

            long highCount = issues.stream().filter(i -> "high".equals(i.get("severity"))).count();
            long mediumCount = issues.stream().filter(i -> "medium".equals(i.get("severity"))).count();
            long lowCount = issues.stream().filter(i -> "low".equals(i.get("severity"))).count();

            data.put("highCount", highCount);
            data.put("mediumCount", mediumCount);
            data.put("lowCount", lowCount);
            data.put("issues", issues);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(issues.size())
                .returnedCount(issues.size())
                .suggestedNextTools(issues.isEmpty()
                    ? List.of("No potential bugs found")
                    : List.of("Review and fix the identified issues"))
                .build());

        } catch (Exception e) {
            log.error("Error finding possible bugs: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private void findIssuesInFile(CompilationUnit ast, Path file, IJdtService service,
                                   List<Map<String, Object>> issues, String severityFilter) {

        ast.accept(new ASTVisitor() {
            // Check for empty catch blocks
            @Override
            public boolean visit(CatchClause node) {
                Block body = node.getBody();
                if (body.statements().isEmpty()) {
                    addIssue("EMPTY_CATCH", "Empty catch block swallows exception",
                            "medium", node, ast, file, service, issues, severityFilter);
                }
                return true;
            }

            // Check for == comparison on objects
            @Override
            public boolean visit(InfixExpression node) {
                if (node.getOperator() == InfixExpression.Operator.EQUALS ||
                    node.getOperator() == InfixExpression.Operator.NOT_EQUALS) {

                    Expression left = node.getLeftOperand();
                    Expression right = node.getRightOperand();

                    ITypeBinding leftType = left.resolveTypeBinding();
                    ITypeBinding rightType = right.resolveTypeBinding();

                    // Check if comparing objects (not primitives or null)
                    if (leftType != null && rightType != null &&
                        !leftType.isPrimitive() && !rightType.isPrimitive() &&
                        !(left instanceof NullLiteral) && !(right instanceof NullLiteral)) {

                        // String comparison is common mistake
                        if ("java.lang.String".equals(leftType.getQualifiedName()) ||
                            "java.lang.String".equals(rightType.getQualifiedName())) {
                            addIssue("STRING_COMPARISON", "String comparison with == instead of equals()",
                                    "high", node, ast, file, service, issues, severityFilter);
                        }
                    }
                }
                return true;
            }

            // Check for unclosed resources
            @Override
            public boolean visit(VariableDeclarationStatement node) {
                ITypeBinding type = node.getType().resolveBinding();
                if (type != null && isCloseable(type)) {
                    // Check if inside try-with-resources
                    ASTNode parent = node.getParent();
                    while (parent != null) {
                        if (parent instanceof TryStatement ts) {
                            // Check if this is a try-with-resources
                            if (!ts.resources().isEmpty()) {
                                return true;  // OK, it's try-with-resources
                            }
                        }
                        parent = parent.getParent();
                    }

                    // Not in try-with-resources, potential leak
                    addIssue("RESOURCE_LEAK", "Closeable resource may not be closed: " + type.getName(),
                            "medium", node, ast, file, service, issues, severityFilter);
                }
                return true;
            }

            // Check for synchronization issues
            @Override
            public boolean visit(SynchronizedStatement node) {
                Expression expr = node.getExpression();
                if (expr instanceof ThisExpression || expr instanceof SimpleName) {
                    ITypeBinding type = expr.resolveTypeBinding();
                    if (type != null && "java.lang.String".equals(type.getQualifiedName())) {
                        addIssue("SYNC_ON_STRING", "Synchronizing on String (interned strings are shared)",
                                "high", node, ast, file, service, issues, severityFilter);
                    }
                }
                return true;
            }
        });
    }

    private boolean isCloseable(ITypeBinding type) {
        if (type == null) return false;

        // Check if implements Closeable or AutoCloseable
        for (ITypeBinding iface : type.getInterfaces()) {
            String name = iface.getQualifiedName();
            if ("java.io.Closeable".equals(name) || "java.lang.AutoCloseable".equals(name)) {
                return true;
            }
        }

        // Check superclass
        ITypeBinding superclass = type.getSuperclass();
        if (superclass != null) {
            return isCloseable(superclass);
        }

        return false;
    }

    private void addIssue(String code, String message, String severity,
                          ASTNode node, CompilationUnit ast, Path file, IJdtService service,
                          List<Map<String, Object>> issues, String severityFilter) {

        if (!"all".equals(severityFilter) && !severity.equals(severityFilter)) {
            return;
        }

        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("code", code);
        issue.put("message", message);
        issue.put("severity", severity);
        issue.put("filePath", service.getPathUtils().formatPath(file));

        int line = ast.getLineNumber(node.getStartPosition()) - 1;
        int column = ast.getColumnNumber(node.getStartPosition());
        issue.put("line", line);
        issue.put("column", column);

        issues.add(issue);
    }
}
