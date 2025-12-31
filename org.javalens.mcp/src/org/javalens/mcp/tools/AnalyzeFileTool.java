package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
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
 * Comprehensive file analysis in a single call.
 * Combines file info, imports, types, and diagnostics.
 */
public class AnalyzeFileTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeFileTool.class);

    public AnalyzeFileTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_file";
    }

    @Override
    public String getDescription() {
        return """
            Comprehensive file analysis in a single call.

            Combines:
            - File info (path, package, line count)
            - All imports (with static/on-demand flags)
            - All types with member counts
            - Compilation diagnostics (errors/warnings)

            Use this instead of multiple calls to get_document_symbols + get_diagnostics.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of(
            "type", "string",
            "description", "Path to source file"
        ));
        properties.put("includeMembers", Map.of(
            "type", "boolean",
            "description", "Include full member details for each type (default false)"
        ));
        properties.put("includeDiagnostics", Map.of(
            "type", "boolean",
            "description", "Include compilation errors/warnings (default true)"
        ));

        schema.put("properties", properties);
        schema.put("required", List.of("filePath"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required parameter missing");
        }

        boolean includeMembers = getBooleanParam(arguments, "includeMembers", false);
        boolean includeDiagnostics = getBooleanParam(arguments, "includeDiagnostics", true);

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);

            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            Map<String, Object> data = new LinkedHashMap<>();

            // File info
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("path", service.getPathUtils().formatPath(filePath));

            // Package
            IPackageDeclaration[] pkgDecls = cu.getPackageDeclarations();
            if (pkgDecls.length > 0) {
                fileInfo.put("package", pkgDecls[0].getElementName());
            } else {
                fileInfo.put("package", "(default package)");
            }

            // Line count
            String source = cu.getSource();
            if (source != null) {
                int lineCount = source.split("\n").length;
                fileInfo.put("lineCount", lineCount);
            }

            data.put("file", fileInfo);

            // Imports
            List<Map<String, Object>> imports = new ArrayList<>();
            for (IImportDeclaration imp : cu.getImports()) {
                Map<String, Object> importInfo = new LinkedHashMap<>();
                importInfo.put("name", imp.getElementName());
                importInfo.put("static", Flags.isStatic(imp.getFlags()));
                importInfo.put("onDemand", imp.isOnDemand());
                imports.add(importInfo);
            }
            data.put("imports", imports);
            data.put("importCount", imports.size());

            // Types
            List<Map<String, Object>> types = new ArrayList<>();
            for (IType type : cu.getTypes()) {
                Map<String, Object> typeInfo = createTypeInfo(type, service, cu, includeMembers);
                if (typeInfo != null) {
                    types.add(typeInfo);
                }
            }
            data.put("types", types);
            data.put("typeCount", types.size());

            // Diagnostics
            if (includeDiagnostics) {
                Map<String, Object> diagnosticsInfo = collectDiagnostics(cu, service);
                data.put("diagnostics", diagnosticsInfo);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "analyze_type for detailed type analysis",
                    "analyze_method for detailed method analysis",
                    "find_references to find usages"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error analyzing file: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createTypeInfo(IType type, IJdtService service, ICompilationUnit cu,
                                                boolean includeMembers) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", type.getElementName());
        info.put("qualifiedName", type.getFullyQualifiedName());

        // Kind
        if (type.isInterface()) {
            info.put("kind", "Interface");
        } else if (type.isEnum()) {
            info.put("kind", "Enum");
        } else if (type.isAnnotation()) {
            info.put("kind", "Annotation");
        } else if (type.isRecord()) {
            info.put("kind", "Record");
        } else {
            info.put("kind", "Class");
        }

        // Modifiers
        info.put("modifiers", getModifiers(type.getFlags()));

        // Location
        int offset = type.getSourceRange().getOffset();
        info.put("line", service.getLineNumber(cu, offset));

        // Member counts
        IMethod[] methods = type.getMethods();
        IField[] fields = type.getFields();
        IType[] nestedTypes = type.getTypes();

        info.put("methodCount", methods.length);
        info.put("fieldCount", fields.length);
        info.put("nestedTypeCount", nestedTypes.length);

        // Include full members if requested
        if (includeMembers) {
            List<Map<String, Object>> methodList = new ArrayList<>();
            for (IMethod method : methods) {
                methodList.add(createMethodInfo(method, service, cu));
            }
            info.put("methods", methodList);

            List<Map<String, Object>> fieldList = new ArrayList<>();
            for (IField field : fields) {
                fieldList.add(createFieldInfo(field, service, cu));
            }
            info.put("fields", fieldList);
        }

        return info;
    }

    private Map<String, Object> createMethodInfo(IMethod method, IJdtService service,
                                                   ICompilationUnit cu) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", method.getElementName());
        info.put("constructor", method.isConstructor());
        info.put("modifiers", getModifiers(method.getFlags()));

        // Signature
        StringBuilder sig = new StringBuilder();
        sig.append(method.getElementName()).append("(");
        String[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.getSimpleName(Signature.toString(paramTypes[i])));
        }
        sig.append(")");
        if (!method.isConstructor()) {
            sig.append(": ").append(Signature.getSimpleName(Signature.toString(method.getReturnType())));
        }
        info.put("signature", sig.toString());

        info.put("line", service.getLineNumber(cu, method.getSourceRange().getOffset()));
        return info;
    }

    private Map<String, Object> createFieldInfo(IField field, IJdtService service,
                                                  ICompilationUnit cu) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", field.getElementName());
        info.put("type", Signature.getSimpleName(Signature.toString(field.getTypeSignature())));
        info.put("modifiers", getModifiers(field.getFlags()));
        info.put("line", service.getLineNumber(cu, field.getSourceRange().getOffset()));
        return info;
    }

    private Map<String, Object> collectDiagnostics(ICompilationUnit cu, IJdtService service) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();

        try {
            // Reconcile to get problems
            CompilationUnit ast = (CompilationUnit) cu.reconcile(
                AST.getJLSLatest(),
                ICompilationUnit.FORCE_PROBLEM_DETECTION,
                null,
                null
            );

            if (ast != null) {
                for (IProblem problem : ast.getProblems()) {
                    Map<String, Object> diag = new LinkedHashMap<>();
                    diag.put("message", problem.getMessage());
                    diag.put("line", problem.getSourceLineNumber() - 1); // Convert to 0-based
                    diag.put("column", problem.getSourceStart());

                    if (problem.isError()) {
                        errors.add(diag);
                    } else if (problem.isWarning()) {
                        warnings.add(diag);
                    }
                }
            }
        } catch (JavaModelException e) {
            log.debug("Error collecting diagnostics: {}", e.getMessage());
        }

        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("errorCount", errors.size());
        result.put("warningCount", warnings.size());
        result.put("hasProblems", !errors.isEmpty() || !warnings.isEmpty());

        return result;
    }

    private List<String> getModifiers(int flags) {
        List<String> modifiers = new ArrayList<>();
        if (Flags.isPublic(flags)) modifiers.add("public");
        if (Flags.isProtected(flags)) modifiers.add("protected");
        if (Flags.isPrivate(flags)) modifiers.add("private");
        if (Flags.isStatic(flags)) modifiers.add("static");
        if (Flags.isFinal(flags)) modifiers.add("final");
        if (Flags.isAbstract(flags)) modifiers.add("abstract");
        return modifiers;
    }
}
