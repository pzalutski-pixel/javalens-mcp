package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
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
import java.util.regex.Pattern;

/**
 * Check code against standard Java naming conventions.
 * Reports violations for classes, methods, fields, constants, and parameters.
 */
public class FindNamingViolationsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindNamingViolationsTool.class);

    private static final Pattern PASCAL_CASE = Pattern.compile("^[A-Z][a-zA-Z0-9]*$");
    private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    private static final Pattern UPPER_SNAKE_CASE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public FindNamingViolationsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_naming_violations";
    }

    @Override
    public String getDescription() {
        return """
            Check code against standard Java naming conventions.

            USAGE: find_naming_violations(filePath="path/to/File.java")
            OUTPUT: List of naming convention violations

            Conventions checked:
            - Classes/interfaces/enums: PascalCase
            - Methods: camelCase
            - Fields: camelCase
            - Constants (static final): UPPER_SNAKE_CASE
            - Parameters: camelCase

            If filePath is omitted, scans all project files.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("filePath", "string", "File to check (omit to scan all files)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");

        try {
            List<Path> files;
            if (filePathStr != null && !filePathStr.isBlank()) {
                Path resolved = service.getPathUtils().resolve(filePathStr);
                files = List.of(resolved);
            } else {
                files = service.getAllJavaFiles();
            }

            List<Map<String, Object>> violations = new ArrayList<>();

            for (Path file : files) {
                ICompilationUnit cu = service.getCompilationUnit(file);
                if (cu == null) continue;

                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setResolveBindings(true);
                CompilationUnit ast = (CompilationUnit) parser.createAST(null);

                String formattedPath = service.getPathUtils().formatPath(file);

                ast.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(TypeDeclaration node) {
                        checkName(node.getName().getIdentifier(), "class", PASCAL_CASE, "PascalCase",
                            ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        return true;
                    }

                    @Override
                    public boolean visit(EnumDeclaration node) {
                        checkName(node.getName().getIdentifier(), "enum", PASCAL_CASE, "PascalCase",
                            ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        return true;
                    }

                    @Override
                    public boolean visit(RecordDeclaration node) {
                        checkName(node.getName().getIdentifier(), "record", PASCAL_CASE, "PascalCase",
                            ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        return true;
                    }

                    @Override
                    public boolean visit(AnnotationTypeDeclaration node) {
                        checkName(node.getName().getIdentifier(), "annotation", PASCAL_CASE, "PascalCase",
                            ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        return true;
                    }

                    @Override
                    public boolean visit(MethodDeclaration node) {
                        if (!node.isConstructor()) {
                            checkName(node.getName().getIdentifier(), "method", CAMEL_CASE, "camelCase",
                                ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(FieldDeclaration node) {
                        int modifiers = node.getModifiers();
                        boolean isConstant = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers);

                        for (Object fragment : node.fragments()) {
                            if (fragment instanceof VariableDeclarationFragment varFrag) {
                                String name = varFrag.getName().getIdentifier();
                                if (isConstant) {
                                    checkName(name, "constant", UPPER_SNAKE_CASE, "UPPER_SNAKE_CASE",
                                        ast.getLineNumber(varFrag.getStartPosition()) - 1, formattedPath, violations);
                                } else {
                                    checkName(name, "field", CAMEL_CASE, "camelCase",
                                        ast.getLineNumber(varFrag.getStartPosition()) - 1, formattedPath, violations);
                                }
                            }
                        }
                        return false;
                    }

                    @Override
                    public boolean visit(SingleVariableDeclaration node) {
                        checkName(node.getName().getIdentifier(), "parameter", CAMEL_CASE, "camelCase",
                            ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        return false;
                    }
                });
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filesScanned", files.size());
            data.put("totalViolations", violations.size());
            data.put("violations", violations);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(violations.size())
                .returnedCount(violations.size())
                .suggestedNextTools(List.of(
                    "rename_symbol to fix a naming violation"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private void checkName(String name, String elementType, Pattern convention, String conventionName,
                          int line, String filePath, List<Map<String, Object>> violations) {
        if (!convention.matcher(name).matches()) {
            Map<String, Object> violation = new LinkedHashMap<>();
            violation.put("file", filePath);
            violation.put("line", line);
            violation.put("elementType", elementType);
            violation.put("name", name);
            violation.put("convention", conventionName);
            violations.add(violation);
        }
    }
}
