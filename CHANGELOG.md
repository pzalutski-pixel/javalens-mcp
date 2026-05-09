# Changelog

## 1.3.0 - 2026-05-08

### Build system support — major overhaul

End-to-end coverage for Maven, Gradle, and Bazel now exercised in CI on Linux, macOS, and Windows against synthetic real-shaped fixtures (multi-module reactors with cross-module deps, real external libraries, annotation processors).

#### Fixed
- **Silent subprocess failure**: when `mvn`/`gradle` is absent or its invocation fails, JavaLens previously returned an empty classpath with no signal. The `load_project` response now surfaces structured `LoadWarning` entries (`MAVEN_SUBPROCESS_FAILED`, `MAVEN_SUBPROCESS_TIMEOUT`, `GRADLE_SUBPROCESS_FAILED`, `COMPLIANCE_LEVEL_UNKNOWN`) so AI agents see degraded analysis explicitly.
- **Multi-module Maven**: per-reactor-child classpath aggregation. The original `dependency:build-classpath -Dmdep.outputFile=<absolute>` made every child overwrite the same file, so only the last module's classpath survived. Each module now writes to its own `target/javalens-classpath.txt`; results are union-merged via `LinkedHashSet`.
- **Multi-module APT**: `<annotationProcessorPaths>` declarations in child poms (typical pattern: `:model` declares Lombok) were missed because only the root pom was parsed. Now walks the reactor recursively.
- **Multi-project Gradle**: `settings.gradle` `include 'subproject'` directives are parsed; subproject `src/main/java` and `build/generated/sources/<task>/main/java` are added as source folders so types declared in subprojects resolve.
- **Bazel multi-target source discovery**: with the `<target>/src/main/java/...` layout, `BUILD.bazel` sat several levels above the Java sources and the existing scan returned nothing. Source roots now derive from every `BUILD.bazel` package.
- **Bazel `bazel-bin`/`bazel-out` dedup**: `bazel-bin` is typically a symlink whose target lives under `bazel-out`. Scan roots are canonicalized via `Path.toRealPath()` and jars dedupe by canonical path; previous scans double-counted every jar.
- **JDT search index race**: `loadProject` returned before JDT's background indexer finished, so the first `find_references`/`search_symbols` call after load could miss results. `loadProject` now flushes the indexer with `WAIT_UNTIL_READY_TO_SEARCH`.
- **Compiler compliance silently inherited workspace defaults**: `JavaCore.COMPILER_SOURCE`/`COMPLIANCE`/`CODEGEN_TARGET` now read from `maven.compiler.release`/`source`/`target`, Gradle `sourceCompatibility`, or Bazel `javacopts` (`-source`/`-target`/`--release` / `--release=N`). Java 21 record patterns and similar level-specific syntax now parse correctly.
- **Generated source directories absent from classpath**: annotation-processor outputs at `target/generated-sources/<processor>/` (Maven) and `build/generated/sources/<task>/main/java` (Gradle) are now discovered as source folders, so references to generated symbols (Lombok getters, MapStruct mappers, JPA metamodel) resolve.

#### Added
- **Gradle classpath support**: replaces the `getGradleDependencies` stub that returned `List.of()`. Ships an init script that registers a `javalensWriteClasspath` task on every Java subproject, runs it via the project's Gradle Wrapper or `gradle` on `PATH`, then unions the resulting `build/javalens-classpath.txt` files. Compile/test/runtime configurations are unioned so `compileOnly` deps (Lombok and similar) reach JDT.
- **JDT annotation processing wiring**: `org.eclipse.jdt.apt.core.util.AptConfig.setEnabled(true)` plus per-processor jar registration on the factory path. Maven processor jars resolve from pom `<annotationProcessorPaths>` against `~/.m2/repository`; Gradle processors come from the `annotationProcessor` configuration via the init script. A cross-cutting scan also auto-registers any classpath jar that declares `META-INF/services/javax.annotation.processing.Processor`, which closes the Bazel APT path without bespoke `java_plugin` parsing.
- **Plain Java compliance fallback**: projects with no build file fall back to `Runtime.version().feature()` rather than silently inheriting older JDT defaults.
- **`org.eclipse.jdt.apt.core` bundle** added to imports so APT wiring works at runtime.

#### Test infrastructure
- 12 new fixtures under `org.javalens.core.tests/test-resources/sample-projects/` covering single-module, multi-module, generated sources, compliance mismatch, broken-deps, Lombok APT, and realistic three-module representative projects per build system.
- 11 new `buildsystem/` test classes plus end-to-end integration tests (`EndToEndIntegrationTest`, `EndToEndGradleIntegrationTest`, `EndToEndBazelIntegrationTest`) that load a representative project and assert every fix is exercised in a single pass.
- New MCP-tool-level tests: `LoadProjectToolTest` extended with warnings-array shape checks; new `CrossModuleNavigationToolTest` exercises `find_references` across reactor modules through the actual `tool.execute(args)` JSON-RPC entry point an AI agent uses.
- `TestEnvironment.requireOrSkip` helper: tests skip on missing tools locally; setting `JAVALENS_TESTS_REQUIRE_TOOLS=true` flips them to hard failures so CI cannot silently weaken coverage.
- CI workflow installs Maven, Gradle, and `bazelisk` on Linux/macOS/Windows and runs with `JAVALENS_TESTS_REQUIRE_TOOLS=true`. Triggers on push to master in addition to PRs.

#### Documentation
- README build-system support tables expanded from "detection markers only" to capability matrices showing what each system reads from build metadata (multi-module, compliance, generated sources, APT). Architecture diagram converted to Mermaid for native GitHub rendering. Stale tool count and unbenchmarked perf claim removed.

## 1.2.0 - 2026-04-08

### Added
- 7 new analysis tools (Tool count: 56 → 63)
  - `analyze_change_impact` — blast radius assessment for symbol changes
  - `analyze_data_flow` — variable read/write/declaration tracking within methods
  - `analyze_control_flow` — branching, loops, return/throw points, nesting depth
  - `get_di_registrations` — Spring DI registration scanning (@Component, @Bean, @Autowired, @Inject)
  - `find_reflection_usage` — detect Class.forName(), Method.invoke(), and other reflection calls
  - `find_large_classes` — find types exceeding configurable size thresholds
  - `find_naming_violations` — check against Java naming conventions

## 1.1.5 - 2026-04-08

### Added
- Published to official MCP Registry (registry.modelcontextprotocol.io)
- Added `mcp-server` keyword to npm package for discoverability

## 1.1.4 - 2026-04-04

### Changed
- Updated npm package README with full feature documentation

## 1.1.3 - 2026-04-04

### Changed
- npm package now bundles the full distribution instead of downloading at runtime
  - No network dependency after install, no cache corruption, no version mismatch
- Added IGNORED_DIRS to skip .git, node_modules, target, etc. during Bazel source scanning
- Added slf4j.simple logging binding to product for production log visibility

## 1.1.2 - 2026-03-26

### Added
- Bazel `bazel-out/` directory scanning for dependency JARs
- Documented `JAVA_TOOL_OPTIONS` for JVM memory configuration on large projects

## 1.1.1 - 2026-03-25

### Added
- npm package distribution (`npx -y javalens-mcp`)
  - Downloads and caches the JavaLens distribution on first run
  - Verifies Java 21+ before launching
  - Reads JVM arguments from eclipse.ini automatically

### Fixed
- Updated all project versions from 1.0.0 to 1.1.1 across pom.xml, MANIFEST.MF, and product files
  - Previously version was stuck at 1.0.0 in source while releases used git tags

## 1.1.0 - 2026-03-25

### Added
- Bazel build system support
  - Detects projects via `MODULE.bazel`, `WORKSPACE.bazel`, and `WORKSPACE` markers
  - Scans `bazel-bin/` for dependency JARs
  - Discovers source directories in Bazel-native layouts (Java files alongside BUILD files)
  - Falls back to standard source layouts (`src/main/java`) when present
- 6 new tests for Bazel detection and source scanning

### Changed
- README clarifies Java 21 is the server runtime, not an analysis restriction
  - JavaLens analyzes Java source from version 1.1 through 23

## 1.0.2 - 2026-01-03

### Fixed
- Fixed MCP connection timeout with `JAVA_PROJECT_PATH` auto-load
  - Project now loads asynchronously in background
  - Server responds to MCP `initialize` immediately instead of blocking
  - No more 30-second timeout failures for large projects

### Added
- Loading state tracking via `health_check`
  - New `project.status` field: `not_loaded`, `loading`, `loaded`, `failed`
  - Tools return informative "Project is loading, please wait..." message during async load
- `ProjectLoadingState` enum for tracking load progress
- `ToolResponse.projectLoading()` and `ToolResponse.projectLoadFailed()` for better error messages

### Changed
- `HealthCheckTool` now reports detailed loading state instead of just loaded/not-loaded
- `AbstractTool.execute()` provides specific feedback based on loading state

## 1.0.1 - 2026-01-01

### Fixed
- Fixed race condition when multiple MCP instances start simultaneously
  - Session UUID now injected into workspace path before OSGi framework starts
  - Each session gets isolated workspace, preventing "workspace locked" errors

### Changed
- Replaced native launcher with Java wrapper JAR
- Single distribution for all platforms (no more platform-specific archives)
- Invocation changed from `javalensc` to `java -jar javalens.jar`

## 1.0.0 - 2025-12-31

Initial release of JavaLens MCP Server.

- 56 MCP tools for Java code navigation, analysis, refactoring, and search
- Eclipse JDT Core for semantic analysis
- Maven and Gradle project support
- 347 tests
