package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /** The accepted {@code severity} values, compared case-insensitively. */
    private static final List<String> SEVERITIES = List.of("high", "medium", "low", "all");

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

            severity (optional, compared case-insensitively): high, medium or low
            keeps the issues of that severity; all (the default) keeps every issue.
            Any other value is rejected with INVALID_PARAMETER.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("filePath", "string", "Optional: specific file to check (default: all files)")
            .optional("severity", "string", "Filter by severity, case-insensitive: high, medium, low "
                + "or all (default: all)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath", null);
        String requestedSeverity = getStringParam(arguments, "severity", "all");
        String severity = requestedSeverity.toLowerCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            return ToolResponse.invalidParameter("severity",
                "Must be one of " + String.join(", ", SEVERITIES) + " (case-insensitive); got: "
                    + requestedSeverity);
        }

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

                    CompilationUnit ast = (CompilationUnit) parser.createAST(Deadline.monitor());
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

            // Check for null-initialized local that is dereferenced without
            // reassignment in the same method. Conservative detector — only
            // fires when the variable's sole assignment is the literal null
            // initializer.
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() == null) return true;
                checkNullInitDereferenceInMethod(method, ast, file, service, issues, severityFilter);
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

    /**
     * Conservative null-deref detector: a local variable whose ONLY assignment
     * is a NullLiteral initializer is dereferenced (as the receiver of a
     * MethodInvocation or FieldAccess) somewhere in the method body.
     * Variables that are reassigned anywhere are excluded — without flow
     * analysis we can't tell whether the reassignment dominates the deref,
     * and the conservative choice is to skip those (avoid false positives).
     */
    private void checkNullInitDereferenceInMethod(MethodDeclaration method, CompilationUnit ast,
                                                   Path file, IJdtService service,
                                                   List<Map<String, Object>> issues, String severityFilter) {
        java.util.Set<String> nullOnly = new java.util.HashSet<>();

        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment frag) {
                if (frag.getInitializer() instanceof NullLiteral) {
                    nullOnly.add(frag.getName().getIdentifier());
                }
                return true;
            }
            @Override
            public boolean visit(Assignment assign) {
                if (assign.getLeftHandSide() instanceof SimpleName lhs) {
                    nullOnly.remove(lhs.getIdentifier());
                }
                return true;
            }
        });

        if (nullOnly.isEmpty()) return;

        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation mi) {
                if (mi.getExpression() instanceof SimpleName recv
                        && nullOnly.contains(recv.getIdentifier())) {
                    addIssue("NULL_DEREFERENCE",
                        "Variable `" + recv.getIdentifier() + "` is initialized to null and "
                            + "dereferenced without reassignment",
                        "high", mi, ast, file, service, issues, severityFilter);
                }
                return true;
            }
            @Override
            public boolean visit(FieldAccess fa) {
                if (fa.getExpression() instanceof SimpleName recv
                        && nullOnly.contains(recv.getIdentifier())) {
                    addIssue("NULL_DEREFERENCE",
                        "Variable `" + recv.getIdentifier() + "` is initialized to null and "
                            + "dereferenced without reassignment",
                        "high", fa, ast, file, service, issues, severityFilter);
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
