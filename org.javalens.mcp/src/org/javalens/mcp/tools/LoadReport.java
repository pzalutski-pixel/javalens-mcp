package org.javalens.mcp.tools;

import org.javalens.core.IJdtService;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fidelity report of a loaded project: the {@code buildModel} summary and the load's
 * {@code warnings}. {@link LoadProjectTool} and {@link HealthCheckTool} both report it
 * through here, so an agent sees the same report from either tool, including for a project
 * auto-loaded through {@code JAVA_PROJECT_PATH}.
 */
final class LoadReport {

    /** Modules listed in {@code buildModel.modules}; {@code moduleCount} gives the total. */
    private static final int MODULE_LIMIT = 20;

    private LoadReport() {
    }

    /**
     * Put {@code buildModel} into {@code target}, then {@code warnings} when the load
     * produced any. A clean load carries no {@code warnings} field.
     *
     * @param service a service that has loaded a project
     */
    static void putInto(Map<String, Object> target, IJdtService service) {
        target.put("buildModel", buildModel(service));
        List<LoadWarning> warnings = service.getWarnings();
        if (!warnings.isEmpty()) {
            target.put("warnings", warnings(warnings));
        }
    }

    /**
     * The build system, where the structure came from, the level the project is analyzed
     * at, and the first {@value #MODULE_LIMIT} modules in model order.
     */
    private static Map<String, Object> buildModel(IJdtService service) {
        BuildModel model = service.getBuildModel();
        List<BuildModule> modules = model.modules();

        List<Map<String, Object>> listed = new ArrayList<>();
        for (BuildModule module : modules.subList(0, Math.min(MODULE_LIMIT, modules.size()))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", module.id());
            entry.put("dir", dir(service, module));
            listed.add(entry);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("buildSystem", model.buildSystem().name().toLowerCase(Locale.ROOT));
        summary.put("source", source(model.provenance()));
        summary.put("analysisLevel", service.getAnalysisLevel());
        summary.put("moduleCount", modules.size());
        summary.put("modules", listed);
        return summary;
    }

    /** The module directory formatted like every other reported path; the project root is ".". */
    private static String dir(IJdtService service, BuildModule module) {
        String dir = service.getPathUtils().formatPath(module.dir());
        return dir.isEmpty() ? "." : dir;
    }

    private static String source(Provenance provenance) {
        return switch (provenance) {
            case BUILD_TOOL -> "build-tool";
            case STATIC_READ -> "static-read";
            case CONVENTION -> "convention";
        };
    }

    /** Each warning's code and message, plus its remediation and module when set. */
    private static List<Map<String, Object>> warnings(List<LoadWarning> warnings) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LoadWarning warning : warnings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", warning.code());
            entry.put("message", warning.message());
            if (warning.remediation() != null) entry.put("remediation", warning.remediation());
            if (warning.module() != null) entry.put("module", warning.module());
            entries.add(entry);
        }
        return entries;
    }
}
