package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.javalens.core.IJdtService;
import org.javalens.mcp.cleanup.CleanUpInvoker;
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
 * Apply one of JDT's own code clean-ups to a file and return the rewritten source.
 *
 * Drives the IDE-independent clean-up operations from org.eclipse.jdt.core.manipulation
 * rather than hand-rolling each transformation. The file is never written: the
 * rewritten source is returned for the caller to apply (single-writer invariant).
 */
public class ApplyCleanupTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ApplyCleanupTool.class);

    public ApplyCleanupTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "apply_cleanup";
    }

    @Override
    public String getDescription() {
        StringBuilder catalog = new StringBuilder();
        CleanUpInvoker.catalog().forEach((id, description) ->
            catalog.append("            - ").append(id).append(": ").append(description).append('\n'));
        return """
            Apply a JDT code clean-up to a file and return the rewritten source.

            USAGE: apply_cleanup(filePath="path/to/File.java", cleanupId="convert_loops")
            OUTPUT: changed flag, a label, and the full rewritten source (the file is
            NOT written — apply the returned source yourself).

            Supported cleanupId values:
            """ + catalog + """

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("cleanupId", "string", "Clean-up to apply (e.g. 'convert_loops')")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        String cleanupId = getStringParam(arguments, "cleanupId");

        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }
        if (cleanupId == null || cleanupId.isBlank()) {
            return ToolResponse.invalidParameter("cleanupId",
                "Required. Supported: " + CleanUpInvoker.supportedCleanUps());
        }
        if (!CleanUpInvoker.supportedCleanUps().contains(cleanupId)) {
            return ToolResponse.invalidParameter("cleanupId",
                "Unknown clean-up '" + cleanupId + "'. Supported: " + CleanUpInvoker.supportedCleanUps());
        }

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            CleanUpInvoker.CleanUpResult result = CleanUpInvoker.apply(cu, cleanupId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("cleanupId", cleanupId);
            data.put("changed", result.changed());
            if (result.label() != null) {
                data.put("label", result.label());
            }
            data.put("source", result.source());

            List<String> next = new ArrayList<>();
            next.add(result.changed()
                ? "Apply the returned source to the file"
                : "Nothing to clean up for this id");
            next.add("get_diagnostics to verify no new problems");

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(next)
                .build());

        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("cleanupId", e.getMessage());
        } catch (Exception e) {
            log.error("Error applying clean-up '{}': {}", cleanupId, e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
