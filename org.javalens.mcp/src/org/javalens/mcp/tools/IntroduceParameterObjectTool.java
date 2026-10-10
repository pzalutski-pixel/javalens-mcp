package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.refactoring.descriptors.IntroduceParameterObjectDescriptor;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.rewrite.RefactoringInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bundle a method's parameters into a parameter object: a new class holding
 * them, with the method and its callers rewritten to use it. Drives JDT's
 * introduce-parameter-object refactoring via its public descriptor; edits are
 * returned as text, never applied.
 */
public class IntroduceParameterObjectTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(IntroduceParameterObjectTool.class);

    public IntroduceParameterObjectTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "introduce_parameter_object";
    }

    @Override
    public String getDescription() {
        return """
            Bundle a method's parameters into a new parameter-object class and
            rewrite the method and all callers to use it. The class is generated
            as a member of the declaring type.

            USAGE: Position on the method name; optionally name the class and parameter.
            OUTPUT: editsByFile with all required edits; warnings from JDT's
            condition checking, by the place each names (file, then position;
            those naming one place in JDT's order, those naming none last). Edits
            are returned as text - apply them yourself.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file containing the method")
            .required("line", "integer", "Zero-based line number of the method declaration")
            .required("column", "integer", "Zero-based column number (on the method name)")
            .optional("className", "string", "Name for the parameter-object class (default: <MethodName>Parameters)")
            .optional("parameterName", "string", "Name for the new parameter (default: parameterObject)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return ToolResponse.invalidParameter("line/column", "Must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);
            if (!(element instanceof IMethod method)) {
                return ToolResponse.invalidParameter("position", "No method at position");
            }
            if (method.getNumberOfParameters() == 0) {
                return ToolResponse.invalidParameter("method",
                    "Method has no parameters to bundle");
            }

            String methodName = method.getElementName();
            String defaultClassName = Character.toUpperCase(methodName.charAt(0))
                + (methodName.length() > 1 ? methodName.substring(1) : "") + "Parameters";
            String className = getStringParam(arguments, "className", defaultClassName);
            String parameterName = getStringParam(arguments, "parameterName", "parameterObject");

            IntroduceParameterObjectDescriptor descriptor = new IntroduceParameterObjectDescriptor();
            descriptor.setProject(method.getJavaProject().getElementName());
            descriptor.setMethod(method);
            descriptor.setClassName(className);
            descriptor.setParameterName(parameterName);
            descriptor.setParameters(IntroduceParameterObjectDescriptor.createParameters(method));
            descriptor.setTopLevel(false);
            descriptor.setGetters(true);
            descriptor.setSetters(false);
            descriptor.setDelegate(false);
            descriptor.setDeprecateDelegate(false);

            RefactoringInvoker.Outcome outcome = RefactoringInvoker.run(descriptor, service);
            if (outcome.refused()) {
                return ToolResponse.invalidParameter("method",
                    String.join("; ", outcome.reasons()));
            }

            int totalEdits = outcome.editsByFile().values().stream().mapToInt(List::size).sum();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("methodName", methodName);
            data.put("className", className);
            data.put("parameterName", parameterName);
            data.put("createdFiles", outcome.createdFiles());
            data.put("totalEdits", totalEdits);
            data.put("filesAffected", outcome.editsByFile().size());
            data.put("editsByFile", outcome.editsByFile());
            if (!outcome.warnings().isEmpty()) {
                data.put("warnings", outcome.warnings());
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalEdits)
                .returnedCount(totalEdits)
                .suggestedNextTools(List.of(
                    "Apply the text edits to complete the refactoring",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error introducing parameter object: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
