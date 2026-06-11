package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.TypeDeclaration;
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
 * Analyze data flow within a method.
 * Reports which variables are declared, read, written, and whether they are
 * parameters, local variables, or fields.
 */
public class AnalyzeDataFlowTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeDataFlowTool.class);

    public AnalyzeDataFlowTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_data_flow";
    }

    @Override
    public String getDescription() {
        return """
            Analyze data flow within a method.

            USAGE: analyze_data_flow(filePath="path/to/File.java", line=10, column=5)
            OUTPUT: Variables with read/write/declaration info

            Reports for each variable:
            - name and type
            - whether it is declared, read, written
            - whether it is a parameter, local variable, or field
            - return statement count and types

            Useful for understanding side effects before extracting methods.

            Options:
            - followCalls: opt-in interprocedural mode (default false). Tracks
              two fact kinds across argument-to-parameter hops into project
              callees and reports interproceduralFlows:
              * null facts - locals assigned null; sink = a dereference of the
                tracked value in a callee (potential NPE)
              * taint facts - this method's parameters, propagated through
                aliases and expressions; sink = the value escaping into a
                non-project (binary) callee
              May-analysis: reassignments do not kill facts. Returned values
              are not tracked back into callers.
            - maxCallDepth: call-edge bound for followCalls (default 2)

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "File containing the method")
            .required("line", "integer", "Zero-based line number within the method")
            .required("column", "integer", "Zero-based column number")
            .optional("followCalls", "boolean", "Track null/taint facts across method calls (default false)")
            .optional("maxCallDepth", "integer", "Call-edge bound for followCalls (default 2, min 1)")
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
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            int offset = service.getOffset(cu, line, column);

            // Find the enclosing method
            ASTNode node = org.eclipse.jdt.core.dom.NodeFinder.perform(ast, offset, 0);
            MethodDeclaration method = findEnclosingMethod(node);

            if (method == null) {
                return ToolResponse.symbolNotFound("No method found at " + filePathStr + ":" + line + ":" + column);
            }

            // Analyze data flow
            DataFlowVisitor visitor = new DataFlowVisitor();
            method.accept(visitor);

            // Build variable list
            List<Map<String, Object>> variables = new ArrayList<>();
            for (Map.Entry<String, VariableInfo> entry : visitor.variables.entrySet()) {
                VariableInfo info = entry.getValue();
                Map<String, Object> varData = new LinkedHashMap<>();
                varData.put("name", entry.getKey());
                varData.put("type", info.type);
                varData.put("kind", info.kind);
                varData.put("declared", info.declared);
                varData.put("read", info.readCount > 0);
                varData.put("written", info.writeCount > 0);
                varData.put("readCount", info.readCount);
                varData.put("writeCount", info.writeCount);
                variables.add(varData);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", method.getName().getIdentifier());
            data.put("parameterCount", method.parameters().size());
            data.put("variables", variables);
            data.put("returnStatements", visitor.returnCount);

            if (getBooleanParam(arguments, "followCalls", false)) {
                int maxCallDepth = getIntParam(arguments, "maxCallDepth", 2);
                if (maxCallDepth < 1) {
                    return ToolResponse.invalidParameter("maxCallDepth", "must be >= 1");
                }
                data.put("followCalls", true);
                data.put("interproceduralFlows",
                    new InterproceduralFlowAnalyzer(maxCallDepth).analyze(method, ast));
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "analyze_control_flow for branching and loop analysis",
                    "extract_method to extract code with understood data dependencies"
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

    private static class VariableInfo {
        String type = "unknown";
        String kind = "local"; // "parameter", "local", "field"
        boolean declared = false;
        int readCount = 0;
        int writeCount = 0;
    }

    private static class DataFlowVisitor extends ASTVisitor {
        Map<String, VariableInfo> variables = new LinkedHashMap<>();
        int returnCount = 0;

        private VariableInfo getOrCreate(String name) {
            return variables.computeIfAbsent(name, k -> new VariableInfo());
        }

        @Override
        public boolean visit(SingleVariableDeclaration node) {
            String name = node.getName().getIdentifier();
            VariableInfo info = getOrCreate(name);
            info.declared = true;
            info.kind = "parameter";
            if (node.getType() != null) {
                info.type = node.getType().toString();
            }
            return true;
        }

        @Override
        public boolean visit(VariableDeclarationFragment node) {
            String name = node.getName().getIdentifier();
            VariableInfo info = getOrCreate(name);
            info.declared = true;
            if (info.kind.equals("local")) {
                // Don't overwrite if already set to "parameter"
                if (node.getParent() instanceof VariableDeclarationStatement vds && vds.getType() != null) {
                    info.type = vds.getType().toString();
                }
            }
            if (node.getInitializer() != null) {
                info.writeCount++;
            }
            return true;
        }

        @Override
        public boolean visit(SimpleName node) {
            String name = node.getIdentifier();

            // Skip if this is a declaration name (handled by other visitors)
            ASTNode parent = node.getParent();
            if (parent instanceof VariableDeclarationFragment vdf && vdf.getName() == node) return true;
            if (parent instanceof SingleVariableDeclaration svd && svd.getName() == node) return true;
            if (parent instanceof MethodDeclaration) return true;
            if (parent instanceof TypeDeclaration) return true;

            // Only track variables we've already declared
            if (!variables.containsKey(name)) {
                // Try binding for fields
                IBinding binding = node.resolveBinding();
                if (binding instanceof IVariableBinding varBinding && varBinding.isField()) {
                    VariableInfo info = getOrCreate(name);
                    info.kind = "field";
                    if (varBinding.getType() != null) {
                        info.type = varBinding.getType().getName();
                    }
                } else {
                    return true; // Not a variable we're tracking
                }
            }

            VariableInfo info = variables.get(name);
            if (info == null) return true;

            // Determine the "effective expression" for write/read classification.
            // When the SimpleName is the name part of a qualified-field-access form —
            // `this.x`, `super.x`, `Outer.x`, or `obj.x` — the AST wraps it in a
            // FieldAccess / SuperFieldAccess / QualifiedName parent. The assignment
            // / postfix / prefix checks should target THAT wrapper, not the bare
            // SimpleName. Otherwise `this.x = 5` mis-classifies `x` as a read
            // (the SimpleName's immediate parent is FieldAccess, not Assignment).
            ASTNode effective = node;
            if (parent instanceof FieldAccess fa && fa.getName() == node) {
                effective = parent;
            } else if (parent instanceof SuperFieldAccess sfa && sfa.getName() == node) {
                effective = parent;
            } else if (parent instanceof QualifiedName qn && qn.getName() == node) {
                effective = parent;
            }
            ASTNode effectiveParent = effective.getParent();

            // Determine if this is a write or read.
            if (effectiveParent instanceof Assignment assignment
                    && assignment.getLeftHandSide() == effective) {
                Assignment.Operator op = assignment.getOperator();
                if (op == Assignment.Operator.ASSIGN) {
                    info.writeCount++;
                } else {
                    // Compound assignment (+=, -=, *=, /=, %=, &=, |=, ^=, <<=, >>=, >>>=)
                    // reads the current value, combines with RHS, writes back.
                    info.writeCount++;
                    info.readCount++;
                }
            } else if (effectiveParent instanceof PostfixExpression) {
                info.writeCount++;
                info.readCount++;
            } else if (effectiveParent instanceof PrefixExpression prefix) {
                PrefixExpression.Operator op = prefix.getOperator();
                if (op == PrefixExpression.Operator.INCREMENT || op == PrefixExpression.Operator.DECREMENT) {
                    info.writeCount++;
                    info.readCount++;
                } else {
                    info.readCount++;
                }
            } else {
                info.readCount++;
            }

            return true;
        }

        @Override
        public boolean visit(ReturnStatement node) {
            returnCount++;
            return true;
        }
    }
}
