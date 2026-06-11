package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Compound diagnose-then-fix in one round-trip: run diagnostics for a file,
 * resolve the available quick fixes for each fixable problem, compute the
 * top fix's edits, and return everything together. STRICTLY read-only — the
 * caller applies the returned edits (single-writer model).
 */
public class DiagnoseAndFixTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(DiagnoseAndFixTool.class);

    private final GetDiagnosticsTool diagnosticsTool;
    private final GetQuickFixesTool quickFixesTool;
    private final ApplyQuickFixTool applyQuickFixTool;
    private final ObjectMapper mapper = new ObjectMapper();

    public DiagnoseAndFixTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.diagnosticsTool = new GetDiagnosticsTool(serviceSupplier);
        this.quickFixesTool = new GetQuickFixesTool(serviceSupplier);
        this.applyQuickFixTool = new ApplyQuickFixTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "diagnose_and_fix";
    }

    @Override
    public String getDescription() {
        return """
            Diagnose a file and compute the quick-fix edits in one call: runs
            diagnostics, resolves the available fixes per fixable problem, and
            returns the top fix's edits for each, combined as editsByFile.

            USAGE: diagnose_and_fix(filePath="path/to/File.java")
            OUTPUT: problems (each with its chosen fix when one exists) and
            editsByFile with the computed edits. NOTHING is written - apply the
            returned edits yourself.

            A file with no fixable diagnostics returns empty problems/edits.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }

        try {
            // 1) Diagnostics for the file.
            ObjectNode diagArgs = mapper.createObjectNode();
            diagArgs.put("filePath", filePath);
            ToolResponse diagResponse = diagnosticsTool.execute(diagArgs);
            if (!diagResponse.isSuccess()) {
                return diagResponse;
            }
            Map<String, Object> diagData = (Map<String, Object>) diagResponse.getData();
            List<Map<String, Object>> diagnostics =
                (List<Map<String, Object>>) diagData.get("diagnostics");

            List<Map<String, Object>> problems = new ArrayList<>();
            Map<String, List<Map<String, Object>>> editsByFile = new LinkedHashMap<>();
            String formattedPath = String.valueOf(diagData.get("filesChecked")) != null
                ? filePath : filePath;

            // 2) Per diagnostic line, resolve the available fixes once.
            Set<Integer> seenLines = new LinkedHashSet<>();
            for (Map<String, Object> diagnostic : diagnostics) {
                int line = ((Number) diagnostic.get("line")).intValue();

                Map<String, Object> problem = new LinkedHashMap<>();
                problem.put("message", diagnostic.get("message"));
                problem.put("severity", diagnostic.get("severity"));
                problem.put("line", line);

                if (seenLines.add(line)) {
                    ObjectNode fixArgs = mapper.createObjectNode();
                    fixArgs.put("filePath", filePath);
                    fixArgs.put("line", line);
                    ToolResponse fixResponse = quickFixesTool.execute(fixArgs);
                    if (fixResponse.isSuccess()) {
                        Map<String, Object> fixData = (Map<String, Object>) fixResponse.getData();
                        List<Map<String, Object>> fixes =
                            (List<Map<String, Object>>) fixData.get("fixes");
                        if (fixes != null && !fixes.isEmpty()) {
                            // Fixes arrive relevance-sorted; take the top one and
                            // compute its edits through the existing apply logic
                            // (which returns edits — it never writes).
                            Map<String, Object> topFix = fixes.get(0);
                            String fixId = (String) topFix.get("fixId");
                            problem.put("fixId", fixId);
                            problem.put("fixLabel", topFix.get("label"));

                            ObjectNode applyArgs = mapper.createObjectNode();
                            applyArgs.put("filePath", filePath);
                            applyArgs.put("fixId", fixId);
                            applyArgs.put("line", line);
                            ToolResponse applyResponse = applyQuickFixTool.execute(applyArgs);
                            if (applyResponse.isSuccess()) {
                                Map<String, Object> applyData =
                                    (Map<String, Object>) applyResponse.getData();
                                List<Map<String, Object>> edits =
                                    (List<Map<String, Object>>) applyData.get("edits");
                                String editPath = String.valueOf(applyData.get("filePath"));
                                editsByFile.computeIfAbsent(editPath, k -> new ArrayList<>())
                                    .addAll(edits);
                            }
                        }
                    }
                }
                problems.add(problem);
            }

            int totalEdits = editsByFile.values().stream().mapToInt(List::size).sum();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", formattedPath);
            data.put("problemCount", problems.size());
            data.put("problems", problems);
            data.put("totalEdits", totalEdits);
            data.put("editsByFile", editsByFile);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(problems.size())
                .returnedCount(problems.size())
                .suggestedNextTools(totalEdits > 0
                    ? List.of(service.getDiskSyncMode() == org.javalens.core.sync.DiskSyncMode.MANUAL
                            ? "Apply the edits, then load_project to refresh"
                            : "Apply the edits - the next query verifies against disk automatically",
                        "get_diagnostics to verify the problems are gone")
                    : List.of("No fixable problems - nothing to apply"))
                .build());

        } catch (Exception e) {
            log.error("Error in diagnose_and_fix: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
