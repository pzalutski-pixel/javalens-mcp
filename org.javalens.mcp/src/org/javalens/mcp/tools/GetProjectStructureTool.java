package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Get project structure showing package hierarchy and file counts.
 */
public class GetProjectStructureTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetProjectStructureTool.class);

    /** The largest {@code maxDepth} accepted. */
    private static final int MAX_DEPTH = 20;

    public GetProjectStructureTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_project_structure";
    }

    @Override
    public String getDescription() {
        return """
            Get project structure showing package hierarchy.

            USAGE: Call to see the package tree of the loaded project
            OUTPUT: Source roots with packages and file counts

            maxDepth outside 1 to 20 is INVALID_PARAMETER.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("includeFiles", "boolean", "Include file names in each package (default false)")
            .optional("maxDepth", "integer", "Maximum package depth to show, 1 to 20 (default 10)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        boolean includeFiles = getBooleanParam(arguments, "includeFiles", false);
        int maxDepth = getIntParam(arguments, "maxDepth", 10);
        if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
            return ToolResponse.invalidParameter("maxDepth",
                "Must be between 1 and " + MAX_DEPTH + "; got: " + maxDepth);
        }

        try {
            IJavaProject project = service.getJavaProject();

            List<Map<String, Object>> sourceRoots = new ArrayList<>();
            int totalPackages = 0;
            int totalFiles = 0;

            for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
                if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                    continue;
                }

                Map<String, Object> rootInfo = new LinkedHashMap<>();
                rootInfo.put("path", root.getPath().toString());

                List<Map<String, Object>> packages = new ArrayList<>();

                for (IJavaElement child : root.getChildren()) {
                    if (child instanceof IPackageFragment pkg) {
                        if (pkg.getCompilationUnits().length == 0 && !pkg.hasSubpackages()) {
                            continue;
                        }

                        Map<String, Object> pkgInfo = createPackageInfo(pkg, includeFiles, maxDepth);
                        if (pkgInfo != null) {
                            packages.add(pkgInfo);
                            totalPackages++;
                            totalFiles += pkg.getCompilationUnits().length;
                        }
                    }
                }

                rootInfo.put("packages", packages);
                rootInfo.put("packageCount", packages.size());
                sourceRoots.add(rootInfo);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("projectName", project.getElementName());
            data.put("projectRoot", service.getProjectRoot().toString());
            data.put("sourceRoots", sourceRoots);
            data.put("totalPackages", totalPackages);
            data.put("totalFiles", totalFiles);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalPackages)
                .returnedCount(totalPackages)
                .suggestedNextTools(List.of(
                    "search_symbols to find types",
                    "get_document_symbols to explore a file",
                    "get_classpath_info for dependencies"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting project structure: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /** The package's entry; null for a package deeper than {@code maxDepth}, which is not shown. */
    private Map<String, Object> createPackageInfo(IPackageFragment pkg, boolean includeFiles, int maxDepth)
            throws JavaModelException {
        String name = pkg.getElementName();
        if (name.isEmpty()) {
            name = "(default package)";
        }

        int depth = name.split("\\.").length;
        if (depth > maxDepth) {
            return null;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);

        ICompilationUnit[] units = pkg.getCompilationUnits();
        info.put("fileCount", units.length);

        if (includeFiles && units.length > 0) {
            List<String> files = new ArrayList<>();
            for (ICompilationUnit unit : units) {
                files.add(unit.getElementName());
            }
            info.put("files", files);
        }

        return info;
    }
}
