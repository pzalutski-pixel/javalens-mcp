package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
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
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

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

            Handlers are listed where a framework can register them: in a named
            top-level or static member class, interface, record or enum. A
            verb-annotated method of an anonymous class, a local class or a
            non-static inner class is no route and is not listed.

            Projects without these frameworks return an empty table.

            potentialMatches lists, the first maxResults by file and position, the
            verb annotations JDT could not confirm, each with the annotation it
            names; none is a route.

            Options:
            - maxResults: cap the reported endpoints (default 200)

            """ + POTENTIAL_MATCHES + """
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
            List<Map<String, Object>> potential = new ArrayList<>();

            for (Map.Entry<String, String> entry : SPRING_VERBS.entrySet()) {
                collectEndpoints(service, entry.getKey(), entry.getValue(), "spring",
                    endpoints, astCache, seenHandlers, potential);
            }
            for (Map.Entry<String, String> entry : JAXRS_VERBS.entrySet()) {
                collectEndpoints(service, entry.getKey(), entry.getValue(), "jaxrs",
                    endpoints, astCache, seenHandlers, potential);
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
            if (!potential.isEmpty()) {
                potential.sort(Comparator
                    .comparing((Map<String, Object> place) -> String.valueOf(place.get("filePath")))
                    .thenComparing(place -> (Integer) place.get("offset")));
                Map<String, Object> potentialMatches = new LinkedHashMap<>();
                potentialMatches.put("count", potential.size());
                potentialMatches.put("locations", potential.subList(0, Math.min(maxResults, potential.size())));
                data.put("potentialMatches", potentialMatches);
            }

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

    /**
     * The routes of the verb annotation {@code annotationFqn}. An annotation type the project's
     * classpath does not hold registers none; a failed lookup or search fails the answer, never
     * leaves that annotation's routes out.
     */
    private void collectEndpoints(IJdtService service, String annotationFqn, String httpMethod,
                                  String framework, List<Map<String, Object>> endpoints,
                                  Map<String, CompilationUnit> astCache, Set<String> seenHandlers,
                                  List<Map<String, Object>> potential) throws Exception {
        IType annotationType = service.findType(annotationFqn);
        if (annotationType == null) {
            return;
        }
        // Every use of the annotation: only those on methods are handlers. A use the search
        // could not confirm registers no route; it is a potential match.
        SearchResult found = service.getSearchService().findReferences(
            annotationType, SearchService.ReferenceKind.ANNOTATION, SearchService.ALL_MATCHES);
        for (Map<String, Object> place : formatMatches(found.potentialMatches(), service)) {
            place.put("annotation", "@" + annotationType.getElementName());
            potential.add(place);
        }
        List<SearchMatch> matches = found.matches();
        for (SearchMatch match : matches) {
            if (!(match.getElement() instanceof IMethod handler)) {
                continue;
            }
            // Only a class a framework can register holds routes.
            if (!registrable(handler.getDeclaringType())) {
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
                typeModifiers(method),
                "org.springframework.web.bind.annotation.RequestMapping"));
        } else {
            methodPath = annotationPathValue(annotationBySimpleName(method.modifiers(), "Path"));
            classPrefix = annotationPathValue(annotationBySimpleName(
                typeModifiers(method), "Path"));
        }

        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("httpMethod", httpMethod);
        endpoint.put("path", composePath(classPrefix, methodPath));
        endpoint.put("handler", ElementNames.methodKey(method.resolveBinding()));
        endpoint.put("framework", framework);
        endpoint.put("filePath", service.getPathUtils().formatPath(
            cu.getResource().getLocation().toOSString()));
        endpoint.put("line", ast.getLineNumber(method.getName().getStartPosition()) - 1);
        return endpoint;
    }

    /**
     * Whether a framework can register {@code type} as the holder of routes: a named top-level
     * type, or a static member type (a member interface, enum or record is static) with a
     * canonical name. An anonymous or local class, a class nested in one, and a non-static inner
     * class need an enclosing method context or instance, and no framework registers them.
     */
    private static boolean registrable(IType type) throws JavaModelException {
        if (type.isMember() && type.isClass() && !Flags.isStatic(type.getFlags())) {
            return false;
        }
        for (IType current = type; current != null; current = current.getDeclaringType()) {
            if (current.isAnonymous() || current.isLocal()) {
                return false;
            }
        }
        return true;
    }

    /** The modifiers of the class, interface, enum or record declaring {@code method}. */
    private static List<?> typeModifiers(MethodDeclaration method) {
        return method.getParent() instanceof AbstractTypeDeclaration type ? type.modifiers() : List.of();
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

    private static CompilationUnit parse(ICompilationUnit cu) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            return (CompilationUnit) parser.createAST(Deadline.monitor());
        } catch (Exception e) {
            return null;
        }
    }
}
