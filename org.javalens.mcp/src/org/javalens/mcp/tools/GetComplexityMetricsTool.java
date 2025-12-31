package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.WhileStatement;
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
 * Calculate code complexity metrics (cyclomatic, cognitive, LOC).
 *
 * Provides metrics at file, type, or method granularity with risk assessment.
 */
public class GetComplexityMetricsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetComplexityMetricsTool.class);

    public GetComplexityMetricsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_complexity_metrics";
    }

    @Override
    public String getDescription() {
        return """
            Get cyclomatic complexity, cognitive complexity, LOC.

            USAGE: get_complexity_metrics(filePath="path/to/File.java")
            OUTPUT: Complexity metrics with risk assessment

            Metrics:
            - Cyclomatic Complexity: Count of decision points (+1 for if/for/while/case/catch)
            - Cognitive Complexity: Penalizes nesting and breaks in linear flow
            - LOC: Physical and logical lines of code

            Risk levels:
            - High: CC > 10
            - Medium: CC 6-10
            - Low: CC <= 5

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
            "granularity", Map.of(
                "type", "string",
                "description", "Level of detail: 'file', 'type', or 'method' (default: 'file')"
            ),
            "includeDetails", Map.of(
                "type", "boolean",
                "description", "Include per-method breakdown (default: true)"
            )
        ));
        schema.put("required", List.of("filePath"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        String granularity = getStringParam(arguments, "granularity", "file");
        boolean includeDetails = getBooleanParam(arguments, "includeDetails", true);

        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            String source = cu.getSource();

            // Parse AST
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            // Calculate file-level metrics
            String[] lines = source.split("\n");
            int physicalLOC = lines.length;
            int blankLines = 0;
            int commentLines = 0;

            boolean inBlockComment = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    blankLines++;
                } else if (inBlockComment) {
                    commentLines++;
                    if (trimmed.contains("*/")) {
                        inBlockComment = false;
                    }
                } else if (trimmed.startsWith("//")) {
                    commentLines++;
                } else if (trimmed.startsWith("/*")) {
                    commentLines++;
                    if (!trimmed.contains("*/")) {
                        inBlockComment = true;
                    }
                }
            }

            // Collect method metrics
            List<Map<String, Object>> methodMetrics = new ArrayList<>();
            int totalCC = 0;
            int totalCognitive = 0;
            int maxCC = 0;

            for (Object type : ast.types()) {
                if (type instanceof TypeDeclaration typeDecl) {
                    collectMethodMetrics(typeDecl, ast, methodMetrics);
                }
            }

            // Calculate totals
            int highRisk = 0;
            int mediumRisk = 0;
            int lowRisk = 0;

            for (Map<String, Object> method : methodMetrics) {
                int cc = (int) method.get("cyclomaticComplexity");
                int cognitive = (int) method.get("cognitiveComplexity");
                totalCC += cc;
                totalCognitive += cognitive;
                if (cc > maxCC) maxCC = cc;

                String risk = (String) method.get("risk");
                switch (risk) {
                    case "high" -> highRisk++;
                    case "medium" -> mediumRisk++;
                    default -> lowRisk++;
                }
            }

            double avgCC = methodMetrics.isEmpty() ? 0 : (double) totalCC / methodMetrics.size();

            // Build response
            Map<String, Object> data = new LinkedHashMap<>();

            // File info
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("path", service.getPathUtils().formatPath(path));
            fileInfo.put("physicalLOC", physicalLOC);
            fileInfo.put("blankLines", blankLines);
            fileInfo.put("commentLines", commentLines);
            fileInfo.put("codeLOC", physicalLOC - blankLines - commentLines);
            data.put("file", fileInfo);

            // Summary
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalCyclomaticComplexity", totalCC);
            summary.put("totalCognitiveComplexity", totalCognitive);
            summary.put("averageMethodCC", Math.round(avgCC * 100.0) / 100.0);
            summary.put("maxMethodCC", maxCC);
            summary.put("methodCount", methodMetrics.size());
            data.put("summary", summary);

            // Risk assessment
            Map<String, Object> risk = new LinkedHashMap<>();
            risk.put("highRiskMethods", highRisk);
            risk.put("mediumRiskMethods", mediumRisk);
            risk.put("lowRiskMethods", lowRisk);
            data.put("riskAssessment", risk);

            // Method details
            if (includeDetails) {
                data.put("methods", methodMetrics);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(methodMetrics.size())
                .returnedCount(methodMetrics.size())
                .suggestedNextTools(highRisk > 0
                    ? List.of("Consider refactoring high-complexity methods")
                    : List.of("get_dependency_graph to analyze dependencies"))
                .build());

        } catch (Exception e) {
            log.error("Error calculating complexity metrics: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Collect metrics for all methods in a type (including nested types).
     */
    private void collectMethodMetrics(TypeDeclaration typeDecl, CompilationUnit ast,
                                       List<Map<String, Object>> metrics) {
        String typeName = typeDecl.getName().getIdentifier();

        for (MethodDeclaration method : typeDecl.getMethods()) {
            Map<String, Object> methodInfo = new LinkedHashMap<>();
            methodInfo.put("name", method.getName().getIdentifier());
            methodInfo.put("type", typeName);
            methodInfo.put("line", ast.getLineNumber(method.getStartPosition()) - 1);

            // Calculate cyclomatic complexity
            int cc = calculateCyclomaticComplexity(method);
            methodInfo.put("cyclomaticComplexity", cc);

            // Calculate cognitive complexity
            int cognitive = calculateCognitiveComplexity(method);
            methodInfo.put("cognitiveComplexity", cognitive);

            // Calculate method LOC
            int startLine = ast.getLineNumber(method.getStartPosition());
            int endLine = ast.getLineNumber(method.getStartPosition() + method.getLength());
            methodInfo.put("physicalLOC", endLine - startLine + 1);

            // Parameter count
            methodInfo.put("parameterCount", method.parameters().size());

            // Risk classification
            String risk = cc > 10 ? "high" : (cc > 5 ? "medium" : "low");
            methodInfo.put("risk", risk);

            metrics.add(methodInfo);
        }

        // Process nested types
        for (TypeDeclaration nestedType : typeDecl.getTypes()) {
            collectMethodMetrics(nestedType, ast, metrics);
        }
    }

    /**
     * Calculate cyclomatic complexity for a method.
     * CC = 1 + number of decision points
     */
    private int calculateCyclomaticComplexity(MethodDeclaration method) {
        final int[] complexity = {1}; // Base complexity

        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(IfStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(ForStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(EnhancedForStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(WhileStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(DoStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(SwitchCase node) {
                if (!node.isDefault()) {
                    complexity[0]++;
                }
                return true;
            }

            @Override
            public boolean visit(CatchClause node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(ConditionalExpression node) {
                complexity[0]++; // Ternary operator
                return true;
            }

            @Override
            public boolean visit(InfixExpression node) {
                // && and || add to complexity
                if (node.getOperator() == InfixExpression.Operator.CONDITIONAL_AND ||
                    node.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
                    complexity[0]++;
                }
                return true;
            }

            @Override
            public boolean visit(ThrowStatement node) {
                complexity[0]++;
                return true;
            }
        });

        return complexity[0];
    }

    /**
     * Calculate cognitive complexity for a method.
     * Penalizes nesting and breaks in linear flow.
     */
    private int calculateCognitiveComplexity(MethodDeclaration method) {
        final int[] complexity = {0};
        final int[] nestingLevel = {0};
        final String methodName = method.getName().getIdentifier();

        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(IfStatement node) {
                // +1 for if, +nesting penalty
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(IfStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(ForStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(ForStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(EnhancedForStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(EnhancedForStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(WhileStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(WhileStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(DoStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(DoStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(TryStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(TryStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(CatchClause node) {
                complexity[0] += 1 + nestingLevel[0];
                return true;
            }

            @Override
            public boolean visit(ConditionalExpression node) {
                complexity[0] += 1 + nestingLevel[0];
                return true;
            }

            @Override
            public boolean visit(InfixExpression node) {
                // Binary logical operators (first in sequence only)
                if (node.getOperator() == InfixExpression.Operator.CONDITIONAL_AND ||
                    node.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
                    // Check if parent is also a logical expression
                    if (!(node.getParent() instanceof InfixExpression parent) ||
                        (parent.getOperator() != InfixExpression.Operator.CONDITIONAL_AND &&
                         parent.getOperator() != InfixExpression.Operator.CONDITIONAL_OR)) {
                        complexity[0]++;
                    }
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                // Recursion adds complexity
                if (node.getName().getIdentifier().equals(methodName)) {
                    complexity[0]++;
                }
                return true;
            }
        });

        return complexity[0];
    }
}
