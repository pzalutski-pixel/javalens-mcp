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
 *       Maven exited 0 without writing a classpath file, the load was interrupted while
 *       it ran, or invoking it threw an unexpected error.</li>
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
     * classpath file, the load was interrupted while it ran (it was then stopped together
     * with every process it started), or invoking it threw an unexpected error. The message
     * names the tool and, when the tool exited after writing output, ends with its last
     * lines.
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

    public LoadWarning(String code, String message, String remediation) {
        this(code, message, remediation, null);
    }
}
