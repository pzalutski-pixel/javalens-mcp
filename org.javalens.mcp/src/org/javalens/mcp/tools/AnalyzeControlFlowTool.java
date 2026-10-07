package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
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
import java.util.Map;
import java.util.function.Supplier;

/**
 * Analyze the control flow structure of a method.
 * Reports branching points, loops, return/throw points, and nesting depth.
 */
public class AnalyzeControlFlowTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeControlFlowTool.class);

    public AnalyzeControlFlowTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_control_flow";
    }

    @Override
    public String getDescription() {
        return """
            Analyze the control flow structure of a method.

            USAGE: analyze_control_flow(filePath="path/to/File.java", line=10, column=5)
            OUTPUT: Branching points, loops, returns, throws, and nesting depth

            Reports:
            - Branch count (if/switch/ternary)
            - Loop count and types (for/while/do-while/enhanced-for)
            - Return points with line numbers
            - Throw points with exception types and line numbers
            - Try-catch blocks with caught exception types
            - Maximum nesting depth

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "File containing the method")
            .required("line", "integer", "Zero-based line number within the method")
            .required("column", "integer", "Zero-based column number")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse paramCheck = requireParam(arguments, "filePath");
        if (paramCheck != null) return paramCheck;

        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", 0);
        int column = getIntParam(arguments, "column", 0);

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            ICompilationUnit cu = service.getCompilationUnit(filePath);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePathStr);
            }

            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(Deadline.monitor());

            int offset = service.getOffset(cu, line, column);

            // Find the enclosing method
            ASTNode node = org.eclipse.jdt.core.dom.NodeFinder.perform(ast, offset, 0);
            MethodDeclaration method = findEnclosingMethod(node);

            if (method == null) {
                return ToolResponse.symbolNotFound("No method found at " + filePathStr + ":" + line + ":" + column);
            }

            // Analyze control flow
            ControlFlowVisitor visitor = new ControlFlowVisitor(ast);
            method.getBody().accept(visitor);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", method.getName().getIdentifier());
            data.put("branches", visitor.branchCount);
            data.put("loops", Map.of(
                "total", visitor.loopCount,
                "for", visitor.forCount,
                "enhancedFor", visitor.enhancedForCount,
                "while", visitor.whileCount,
                "doWhile", visitor.doWhileCount
            ));
            data.put("returnPoints", visitor.returnPoints);
            data.put("throwPoints", visitor.throwPoints);
            data.put("tryCatchBlocks", visitor.tryCatchBlocks);
            data.put("breakStatements", visitor.breakCount);
            data.put("continueStatements", visitor.continueCount);
            data.put("maxNestingDepth", visitor.maxNestingDepth);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "get_complexity_metrics for cyclomatic and cognitive complexity",
                    "analyze_data_flow for variable usage within this method"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private MethodDeclaration findEnclosingMethod(ASTNode node) {
        while (node != null) {
            if (node instanceof MethodDeclaration md) return md;
            node = node.getParent();
        }
        return null;
    }

    private static class ControlFlowVisitor extends ASTVisitor {
        private final CompilationUnit ast;
        int branchCount = 0;
        int loopCount = 0;
        int forCount = 0;
        int enhancedForCount = 0;
        int whileCount = 0;
        int doWhileCount = 0;
        int breakCount = 0;
        int continueCount = 0;
        int maxNestingDepth = 0;
        int currentNestingDepth = 0;
        List<Map<String, Object>> returnPoints = new ArrayList<>();
        List<Map<String, Object>> throwPoints = new ArrayList<>();
        List<Map<String, Object>> tryCatchBlocks = new ArrayList<>();

        ControlFlowVisitor(CompilationUnit ast) {
            this.ast = ast;
        }

        private void pushNesting() {
            currentNestingDepth++;
            maxNestingDepth = Math.max(maxNestingDepth, currentNestingDepth);
        }

        private void popNesting() {
            currentNestingDepth--;
        }

        @Override
        public boolean visit(IfStatement node) {
            branchCount++;
            pushNesting();
            return true;
        }

        @Override
        public void endVisit(IfStatement node) {
            popNesting();
        }

        @Override
        public boolean visit(SwitchCase node) {
            if (!node.isDefault()) branchCount++;
            return true;
        }

        @Override
        public boolean visit(ConditionalExpression node) {
            branchCount++;
            return true;
        }

        @Override
        public boolean visit(ForStatement node) {
            loopCount++;
            forCount++;
            pushNesting();
            return true;
        }

        @Override
        public void endVisit(ForStatement node) {
            popNesting();
        }

        @Override
        public boolean visit(EnhancedForStatement node) {
            loopCount++;
            enhancedForCount++;
            pushNesting();
            return true;
        }

        @Override
        public void endVisit(EnhancedForStatement node) {
            popNesting();
        }

        @Override
        public boolean visit(WhileStatement node) {
            loopCount++;
            whileCount++;
            pushNesting();
            return true;
        }

        @Override
        public void endVisit(WhileStatement node) {
            popNesting();
        }

        @Override
        public boolean visit(DoStatement node) {
            loopCount++;
            doWhileCount++;
            pushNesting();
            return true;
        }

        @Override
        public void endVisit(DoStatement node) {
            popNesting();
        }

        @Override
        public boolean visit(ReturnStatement node) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
            returnPoints.add(point);
            return true;
        }

        @Override
        public boolean visit(ThrowStatement node) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
            if (node.getExpression() != null) {
                point.put("expression", node.getExpression().toString());
            }
            throwPoints.add(point);
            return true;
        }

        @Override
        public boolean visit(TryStatement node) {
            pushNesting();
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
            List<String> caughtTypes = new ArrayList<>();
            for (Object catchObj : node.catchClauses()) {
                if (catchObj instanceof CatchClause catchClause) {
                    caughtTypes.add(catchClause.getException().getType().toString());
                }
            }
            block.put("caughtTypes", caughtTypes);
            block.put("hasFinally", node.getFinally() != null);
            tryCatchBlocks.add(block);
            return true;
        }

        @Override
        public void endVisit(TryStatement node) {
            popNesting();
        }

        @Override
        public boolean visit(BreakStatement node) {
            breakCount++;
            return true;
        }

        @Override
        public boolean visit(ContinueStatement node) {
            continueCount++;
            return true;
        }
    }
}
