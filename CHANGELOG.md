# Changelog

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
