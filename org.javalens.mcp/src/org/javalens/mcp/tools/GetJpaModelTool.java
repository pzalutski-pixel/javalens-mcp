package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Assemble the JPA entity model: entities, table names, id fields, and
 * relationships with resolved target entities and ownership sides. The
 * assembly is what an agent would otherwise reconstruct by reading every
 * annotated file.
 */
public class GetJpaModelTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetJpaModelTool.class);

    private static final String[] ENTITY_ANNOTATIONS = {
        "jakarta.persistence.Entity",
        "javax.persistence.Entity",
    };

    private static final Set<String> RELATIONSHIP_KINDS =
        Set.of("OneToMany", "ManyToOne", "OneToOne", "ManyToMany");

    public GetJpaModelTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_jpa_model";
    }

    @Override
    public String getDescription() {
        return """
            Assemble the project's JPA entity model.

            USAGE: get_jpa_model()
            OUTPUT: Entities with table name, id field, and relationships
            (kind, target entity, mappedBy side), with locations.

            Scans @Entity types (jakarta.persistence and javax.persistence)
            and reads @Table, @Id, and @OneToMany/@ManyToOne/@OneToOne/
            @ManyToMany field annotations. Relationship targets are resolved
            from the field's type binding, including through collection type
            arguments (List<Order> -> Order).

            Projects without JPA on the classpath return an empty model.

            Options:
            - maxResults: cap the reported entities (default 100)

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("maxResults", "integer", "Maximum entities to return (default 100)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 100);
        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults", "must be >= 0");
        }

        try {
            List<IType> entityTypes = findEntityTypes(service);
            entityTypes.sort(Comparator.comparing(t -> t.getFullyQualifiedName('.')));

            List<Map<String, Object>> entities = new ArrayList<>();
            for (IType type : entityTypes) {
                Map<String, Object> entity = assembleEntity(service, type);
                if (entity != null) {
                    entities.add(entity);
                }
            }

            int total = entities.size();
            boolean truncated = total > maxResults;
            List<Map<String, Object>> returned = truncated ? entities.subList(0, maxResults) : entities;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("entityCount", total);
            data.put("entities", returned);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(returned.size())
                .truncated(truncated)
                .suggestedNextTools(List.of(
                    "analyze_type for full details of an entity",
                    "find_references to see where an entity is used"))
                .build());

        } catch (Exception e) {
            log.error("Error assembling JPA model: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private List<IType> findEntityTypes(IJdtService service) {
        Set<String> seen = new LinkedHashSet<>();
        List<IType> types = new ArrayList<>();
        for (String fqn : ENTITY_ANNOTATIONS) {
            try {
                IType annotationType = service.findType(fqn);
                if (annotationType == null) {
                    continue;
                }
                List<SearchMatch> matches = service.getSearchService().findReferences(
                    annotationType, SearchService.ReferenceKind.ANNOTATION, 1000).matches();
                for (SearchMatch match : matches) {
                    if (match.getElement() instanceof IType type
                        && seen.add(type.getHandleIdentifier())) {
                        types.add(type);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not scan for {}: {}", fqn, e.getMessage());
            }
        }
        return types;
    }

    private Map<String, Object> assembleEntity(IJdtService service, IType type) throws Exception {
        ICompilationUnit cu = type.getCompilationUnit();
        if (cu == null) {
            return null;
        }
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        String qualifiedName = type.getFullyQualifiedName('.');
        TypeDeclaration[] declaration = {null};
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                ITypeBinding binding = node.resolveBinding();
                if (binding != null && qualifiedName.equals(binding.getQualifiedName())) {
                    declaration[0] = node;
                }
                return declaration[0] == null;
            }
        });
        if (declaration[0] == null) {
            return null;
        }
        TypeDeclaration typeDecl = declaration[0];

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", qualifiedName);

        String table = annotationStringValue(annotationOn(typeDecl.modifiers(), "Table"), "name");
        if (table != null && !table.isEmpty()) {
            entity.put("table", table);
        }

        String idField = null;
        List<Map<String, Object>> relationships = new ArrayList<>();
        for (FieldDeclaration field : typeDecl.getFields()) {
            String fieldName = field.fragments().isEmpty() ? null
                : ((VariableDeclarationFragment) field.fragments().get(0)).getName().getIdentifier();
            if (fieldName == null) {
                continue;
            }
            if (idField == null && annotationOn(field.modifiers(), "Id") != null) {
                idField = fieldName;
            }
            for (String kind : RELATIONSHIP_KINDS) {
                Annotation relationship = annotationOn(field.modifiers(), kind);
                if (relationship == null) {
                    continue;
                }
                Map<String, Object> rel = new LinkedHashMap<>();
                rel.put("field", fieldName);
                rel.put("kind", kind);
                String target = relationshipTarget(field);
                if (target != null) {
                    rel.put("target", target);
                }
                String mappedBy = annotationStringValue(relationship, "mappedBy");
                if (mappedBy != null && !mappedBy.isEmpty()) {
                    rel.put("mappedBy", mappedBy);
                }
                relationships.add(rel);
            }
        }
        if (idField != null) {
            entity.put("idField", idField);
        }
        entity.put("relationships", relationships);
        entity.put("filePath", service.getPathUtils().formatPath(
            cu.getResource().getLocation().toOSString()));
        entity.put("line", ast.getLineNumber(typeDecl.getName().getStartPosition()) - 1);
        return entity;
    }

    /** Relationship target from the field type binding, through collection type arguments. */
    private static String relationshipTarget(FieldDeclaration field) {
        ITypeBinding binding = field.getType().resolveBinding();
        if (binding == null) {
            return null;
        }
        if (binding.isParameterizedType() && binding.getTypeArguments().length == 1) {
            return binding.getTypeArguments()[0].getErasure().getQualifiedName();
        }
        return binding.getErasure().getQualifiedName();
    }

    /** First annotation on the declaration matching the simple name (persistence package or unresolved). */
    private static Annotation annotationOn(List<?> modifiers, String simpleName) {
        for (Object modifier : modifiers) {
            if (modifier instanceof Annotation annotation) {
                ITypeBinding binding = annotation.resolveTypeBinding();
                String qualified = binding != null ? binding.getQualifiedName() : "";
                if (qualified.equals("jakarta.persistence." + simpleName)
                    || qualified.equals("javax.persistence." + simpleName)
                    || (binding == null && TestMethodDetector.annotationName(annotation).equals(simpleName))) {
                    return annotation;
                }
            }
        }
        return null;
    }

    /** String member value: single-member annotations use "value", normal annotations the given name. */
    private static String annotationStringValue(Annotation annotation, String memberName) {
        if (annotation instanceof SingleMemberAnnotation single
            && single.getValue() instanceof StringLiteral literal) {
            return literal.getLiteralValue();
        }
        if (annotation instanceof NormalAnnotation normal) {
            for (Object value : normal.values()) {
                MemberValuePair pair = (MemberValuePair) value;
                if (pair.getName().getIdentifier().equals(memberName)) {
                    Expression expression = pair.getValue();
                    if (expression instanceof StringLiteral literal) {
                        return literal.getLiteralValue();
                    }
                }
            }
        }
        return null;
    }
}
