package org.javalens.mcp.tools;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bounded interprocedural flow tracking for analyze_data_flow's followCalls
 * mode. Two fact kinds:
 *
 * <ul>
 *   <li><b>null</b> — locals assigned the null literal; followed through
 *       argument-to-parameter hops into project-source callees; sink = a
 *       dereference of the tracked value (potential NPE).</li>
 *   <li><b>taint</b> — the selected method's parameters; propagated through
 *       aliases and expressions containing the tracked value; sink = the
 *       value escaping into a non-project (binary) callee.</li>
 * </ul>
 *
 * <p>May-analysis: reassignments do not kill facts. Returned values are not
 * tracked back into callers. Recursion terminates via a visited set on
 * (fact, method, variable); descent is bounded by maxCallDepth.
 */
final class InterproceduralFlowAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(InterproceduralFlowAnalyzer.class);

    private final int maxCallDepth;
    private final Map<String, CompilationUnit> astCache = new HashMap<>();
    private final Set<String> visitedFrames = new HashSet<>();
    private final Set<String> emitted = new HashSet<>();
    private final List<Map<String, Object>> flows = new ArrayList<>();

    InterproceduralFlowAnalyzer(int maxCallDepth) {
        this.maxCallDepth = maxCallDepth;
    }

    /** Source metadata carried by every flow a fact produces. */
    private record FactOrigin(String fact, String sourceVariable, String sourceMethod, int sourceLine) {
    }

    List<Map<String, Object>> analyze(MethodDeclaration selected, CompilationUnit selectedAst) {
        IMethodBinding binding = selected.resolveBinding();
        if (binding == null) {
            return flows;
        }
        String methodKey = methodKey(binding);

        // Taint seeds: the selected method's parameters.
        for (Object parameter : selected.parameters()) {
            SingleVariableDeclaration svd = (SingleVariableDeclaration) parameter;
            IVariableBinding vb = svd.resolveBinding();
            if (vb != null) {
                FactOrigin origin = new FactOrigin("taint", svd.getName().getIdentifier(),
                    methodKey, lineOf(selectedAst, svd.getName()));
                walkFrame(origin, selected, selectedAst, methodKey, vb, 0, new ArrayList<>());
            }
        }

        // Null seeds: locals declared with or assigned the null literal.
        List<SimpleName> nullSeeds = new ArrayList<>();
        selected.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                if (node.getInitializer() instanceof NullLiteral) {
                    nullSeeds.add(node.getName());
                }
                return true;
            }

            @Override
            public boolean visit(Assignment node) {
                if (node.getRightHandSide() instanceof NullLiteral
                    && node.getLeftHandSide() instanceof SimpleName name) {
                    nullSeeds.add(name);
                }
                return true;
            }
        });
        for (SimpleName seed : nullSeeds) {
            if (seed.resolveBinding() instanceof IVariableBinding vb && !vb.isField()) {
                FactOrigin origin = new FactOrigin("null", seed.getIdentifier(),
                    methodKey, lineOf(selectedAst, seed));
                walkFrame(origin, selected, selectedAst, methodKey, vb, 0, new ArrayList<>());
            }
        }
        return flows;
    }

    /**
     * Walk one method frame tracking a single variable (plus aliases found
     * along the way) in source order.
     */
    private void walkFrame(FactOrigin origin, MethodDeclaration method, CompilationUnit ast,
                           String methodKey, IVariableBinding tracked, int depth,
                           List<Map<String, Object>> steps) {
        IMethodBinding frameBinding = method.resolveBinding();
        String frameKey = origin.fact() + "|" + (frameBinding != null ? frameBinding.getKey() : methodKey)
            + "|" + tracked.getKey();
        if (!visitedFrames.add(frameKey)) {
            return;
        }

        Set<String> trackedKeys = new HashSet<>();
        trackedKeys.add(tracked.getKey());

        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                Expression init = node.getInitializer();
                if (init != null && carries(init, trackedKeys, origin.fact())
                    && node.resolveBinding() != null) {
                    trackedKeys.add(node.resolveBinding().getKey());
                    steps.add(aliasStep(node.getName().getIdentifier(), lineOf(ast, node)));
                }
                return true;
            }

            @Override
            public boolean visit(Assignment node) {
                if (node.getLeftHandSide() instanceof SimpleName lhs
                    && lhs.resolveBinding() instanceof IVariableBinding lhsVar
                    && !trackedKeys.contains(lhsVar.getKey())
                    && carries(node.getRightHandSide(), trackedKeys, origin.fact())) {
                    trackedKeys.add(lhsVar.getKey());
                    steps.add(aliasStep(lhs.getIdentifier(), lineOf(ast, node)));
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                // Dereference sink: a tracked (possibly null) value used as receiver.
                if (origin.fact().equals("null") && node.getExpression() instanceof SimpleName receiver
                    && isTracked(receiver, trackedKeys)) {
                    emitFlow(origin, steps, sink("dereference", node.toString(), methodKey,
                        lineOf(ast, node), null));
                }
                handleArguments(node.resolveMethodBinding(), node.arguments(), node, ast, methodKey,
                    trackedKeys, origin, depth, steps);
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                handleArguments(node.resolveConstructorBinding(), node.arguments(), node, ast,
                    methodKey, trackedKeys, origin, depth, steps);
                return true;
            }

            @Override
            public boolean visit(FieldAccess node) {
                if (origin.fact().equals("null") && node.getExpression() instanceof SimpleName qualifier
                    && isTracked(qualifier, trackedKeys)) {
                    emitFlow(origin, steps, sink("dereference", node.toString(), methodKey,
                        lineOf(ast, node), null));
                }
                return true;
            }

            @Override
            public boolean visit(QualifiedName node) {
                if (origin.fact().equals("null") && node.getQualifier() instanceof SimpleName qualifier
                    && isTracked(qualifier, trackedKeys)) {
                    emitFlow(origin, steps, sink("dereference", node.toString(), methodKey,
                        lineOf(ast, node), null));
                }
                return true;
            }
        });
    }

    private void handleArguments(IMethodBinding callee, List<?> arguments, ASTNode site,
                                 CompilationUnit ast, String methodKey, Set<String> trackedKeys,
                                 FactOrigin origin, int depth, List<Map<String, Object>> steps) {
        if (callee == null) {
            return;
        }
        IMethodBinding declaration = callee.getMethodDeclaration();
        ITypeBinding declaring = declaration.getDeclaringClass();
        if (declaring == null) {
            return;
        }

        for (int i = 0; i < arguments.size(); i++) {
            Expression argument = (Expression) arguments.get(i);
            boolean direct = argument instanceof SimpleName name && isTracked(name, trackedKeys);
            boolean carried = direct
                || (origin.fact().equals("taint") && carries(argument, trackedKeys, origin.fact()));
            if (!carried) {
                continue;
            }

            if (declaring.isFromSource() && depth < maxCallDepth) {
                descendIntoCallee(declaration, i, site, ast, origin, depth, steps);
            } else if (!declaring.isFromSource() && origin.fact().equals("taint")) {
                emitFlow(origin, steps, sink("escape", site.toString(), methodKey,
                    lineOf(ast, site), methodKey(declaration)));
            }
        }
    }

    private void descendIntoCallee(IMethodBinding declaration, int argIndex, ASTNode site,
                                   CompilationUnit callerAst, FactOrigin origin, int depth,
                                   List<Map<String, Object>> steps) {
        try {
            if (!(declaration.getJavaElement() instanceof IMethod calleeElement)) {
                return;
            }
            ICompilationUnit calleeCu = calleeElement.getCompilationUnit();
            if (calleeCu == null) {
                return;
            }
            CompilationUnit calleeAst = astCache.computeIfAbsent(
                calleeCu.getHandleIdentifier(), k -> parse(calleeCu));
            if (calleeAst == null) {
                return;
            }
            MethodDeclaration calleeMethod = findMethod(calleeAst, declaration.getKey());
            if (calleeMethod == null || argIndex >= calleeMethod.parameters().size()) {
                return;
            }
            SingleVariableDeclaration parameter =
                (SingleVariableDeclaration) calleeMethod.parameters().get(argIndex);
            IVariableBinding parameterBinding = parameter.resolveBinding();
            if (parameterBinding == null) {
                return;
            }

            List<Map<String, Object>> nextSteps = new ArrayList<>(steps);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("kind", "argument");
            step.put("callee", methodKey(declaration));
            step.put("parameter", parameter.getName().getIdentifier());
            step.put("argIndex", argIndex);
            step.put("line", lineOf(callerAst, site));
            nextSteps.add(step);

            walkFrame(origin, calleeMethod, calleeAst, methodKey(declaration),
                parameterBinding, depth + 1, nextSteps);
        } catch (Exception e) {
            log.debug("Could not descend into callee {}: {}", declaration.getName(), e.getMessage());
        }
    }

    /** Whether the expression carries a tracked value (taint: anywhere in the subtree). */
    private static boolean carries(Expression expression, Set<String> trackedKeys, String fact) {
        if (expression instanceof SimpleName name) {
            return isTracked(name, trackedKeys);
        }
        if (!fact.equals("taint")) {
            return false;
        }
        boolean[] found = {false};
        expression.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (isTracked(node, trackedKeys)) {
                    found[0] = true;
                }
                return !found[0];
            }
        });
        return found[0];
    }

    private static boolean isTracked(SimpleName name, Set<String> trackedKeys) {
        return name.resolveBinding() instanceof IVariableBinding vb && trackedKeys.contains(vb.getKey());
    }

    private void emitFlow(FactOrigin origin, List<Map<String, Object>> steps, Map<String, Object> sink) {
        String signature = origin.fact() + "|" + origin.sourceMethod() + "|" + origin.sourceVariable()
            + "|" + sink.get("method") + "|" + sink.get("line") + "|" + sink.get("expression");
        if (!emitted.add(signature)) {
            return;
        }
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("fact", origin.fact());
        flow.put("sourceVariable", origin.sourceVariable());
        flow.put("sourceMethod", origin.sourceMethod());
        flow.put("sourceLine", origin.sourceLine());
        flow.put("steps", List.copyOf(steps));
        flow.put("sink", sink);
        flows.add(flow);
    }

    private static Map<String, Object> aliasStep(String variable, int line) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", "alias");
        step.put("variable", variable);
        step.put("line", line);
        return step;
    }

    private static Map<String, Object> sink(String kind, String expression, String methodKey,
                                            int line, String callee) {
        Map<String, Object> sink = new LinkedHashMap<>();
        sink.put("kind", kind);
        if (callee != null) {
            sink.put("callee", callee);
        }
        sink.put("expression", expression);
        sink.put("method", methodKey);
        sink.put("line", line);
        return sink;
    }

    private static CompilationUnit parse(ICompilationUnit cu) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            return (CompilationUnit) parser.createAST(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static MethodDeclaration findMethod(CompilationUnit ast, String bindingKey) {
        MethodDeclaration[] result = {null};
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding binding = node.resolveBinding();
                if (binding != null && bindingKey.equals(binding.getKey())) {
                    result[0] = node;
                }
                return result[0] == null;
            }
        });
        return result[0];
    }

    private static String methodKey(IMethodBinding binding) {
        IMethodBinding declaration = binding.getMethodDeclaration();
        ITypeBinding declaring = declaration.getDeclaringClass();
        String typeName = declaring.getErasure().getQualifiedName();
        String name = declaration.isConstructor() ? declaring.getName() : declaration.getName();
        String params = Arrays.stream(declaration.getParameterTypes())
            .map(ITypeBinding::getName)
            .collect(Collectors.joining(","));
        return typeName + "#" + name + "(" + params + ")";
    }

    private static int lineOf(CompilationUnit ast, ASTNode node) {
        return ast.getLineNumber(node.getStartPosition()) - 1;
    }
}
