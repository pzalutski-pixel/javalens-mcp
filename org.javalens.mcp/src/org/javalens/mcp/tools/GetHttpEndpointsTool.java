package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Assemble the project's HTTP surface: effective routes composed from
 * class-level prefixes and method-level paths, mapped to handler methods.
 */
public class GetHttpEndpointsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetHttpEndpointsTool.class);

    /** Spring verb-shortcut annotation FQN -> HTTP method. */
    private static final Map<String, String> SPRING_VERBS = Map.of(
        "org.springframework.web.bind.annotation.GetMapping", "GET",
        "org.springframework.web.bind.annotation.PostMapping", "POST",
        "org.springframework.web.bind.annotation.PutMapping", "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping", "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping", "PATCH");

    /** JAX-RS verb annotation FQN -> HTTP method (jakarta and javax). */
    private static final Map<String, String> JAXRS_VERBS = buildJaxrsVerbs();

    private static Map<String, String> buildJaxrsVerbs() {
        Map<String, String> verbs = new HashMap<>();
        for (String ns : new String[] {"jakarta.ws.rs.", "javax.ws.rs."}) {
            for (String verb : new String[] {"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH"}) {
                verbs.put(ns + verb, verb);
            }
        }
        return verbs;
    }

    public GetHttpEndpointsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_http_endpoints";
    }

    @Override
    public String getDescription() {
        return """
            Assemble the project's HTTP route table.

            USAGE: get_http_endpoints()
            OUTPUT: route -> handler entries (HTTP method, effective path,
            handler method, framework, location), sorted by path.

            Supports:
            - Spring verb shortcuts: @GetMapping, @PostMapping, @PutMapping,
              @DeleteMapping, @PatchMapping; the class-level @RequestMapping
              value is composed as the path prefix.
            - JAX-RS (jakarta.ws.rs and javax.ws.rs): @GET/@POST/... verbs
              with class-level and method-level @Path composed.

            Method-level @RequestMapping(method=...) routes are not assembled;
            verb-shortcut annotations are the supported Spring form.

            Projects without these frameworks return an empty table.

            Options:
            - maxResults: cap the reported endpoints (default 200)

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("maxResults", "integer", "Maximum endpoints to return (default 200)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 200);
        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults", "must be >= 0");
        }

        try {
            List<Map<String, Object>> endpoints = new ArrayList<>();
            Map<String, CompilationUnit> astCache = new HashMap<>();
            Set<String> seenHandlers = new LinkedHashSet<>();

            for (Map.Entry<String, String> entry : SPRING_VERBS.entrySet()) {
                collectEndpoints(service, entry.getKey(), entry.getValue(), "spring",
                    endpoints, astCache, seenHandlers);
            }
            for (Map.Entry<String, String> entry : JAXRS_VERBS.entrySet()) {
                collectEndpoints(service, entry.getKey(), entry.getValue(), "jaxrs",
                    endpoints, astCache, seenHandlers);
            }

            endpoints.sort(Comparator
                .comparing((Map<String, Object> e) -> (String) e.get("path"))
                .thenComparing(e -> (String) e.get("httpMethod")));

            int total = endpoints.size();
            boolean truncated = total > maxResults;
            List<Map<String, Object>> returned = truncated ? endpoints.subList(0, maxResults) : endpoints;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("endpointCount", total);
            data.put("endpoints", returned);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(returned.size())
                .truncated(truncated)
                .suggestedNextTools(List.of(
                    "analyze_method on a handler for its callers and callees",
                    "find_affected_tests to see which tests cover a handler"))
                .build());

        } catch (Exception e) {
            log.error("Error assembling HTTP endpoints: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private void collectEndpoints(IJdtService service, String annotationFqn, String httpMethod,
                                  String framework, List<Map<String, Object>> endpoints,
                                  Map<String, CompilationUnit> astCache, Set<String> seenHandlers) {
        try {
            IType annotationType = service.findType(annotationFqn);
            if (annotationType == null) {
                return;
            }
            List<SearchMatch> matches = service.getSearchService().findReferences(
                annotationType, SearchService.ReferenceKind.ANNOTATION, 1000).matches();
            for (SearchMatch match : matches) {
                if (!(match.getElement() instanceof IMethod handler)) {
                    continue;
                }
                String dedupeKey = handler.getHandleIdentifier() + "|" + annotationFqn;
                if (!seenHandlers.add(dedupeKey)) {
                    continue;
                }
                Map<String, Object> endpoint =
                    assembleEndpoint(service, handler, annotationFqn, httpMethod, framework, astCache);
                if (endpoint != null) {
                    endpoints.add(endpoint);
                }
            }
        } catch (Exception e) {
            log.debug("Could not scan for {}: {}", annotationFqn, e.getMessage());
        }
    }

    private Map<String, Object> assembleEndpoint(IJdtService service, IMethod handler,
                                                 String verbAnnotationFqn, String httpMethod,
                                                 String framework,
                                                 Map<String, CompilationUnit> astCache) throws Exception {
        ICompilationUnit cu = handler.getCompilationUnit();
        if (cu == null) {
            return null;
        }
        CompilationUnit ast = astCache.computeIfAbsent(cu.getHandleIdentifier(), k -> parse(cu));
        if (ast == null) {
            return null;
        }

        MethodDeclaration[] found = {null};
        String handlerHandle = handler.getHandleIdentifier();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding binding = node.resolveBinding();
                if (binding != null && binding.getJavaElement() != null
                    && handlerHandle.equals(binding.getJavaElement().getHandleIdentifier())) {
                    found[0] = node;
                }
                return found[0] == null;
            }
        });
        if (found[0] == null) {
            return null;
        }
        MethodDeclaration method = found[0];

        String methodPath;
        String classPrefix;
        if (framework.equals("spring")) {
            methodPath = annotationPathValue(annotationByFqn(method.modifiers(), verbAnnotationFqn));
            classPrefix = annotationPathValue(annotationByFqn(
                enclosingType(method).modifiers(),
                "org.springframework.web.bind.annotation.RequestMapping"));
        } else {
            methodPath = annotationPathValue(annotationBySimpleName(method.modifiers(), "Path"));
            classPrefix = annotationPathValue(annotationBySimpleName(
                enclosingType(method).modifiers(), "Path"));
        }

        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("httpMethod", httpMethod);
        endpoint.put("path", composePath(classPrefix, methodPath));
        endpoint.put("handler", methodKey(method.resolveBinding()));
        endpoint.put("framework", framework);
        endpoint.put("filePath", service.getPathUtils().formatPath(
            cu.getResource().getLocation().toOSString()));
        endpoint.put("line", ast.getLineNumber(method.getName().getStartPosition()) - 1);
        return endpoint;
    }

    private static TypeDeclaration enclosingType(MethodDeclaration method) {
        return (TypeDeclaration) method.getParent();
    }

    private static String composePath(String prefix, String methodPath) {
        String left = normalize(prefix);
        String right = normalize(methodPath);
        String composed = left + right;
        return composed.isEmpty() ? "/" : composed;
    }

    private static String normalize(String segment) {
        if (segment == null || segment.isEmpty()) {
            return "";
        }
        String result = segment.startsWith("/") ? segment : "/" + segment;
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private static Annotation annotationByFqn(List<?> modifiers, String fqn) {
        for (Object modifier : modifiers) {
            if (modifier instanceof Annotation annotation) {
                ITypeBinding binding = annotation.resolveTypeBinding();
                if (binding != null && fqn.equals(binding.getQualifiedName())) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static Annotation annotationBySimpleName(List<?> modifiers, String simpleName) {
        for (Object modifier : modifiers) {
            if (modifier instanceof Annotation annotation
                && TestMethodDetector.annotationName(annotation).equals(simpleName)) {
                return annotation;
            }
        }
        return null;
    }

    /** Path string from an annotation's value member (string or first array element). */
    private static String annotationPathValue(Annotation annotation) {
        if (annotation == null) {
            return "";
        }
        Expression value = null;
        if (annotation instanceof SingleMemberAnnotation single) {
            value = single.getValue();
        } else if (annotation instanceof NormalAnnotation normal) {
            for (Object pair : normal.values()) {
                MemberValuePair mvp = (MemberValuePair) pair;
                if (mvp.getName().getIdentifier().equals("value")
                    || mvp.getName().getIdentifier().equals("path")) {
                    value = mvp.getValue();
                    break;
                }
            }
        }
        if (value instanceof ArrayInitializer array && !array.expressions().isEmpty()) {
            value = (Expression) array.expressions().get(0);
        }
        return value instanceof StringLiteral literal ? literal.getLiteralValue() : "";
    }

    private static String methodKey(IMethodBinding binding) {
        IMethodBinding declaration = binding.getMethodDeclaration();
        ITypeBinding declaring = declaration.getDeclaringClass();
        String params = Arrays.stream(declaration.getParameterTypes())
            .map(ITypeBinding::getName)
            .collect(Collectors.joining(","));
        return declaring.getErasure().getQualifiedName() + "#" + declaration.getName()
            + "(" + params + ")";
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
}
