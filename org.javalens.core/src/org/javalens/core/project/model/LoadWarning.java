package org.javalens.core.project.model;

/**
 * Structured warning surfaced during project load.
 *
 * <p>Bug X fix: when external build-tool subprocesses (mvn/gradle/bazel) fail or produce
 * incomplete output, JavaLens previously returned an empty classpath silently. Loaders now
 * accumulate warnings into the {@code JdtServiceImpl} and the {@code load_project} response
 * surfaces them so MCP clients have a signal that analysis quality may be degraded.
 *
 * <p>Stable codes (use these in tests and downstream consumers):
 * <ul>
 *   <li>{@code BUILD_TOOL_UNAVAILABLE}: the build tool (Maven, Gradle) could not be
 *       started, or no binary for it exists.</li>
 *   <li>{@code BUILD_TOOL_FAILED}: the build tool ran and failed: it exited non-zero,
 *       Maven exited 0 without writing a classpath file, Gradle exited 0 without a
 *       readable project model, the load was interrupted while it ran, its run could not
 *       be prepared, or invoking it threw an unexpected error.</li>
 *   <li>{@code BUILD_TOOL_TIMEOUT}: the build tool did not finish within its time limit
 *       and was stopped.</li>
 *   <li>{@code BAZEL_NOT_BUILT} — {@code bazel-bin/}/{@code bazel-out/} not populated; user
 *       has not run {@code bazel build} recently.</li>
 *   <li>{@code COMPLIANCE_LEVEL_UNKNOWN} — could not determine the project's compiler
 *       source/target level; using JVM defaults.</li>
 *   <li>{@code APT_PROCESSOR_JARS_MISSING} — annotation processors were declared but
 *       every candidate jar was missing from disk at wiring time; APT is enabled with
 *       an empty factory path so generated members will not resolve.</li>
 *   <li>{@code MODULE_NOT_LOADED}: a module a build file declares was not loaded because
 *       its name is not a valid path or its directory does not exist.</li>
 *   <li>{@code MODULE_LAYOUT_ASSUMED}: a module's source roots were assumed from
 *       conventional layouts because the build model did not describe it.</li>
 *   <li>{@code COMPILED_OUTPUT_INCLUDED}: a module's compiled output is on the classpath
 *       because it holds classes with no linked source.</li>
 *   <li>{@code INCLUDED_OUTSIDE_BUILD}: a conventional source folder is linked although the
 *       build does not report it as a source root.</li>
 *   <li>{@code COMPLIANCE_LEVEL_MIXED}: modules declare different Java levels; the project
 *       is analyzed at the highest.</li>
 *   <li>{@code INDEX_INCOMPLETE}: the search index is missing declarations of source files
 *       even after re-indexing them; search-based results do not include those files.</li>
 *   <li>{@code EARLIER_PROJECT_KEPT}: the load completed and is in use, but an earlier
 *       JavaLens project could not be removed from the workspace; it stays until the next
 *       load removes it.</li>
 * </ul>
 *
 * @param code stable identifier (see list above)
 * @param message human-readable description of what happened
 * @param remediation actionable hint for the user (e.g., "Install Maven and ensure mvn is on PATH")
 * @param module optional module identifier; null for project-wide warnings
 */
public record LoadWarning(String code, String message, String remediation, String module) {

    /**
     * The build tool (Maven, Gradle) could not be started, or no binary for it exists, so
     * the project's dependencies are unresolved. The message names the tool and the
     * reason it could not start.
     */
    public static final String BUILD_TOOL_UNAVAILABLE = "BUILD_TOOL_UNAVAILABLE";

    /**
     * The build tool ran and failed: it exited non-zero, Maven exited 0 without writing a
     * classpath file, Gradle exited 0 without a readable project model, the load was
     * interrupted while it ran (it was then stopped together with every process it
     * started), the files its run needs could not be written, or invoking it threw an
     * unexpected error. The message names the tool and, when the tool exited after writing
     * output, ends with its last lines.
     */
    public static final String BUILD_TOOL_FAILED = "BUILD_TOOL_FAILED";

    /**
     * The build tool did not finish within its time limit and was stopped together with
     * every process it started. The message names the tool and the limit.
     */
    public static final String BUILD_TOOL_TIMEOUT = "BUILD_TOOL_TIMEOUT";

    public static final String BAZEL_NOT_BUILT = "BAZEL_NOT_BUILT";
    public static final String COMPLIANCE_LEVEL_UNKNOWN = "COMPLIANCE_LEVEL_UNKNOWN";
    /**
     * The build files declare a compiler level outside JDT's supported range
     * (below 1.8, or above the newest level this JDT knows). Applied verbatim,
     * such a level silently disables reconcile problem detection — diagnostics
     * would report a clean project regardless of real errors. The level is
     * clamped into the supported range instead, and this warning says so.
     */
    public static final String COMPLIANCE_LEVEL_UNSUPPORTED = "COMPLIANCE_LEVEL_UNSUPPORTED";
    /**
     * Annotation processors were declared by the project's build files but every candidate
     * jar failed the {@code Files.isRegularFile} probe at APT wiring time. APT is left
     * enabled with an empty factory path — analysis of processor-generated members will
     * silently return wrong results. Typical cause: stale paths cached from a previous
     * build, or {@code ~/.m2}/Gradle cache eviction between dependency resolution and
     * project load.
     */
    public static final String APT_PROCESSOR_JARS_MISSING = "APT_PROCESSOR_JARS_MISSING";

    /**
     * The running JVM could not be registered as an {@code IVMInstall} backing the
     * project's JRE container — typically because {@code System.getProperty("java.home")}
     * was unset or pointed at a non-existent directory. Without a backing install the
     * JRE container resolves to an empty system-modules set and every source file
     * referencing {@code java.lang.Object} produces a BUILDPATH cascade.
     */
    public static final String JRE_REGISTRATION_FAILED = "JRE_REGISTRATION_FAILED";

    /**
     * A build file declares a module (a Maven {@code <module>}, a Gradle {@code include})
     * that was not loaded, because the declared name is not a valid path or its directory
     * does not exist. The rest of the project loads without it. {@code module} carries the
     * declared name with control characters escaped; the message names the declaring file,
     * relative to the project root, and the reason.
     */
    public static final String MODULE_NOT_LOADED = "MODULE_NOT_LOADED";

    /**
     * A module's source roots were assumed from conventional layouts because the build
     * model did not describe it. When resolving the whole build model fails, the project
     * root is loaded as the single module {@code "."}, probed for conventional and
     * generated-source layouts, and the message names the failure.
     */
    public static final String MODULE_LAYOUT_ASSUMED = "MODULE_LAYOUT_ASSUMED";

    /**
     * A module's compiled output (a class directory or jar the build model reports) is on
     * the classpath because it holds top-level classes with no source in any linked source
     * root: classes from other JVM languages, bytecode generators, or sources in folders
     * the build does not declare. Output whose every class has a linked source is left off
     * the classpath. {@code module} carries the module id; the message names the output,
     * relative to the project root, and the number of source-less classes, and says that
     * results for the module may include compiled duplicates of linked sources and reflect
     * the last build, since class files are not verified against disk per query.
     */
    public static final String COMPILED_OUTPUT_INCLUDED = "COMPILED_OUTPUT_INCLUDED";

    /**
     * A source folder that a conventional layout probe finds in a loaded module's directory
     * ({@code src/main/java}, {@code src/test/java}, {@code src/main/kotlin},
     * {@code src/test/kotlin}, the {@code src/} fallback of a module that reports no source
     * or generated root under its directory, or a generated-source directory) is linked
     * although no module of the build as evaluated reports it as a source root. It stays
     * linked, after the module's reported roots, so its types stay visible. {@code module}
     * carries the module id; the message names the folder relative to the project root.
     */
    public static final String INCLUDED_OUTSIDE_BUILD = "INCLUDED_OUTSIDE_BUILD";

    /**
     * Modules declare different Java levels. All modules are analyzed as one project, at the
     * highest declared level, so a module's own lower level is not enforced. The message
     * lists each module that declares a level, with its id and level, in module order;
     * {@code module} is null.
     */
    public static final String COMPLIANCE_LEVEL_MIXED = "COMPLIANCE_LEVEL_MIXED";

    /**
     * The search index is missing declarations of loaded source files: a file's document in
     * the index lacks a top-level type the file declares, and re-indexing the file did not
     * restore it. Index-backed results (symbol search, references, call hierarchy) do not
     * include those files' types and references. The message names the files relative to the
     * project root, sorted. The warning reflects the index as last verified, which is at load
     * and after each disk-sync repair for the files it re-read, and goes once the files are
     * found complete. It comes after the model's warnings and before
     * {@link #EARLIER_PROJECT_KEPT}. {@code module} is null.
     */
    public static final String INDEX_INCOMPLETE = "INDEX_INCOMPLETE";

    /**
     * The load completed and its project is the one in use, but a project an earlier load
     * created could not be removed from the workspace, for example because a file in its
     * directory is locked. One warning per such project, after the load's other warnings;
     * the message names the project and the reason. The project stays in the workspace until
     * the next load removes it. {@code module} is null.
     */
    public static final String EARLIER_PROJECT_KEPT = "EARLIER_PROJECT_KEPT";

    public LoadWarning(String code, String message, String remediation) {
        this(code, message, remediation, null);
    }
}
