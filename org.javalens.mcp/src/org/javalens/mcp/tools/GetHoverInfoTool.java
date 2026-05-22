package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.javalens.core.ElementKindResolver;
import org.javalens.core.IJdtService;
import org.javalens.core.ModifierFormatter;
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
 * Get hover information (documentation) for a symbol at a position.
 */
public class GetHoverInfoTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetHoverInfoTool.class);

    public GetHoverInfoTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_hover_info";
    }

    @Override
    public String getDescription() {
        return """
            Get hover information (documentation) for a symbol at a position.

            USAGE: Position on any symbol
            OUTPUT: Signature, Javadoc, and quick info similar to IDE hover

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("line", "integer", "Zero-based line number")
            .required("column", "integer", "Zero-based column number")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required parameter missing");
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            Map<String, Object> data = createHoverInfo(element, service);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "go_to_definition to navigate to source",
                    "find_references to find all usages",
                    "get_javadoc for full documentation"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting hover info: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createHoverInfo(IJavaElement element, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("name", element.getElementName());
        info.put("kind", getElementKind(element));

        // Build signature based on element type
        String signature = buildSignature(element);
        if (signature != null) {
            info.put("signature", signature);
        }

        // Get Javadoc if available
        if (element instanceof IMember member) {
            try {
                String javadoc = member.getAttachedJavadoc(null);
                if (javadoc != null && !javadoc.isBlank()) {
                    // Clean up HTML from javadoc
                    String cleanDoc = cleanJavadoc(javadoc);
                    info.put("documentation", cleanDoc);
                }
            } catch (JavaModelException e) {
                // Javadoc not available
                log.debug("Javadoc not available for {}: {}", element.getElementName(), e.getMessage());
            }

            // Try to get doc comment from source
            ISourceRange javadocRange = member.getJavadocRange();
            if (javadocRange != null) {
                ICompilationUnit cu = member.getCompilationUnit();
                if (cu != null) {
                    String source = cu.getSource();
                    if (source != null && javadocRange.getOffset() >= 0) {
                        int end = javadocRange.getOffset() + javadocRange.getLength();
                        if (end <= source.length()) {
                            String docComment = source.substring(javadocRange.getOffset(), end);
                            info.put("docComment", cleanDocComment(docComment));
                        }
                    }
                }
            }

            // Modifiers
            int flags = member.getFlags();
            info.put("modifiers", ModifierFormatter.format(flags));

            // Declaring type
            IType declaringType = member.getDeclaringType();
            if (declaringType != null) {
                info.put("declaringType", declaringType.getFullyQualifiedName());
            }
        }

        // Source location
        ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu != null && cu.getResource() != null) {
            IPath location = cu.getResource().getLocation();
            if (location != null) {
                info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }
        }

        return info;
    }

    private String buildSignature(IJavaElement element) throws JavaModelException {
        if (element instanceof IType type) {
            StringBuilder sig = new StringBuilder();

            int flags = type.getFlags();
            if (Flags.isPublic(flags)) sig.append("public ");
            if (Flags.isAbstract(flags) && !type.isInterface()) sig.append("abstract ");
            if (Flags.isFinal(flags)) sig.append("final ");

            // Annotation types report isInterface()=true; check isAnnotation first so
            // we emit "@interface" rather than "interface".
            if (type.isAnnotation()) {
                sig.append("@interface ");
            } else if (type.isInterface()) {
                sig.append("interface ");
            } else if (type.isEnum()) {
                sig.append("enum ");
            } else if (type.isRecord()) {
                sig.append("record ");
            } else {
                sig.append("class ");
            }

            sig.append(type.getElementName());
            sig.append(formatTypeParameterClause(type.getTypeParameters()));

            String superclass = type.getSuperclassName();
            if (superclass != null && !superclass.equals("Object")) {
                sig.append(" extends ").append(superclass);
            }

            String[] interfaces = type.getSuperInterfaceNames();
            if (interfaces.length > 0) {
                sig.append(type.isInterface() ? " extends " : " implements ");
                sig.append(String.join(", ", interfaces));
            }

            return sig.toString();

        } else if (element instanceof IMethod method) {
            StringBuilder sig = new StringBuilder();

            int flags = method.getFlags();
            if (Flags.isPublic(flags)) sig.append("public ");
            if (Flags.isProtected(flags)) sig.append("protected ");
            if (Flags.isPrivate(flags)) sig.append("private ");
            if (Flags.isStatic(flags)) sig.append("static ");
            if (Flags.isAbstract(flags)) sig.append("abstract ");
            if (Flags.isFinal(flags)) sig.append("final ");

            String methodTypeParams = formatTypeParameterClause(method.getTypeParameters());
            if (!methodTypeParams.isEmpty()) {
                sig.append(methodTypeParams).append(" ");
            }

            if (!method.isConstructor()) {
                sig.append(Signature.getSimpleName(Signature.toString(method.getReturnType()))).append(" ");
            }

            sig.append(method.getElementName()).append("(");

            String[] paramTypes = method.getParameterTypes();
            String[] paramNames = method.getParameterNames();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(Signature.getSimpleName(Signature.toString(paramTypes[i])));
                if (i < paramNames.length) {
                    sig.append(" ").append(paramNames[i]);
                }
            }
            sig.append(")");

            String[] exceptions = method.getExceptionTypes();
            if (exceptions.length > 0) {
                sig.append(" throws ");
                for (int i = 0; i < exceptions.length; i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(Signature.getSimpleName(Signature.toString(exceptions[i])));
                }
            }

            return sig.toString();

        } else if (element instanceof IField field) {
            StringBuilder sig = new StringBuilder();

            int flags = field.getFlags();
            if (Flags.isPublic(flags)) sig.append("public ");
            if (Flags.isProtected(flags)) sig.append("protected ");
            if (Flags.isPrivate(flags)) sig.append("private ");
            if (Flags.isStatic(flags)) sig.append("static ");
            if (Flags.isFinal(flags)) sig.append("final ");

            sig.append(Signature.getSimpleName(Signature.toString(field.getTypeSignature())));
            sig.append(" ").append(field.getElementName());

            Object constant = field.getConstant();
            if (constant != null) {
                sig.append(" = ").append(constant);
            }

            return sig.toString();
        }

        return null;
    }

    /**
     * Formats a JDT type-parameter list (class- or method-level) as a
     * Java-source declaration clause like {@code <T extends Number, U>}.
     * Returns the empty string when there are no parameters.
     */
    private String formatTypeParameterClause(ITypeParameter[] params) throws JavaModelException {
        if (params == null || params.length == 0) return "";
        StringBuilder sb = new StringBuilder("<");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getElementName());
            String[] bounds = params[i].getBounds();
            if (bounds != null && bounds.length > 0) {
                sb.append(" extends ");
                for (int b = 0; b < bounds.length; b++) {
                    if (b > 0) sb.append(" & ");
                    sb.append(bounds[b]);
                }
            }
        }
        sb.append(">");
        return sb.toString();
    }

    private String cleanJavadoc(String javadoc) {
        if (javadoc == null) return null;

        // Remove HTML tags but preserve structure
        return javadoc
            .replaceAll("<[^>]+>", "")
            .replaceAll("&nbsp;", " ")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&amp;", "&")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String cleanDocComment(String docComment) {
        if (docComment == null) return null;

        // Remove /** */ and * prefixes
        return docComment
            .replaceAll("/\\*\\*", "")
            .replaceAll("\\*/", "")
            .replaceAll("(?m)^\\s*\\*\\s?", "")
            .trim();
    }

    private String getElementKind(IJavaElement element) {
        return ElementKindResolver.kindOf(element);
    }

}
