package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IModuleDescription;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstall2;
import org.eclipse.jdt.launching.JavaRuntime;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Get project classpath information.
 * Returns source folders, libraries, and classpath containers.
 */
public class GetClasspathInfoTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetClasspathInfoTool.class);

    public GetClasspathInfoTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_classpath_info";
    }

    @Override
    public String getDescription() {
        return """
            Get project classpath information.

            USAGE: Call to get all classpath entries for the loaded project
            OUTPUT: Source folders, libraries, and classpath containers

            Useful for understanding project structure and dependencies.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("includeLibraries", "boolean", "Include library entries (default true)")
            .optional("includeSource", "boolean", "Include source folder entries (default true)")
            .optional("includeContainers", "boolean", "Include container entries like JRE (default true)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        boolean includeLibraries = getBooleanParam(arguments, "includeLibraries", true);
        boolean includeSource = getBooleanParam(arguments, "includeSource", true);
        boolean includeContainers = getBooleanParam(arguments, "includeContainers", true);

        try {
            IJavaProject project = service.getJavaProject();
            IClasspathEntry[] entries = project.getRawClasspath();

            List<Map<String, Object>> sourceFolders = new ArrayList<>();
            List<Map<String, Object>> libraries = new ArrayList<>();
            List<Map<String, Object>> containers = new ArrayList<>();
            List<Map<String, Object>> projects = new ArrayList<>();
            List<Map<String, Object>> variables = new ArrayList<>();

            for (IClasspathEntry entry : entries) {
                Map<String, Object> entryInfo = createEntryInfo(entry, service);

                switch (entry.getEntryKind()) {
                    case IClasspathEntry.CPE_SOURCE:
                        if (includeSource) {
                            sourceFolders.add(entryInfo);
                        }
                        break;
                    case IClasspathEntry.CPE_LIBRARY:
                        if (includeLibraries) {
                            libraries.add(entryInfo);
                        }
                        break;
                    case IClasspathEntry.CPE_CONTAINER:
                        if (includeContainers) {
                            containers.add(entryInfo);
                        }
                        break;
                    case IClasspathEntry.CPE_PROJECT:
                        projects.add(entryInfo);
                        break;
                    case IClasspathEntry.CPE_VARIABLE:
                        variables.add(entryInfo);
                        break;
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("projectName", project.getElementName());
            data.put("projectRoot", service.getProjectRoot().toString());

            if (includeSource) {
                data.put("sourceFolders", sourceFolders);
            }
            if (includeLibraries) {
                data.put("libraries", libraries);
            }
            if (includeContainers) {
                data.put("containers", containers);
            }
            if (!projects.isEmpty()) {
                data.put("projectDependencies", projects);
            }
            if (!variables.isEmpty()) {
                data.put("variables", variables);
            }

            // Output folder
            try {
                data.put("outputLocation", project.getOutputLocation().toString());
            } catch (JavaModelException e) {
                log.debug("Could not get output location: {}", e.getMessage());
            }

            int totalEntries = sourceFolders.size() + libraries.size() + containers.size() +
                              projects.size() + variables.size();
            data.put("totalEntries", totalEntries);

            // Resolved classpath — containers expanded, per-entry existence check.
            // This is what the analyzer actually sees, not what was configured. The
            // raw classpath shows JRE_CONTAINER as one opaque entry; resolved expands
            // it into the individual system-module entries JDT will read class files
            // from. Stale paths (jars in the classpath that no longer exist on disk)
            // show up here with exists=false, which is otherwise invisible.
            data.put("resolved", buildResolvedSection(project));

            // JRE that JDT selected for this project — install location, name,
            // and the list of system modules visible to the project. Surfaces the
            // exact JDK driving analysis (which can diverge from the user-expected
            // JDK in npm-launched runtimes, IDE-bundled JVMs, or container images)
            // and lets a consumer confirm that bootstrap modules like java.base
            // are reachable.
            Map<String, Object> jreInfo = buildJreSection(project);
            if (jreInfo != null) {
                data.put("jre", jreInfo);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(entries.length)
                .returnedCount(totalEntries)
                .suggestedNextTools(List.of(
                    "get_project_structure to see package hierarchy",
                    "search_symbols to find types in the project",
                    "get_document_symbols to explore a source file"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting classpath info: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createEntryInfo(IClasspathEntry entry, IJdtService service) {
        Map<String, Object> info = new LinkedHashMap<>();

        String path = entry.getPath().toString();
        info.put("path", path);

        String kind = switch (entry.getEntryKind()) {
            case IClasspathEntry.CPE_SOURCE -> "source";
            case IClasspathEntry.CPE_LIBRARY -> "library";
            case IClasspathEntry.CPE_CONTAINER -> "container";
            case IClasspathEntry.CPE_PROJECT -> "project";
            case IClasspathEntry.CPE_VARIABLE -> "variable";
            default -> "unknown";
        };
        info.put("kind", kind);

        // Add additional info for different types
        if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
            // Output location for source folder
            if (entry.getOutputLocation() != null) {
                info.put("outputLocation", entry.getOutputLocation().toString());
            }

            // Exclusion patterns
            if (entry.getExclusionPatterns().length > 0) {
                List<String> exclusions = new ArrayList<>();
                for (var pattern : entry.getExclusionPatterns()) {
                    exclusions.add(pattern.toString());
                }
                info.put("exclusions", exclusions);
            }

            // Inclusion patterns
            if (entry.getInclusionPatterns().length > 0) {
                List<String> inclusions = new ArrayList<>();
                for (var pattern : entry.getInclusionPatterns()) {
                    inclusions.add(pattern.toString());
                }
                info.put("inclusions", inclusions);
            }
        }

        if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
            // Source attachment
            if (entry.getSourceAttachmentPath() != null) {
                info.put("sourceAttachment", entry.getSourceAttachmentPath().toString());
            }
        }

        // Export flag
        if (entry.isExported()) {
            info.put("exported", true);
        }

        return info;
    }

    /**
     * Build the resolved-classpath section. Walks {@link IJavaProject#getResolvedClasspath}
     * (containers expanded, variables resolved) and emits per-entry kind + path + a
     * boolean {@code exists} that probes the file/directory on disk. Library entries
     * with an explicit {@code IClasspathAttribute.MODULE} also surface that value so
     * consumers can tell modulepath placement from classpath placement.
     */
    private List<Map<String, Object>> buildResolvedSection(IJavaProject project) {
        List<Map<String, Object>> resolved = new ArrayList<>();
        try {
            IClasspathEntry[] entries = project.getResolvedClasspath(true);
            for (IClasspathEntry entry : entries) {
                Map<String, Object> info = new LinkedHashMap<>();
                String kind = switch (entry.getEntryKind()) {
                    case IClasspathEntry.CPE_SOURCE -> "source";
                    case IClasspathEntry.CPE_LIBRARY -> "library";
                    case IClasspathEntry.CPE_CONTAINER -> "container";
                    case IClasspathEntry.CPE_PROJECT -> "project";
                    case IClasspathEntry.CPE_VARIABLE -> "variable";
                    default -> "unknown";
                };
                info.put("kind", kind);
                String path = entry.getPath().toString();
                info.put("path", path);
                info.put("exists", entryExists(entry));

                // Surface the MODULE classpath attribute when explicitly set — the
                // value controls whether the entry is treated as modulepath (named
                // or automatic module) vs classpath (unnamed module). Absent means
                // JDT's default (classpath/unnamed for libraries).
                String moduleAttr = readModuleAttribute(entry);
                if (moduleAttr != null) info.put("module", moduleAttr);

                resolved.add(info);
            }
        } catch (JavaModelException e) {
            log.warn("Failed to resolve classpath: {}", e.getMessage());
        }
        return resolved;
    }

    /**
     * Per-entry existence check. The right answer depends on the entry kind:
     * source / project entries carry workspace-relative paths and must be checked
     * via the Eclipse resource model; library / variable entries carry filesystem
     * paths (or the {@code jrt:} virtual scheme for JDK system modules); container
     * entries should not appear in the resolved classpath since containers expand
     * before resolution. A single {@code Files.exists(path)} on the raw string is
     * wrong for source entries because the linked-folder workspace path has no
     * filesystem analog.
     */
    private boolean entryExists(IClasspathEntry entry) {
        String path = entry.getPath().toString();
        if (path == null || path.isBlank()) return false;

        switch (entry.getEntryKind()) {
            case IClasspathEntry.CPE_SOURCE:
            case IClasspathEntry.CPE_PROJECT:
                IResource resource = ResourcesPlugin.getWorkspace().getRoot()
                    .findMember(new Path(path));
                return resource != null && resource.exists();
            case IClasspathEntry.CPE_LIBRARY:
            case IClasspathEntry.CPE_VARIABLE:
            default:
                if (path.startsWith("jrt:") || path.startsWith("jrt-fs:")) return true;
                try {
                    return Files.exists(new File(path).toPath());
                } catch (Exception e) {
                    return false;
                }
        }
    }

    private String readModuleAttribute(IClasspathEntry entry) {
        IClasspathAttribute[] attrs = entry.getExtraAttributes();
        if (attrs == null) return null;
        for (IClasspathAttribute attr : attrs) {
            if (IClasspathAttribute.MODULE.equals(attr.getName())) {
                return attr.getValue();
            }
        }
        return null;
    }

    /**
     * Build the {@code jre} section. Resolves the JDT JRE container, extracts the
     * {@link IVMInstall} JDT selected, and reports the install location, name, and
     * the list of system modules visible to the project (java.base, java.logging, …).
     * Returns null if no JRE container is on the raw classpath — projects without a
     * JRE container don't get a jre section in the response.
     */
    private Map<String, Object> buildJreSection(IJavaProject project) {
        try {
            IClasspathEntry jreContainerEntry = findJreContainerEntry(project);
            if (jreContainerEntry == null) return null;

            Map<String, Object> jre = new LinkedHashMap<>();

            IVMInstall vm = JavaRuntime.getVMInstall(project);
            if (vm != null) {
                jre.put("name", vm.getName());
                if (vm.getInstallLocation() != null) {
                    jre.put("installLocation", vm.getInstallLocation().getAbsolutePath());
                }
                jre.put("id", vm.getId());
                jre.put("typeId", vm.getVMInstallType().getId());
                if (vm instanceof IVMInstall2 vm2) {
                    String javaVersion = vm2.getJavaVersion();
                    if (javaVersion != null) jre.put("javaVersion", javaVersion);
                }
            }

            // System modules: the names visible inside the resolved entries of the
            // JRE container. Under JDK 9+ each system module surfaces as its own
            // resolved library entry whose path includes the module name as the
            // last segment.
            jre.put("systemModules", extractSystemModuleNames(project, jreContainerEntry));

            jre.put("containerPath", jreContainerEntry.getPath().toString());

            return jre;
        } catch (Exception e) {
            log.warn("Failed to build JRE section: {}", e.getMessage());
            return null;
        }
    }

    private IClasspathEntry findJreContainerEntry(IJavaProject project) throws JavaModelException {
        for (IClasspathEntry e : project.getRawClasspath()) {
            if (e.getEntryKind() == IClasspathEntry.CPE_CONTAINER
                && e.getPath().toString().startsWith(JavaRuntime.JRE_CONTAINER)) {
                return e;
            }
        }
        return null;
    }

    private List<String> extractSystemModuleNames(IJavaProject project, IClasspathEntry jreContainerEntry) {
        List<String> modules = new ArrayList<>();
        try {
            // Use the JDT model authoritatively: each system module exposed by the
            // JRE container surfaces as an IPackageFragmentRoot whose raw classpath
            // entry is the JRE container itself and whose getModuleDescription() is
            // non-null. The IModuleDescription's element name IS the module name —
            // no path-string parsing required, no JDT-internal heuristics.
            for (IPackageFragmentRoot root : project.getAllPackageFragmentRoots()) {
                IClasspathEntry raw = root.getRawClasspathEntry();
                if (raw == null) continue;
                if (raw.getEntryKind() != IClasspathEntry.CPE_CONTAINER) continue;
                if (!raw.getPath().equals(jreContainerEntry.getPath())) continue;
                IModuleDescription module = root.getModuleDescription();
                if (module == null) continue;
                modules.add(module.getElementName());
            }
        } catch (JavaModelException e) {
            log.warn("Failed to extract system module names: {}", e.getMessage());
        }
        return modules;
    }
}
