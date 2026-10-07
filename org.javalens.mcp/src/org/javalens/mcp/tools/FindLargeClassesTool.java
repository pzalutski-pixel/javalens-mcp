package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
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
 * Find types exceeding configurable member count or line count thresholds.
 * Useful for identifying classes that should be refactored into smaller units.
 */
public class FindLargeClassesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindLargeClassesTool.class);

    public FindLargeClassesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_large_classes";
    }

    @Override
    public String getDescription() {
        return """
            Find classes that exceed size thresholds.

            USAGE: find_large_classes(maxMethods=20, maxFields=10, maxLines=300)
            OUTPUT: List of classes exceeding any threshold with their metrics

            Default thresholds:
            - maxMethods: 20 methods
            - maxFields: 10 fields
            - maxLines: 300 lines

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("maxMethods", "integer", "Maximum methods before flagging (default 20)")
            .optional("maxFields", "integer", "Maximum fields before flagging (default 10)")
            .optional("maxLines", "integer", "Maximum lines before flagging (default 300)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int methodThreshold = getIntParam(arguments, "maxMethods", 20);
        int fieldThreshold = getIntParam(arguments, "maxFields", 10);
        int lineThreshold = getIntParam(arguments, "maxLines", 300);

        try {
            List<Path> allFiles = service.getAllJavaFiles();
            List<Map<String, Object>> largeClasses = new ArrayList<>();
            int totalClassesScanned = 0;

            for (Path filePath : allFiles) {
                ICompilationUnit cu = service.getCompilationUnit(filePath);
                if (cu == null) continue;

                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setResolveBindings(true);
                CompilationUnit ast = (CompilationUnit) parser.createAST(Deadline.monitor());

                for (Object type : ast.types()) {
                    if (type instanceof AbstractTypeDeclaration typeDecl) {
                        totalClassesScanned++;

                        int methodCount = 0;
                        int fieldCount = 0;
                        for (Object decl : typeDecl.bodyDeclarations()) {
                            if (decl instanceof MethodDeclaration) {
                                methodCount++;
                            } else if (decl instanceof FieldDeclaration) {
                                fieldCount++;
                            }
                        }
                        if (typeDecl instanceof RecordDeclaration record) {
                            fieldCount += record.recordComponents().size();
                        }

                        int startLine = ast.getLineNumber(typeDecl.getStartPosition());
                        int endLine = ast.getLineNumber(typeDecl.getStartPosition() + typeDecl.getLength());
                        if (startLine < 1 || endLine < 1) {
                            // A JEP 512 implicit class (ImplicitTypeDeclaration) is synthetic:
                            // its own source range does not map to real lines, so the span
                            // above is invalid. Fall back to the range of its body
                            // declarations so the line threshold still applies.
                            int minStart = Integer.MAX_VALUE;
                            int maxEnd = -1;
                            for (Object decl : typeDecl.bodyDeclarations()) {
                                if (decl instanceof ASTNode node) {
                                    minStart = Math.min(minStart, node.getStartPosition());
                                    maxEnd = Math.max(maxEnd, node.getStartPosition() + node.getLength());
                                }
                            }
                            if (maxEnd >= 0) {
                                startLine = ast.getLineNumber(minStart);
                                endLine = ast.getLineNumber(maxEnd);
                            }
                        }
                        int lineCount = (startLine >= 1 && endLine >= 1) ? endLine - startLine + 1 : 0;

                        // Implicit classes have no source-level name; the JDT model names
                        // them after the file, so match that for a stable, non-empty name.
                        String typeName = typeDecl.getName().getIdentifier();
                        if (typeName.isEmpty()) {
                            String fn = filePath.getFileName().toString();
                            typeName = fn.endsWith(".java") ? fn.substring(0, fn.length() - 5) : fn;
                        }

                        List<String> violations = new ArrayList<>();
                        if (methodCount > methodThreshold) violations.add("methods: " + methodCount + " > " + methodThreshold);
                        if (fieldCount > fieldThreshold) violations.add("fields: " + fieldCount + " > " + fieldThreshold);
                        if (lineCount > lineThreshold) violations.add("lines: " + lineCount + " > " + lineThreshold);

                        if (!violations.isEmpty()) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("file", service.getPathUtils().formatPath(filePath));
                            entry.put("typeName", typeName);
                            entry.put("methodCount", methodCount);
                            entry.put("fieldCount", fieldCount);
                            entry.put("lineCount", lineCount);
                            entry.put("violations", violations);
                            largeClasses.add(entry);
                        }
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalClassesScanned", totalClassesScanned);
            data.put("totalViolations", largeClasses.size());
            data.put("thresholds", Map.of("maxMethods", methodThreshold, "maxFields", fieldThreshold, "maxLines", lineThreshold));
            data.put("largeClasses", largeClasses);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(largeClasses.size())
                .returnedCount(largeClasses.size())
                .suggestedNextTools(List.of(
                    "get_complexity_metrics for detailed method-level metrics",
                    "analyze_type for full type analysis"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
