# Changelog

## [1.3.6] - 2026-06-05

### Fixed

- The JRE registration added in 1.3.5 was not written to the persisted VM configuration when JDT's default-VM pointer already referenced the running JVM, leaving the JRE container unbacked on environments where startup VM detection registers nothing (e.g. SDKMAN-managed JDKs with no JVM under the system Java location). The running JVM is now set as the default unconditionally, so the registration persists.

## [1.3.5] - 2026-05-26

### Fixed

- Projects loaded in environments where JDT's default-JRE auto-detection doesn't fire are no longer left with an unresolved JRE container (which caused BUILDPATH cascades on every source file referencing `java.lang.Object`). The running JVM is now registered explicitly as the project's JRE before the container is added.

### Added

- `LoadWarning.JRE_REGISTRATION_FAILED` surfaces when the running JVM cannot be registered (`java.home` unset or invalid). Turns an otherwise opaque BUILDPATH cascade into an actionable load-time signal.

## [1.3.4] - 2026-05-25

### Added

- `get_classpath_info` now returns `resolved` (containers expanded, per-entry `exists` flag, optional `module` attribute) and `jre` (`name`, `installLocation`, `javaVersion`, `systemModules`, `containerPath`) sections.

### Fixed

- `get_classpath_info` `resolved` source-folder entries no longer report `exists: false` due to checking workspace-relative paths against the filesystem.

## [1.3.3] - 2026-05-23

### Fixed

- `find_unused_code` reported every private field of a generic class as unused even when read in the same class (#17).
- `rename_symbol` returned zero edits on large projects despite valid references existing (#13).
- `rename_symbol` on a type parameter (e.g. `T` in `class Foo<T>`) produced zero edits instead of renaming the parameter and its usages.
- `change_method_signature` did not propagate to `super.method()` call sites in subclasses.
- `change_method_signature` silently dropped method-reference call sites (`Foo::method`, `instance::method`, `super::method`, `Foo::new`). They now surface in `methodReferences` with `warnings`; rewriting the reference textually would either rebind to the new signature or require synthesizing a lambda, both of which need context the tool cannot recover.
- `analyze_data_flow` misclassified qualified-field writes (`this.x = v`, `super.x = v`, `Outer.x = v`) as reads.
- `analyze_data_flow` counted compound assignment (`x += 5`) as a write only, missing the implicit read.
- `inline_method` silently inlined method bodies containing `super.method()`, `super.field`, or `super::method` — `super` would have rebound to a different class hierarchy. The tool now refuses with a reason.
- `extract_constant` produced uncompilable `private static final` declarations when the expression referenced instance state (`this`, instance field, non-static method). The tool now refuses with a reason.
- `extract_variable` lifted expressions whose evaluation context made the hoist semantically unsafe — for-loop conditions, while/do conditions, short-circuit `&&` / `||` right operands, ternary then/else branches. The tool now refuses with a reason.
- `extract_interface` produced uncompilable interfaces when the source class declared type parameters used by extracted methods. Both class-level and method-level type parameters are now propagated, and the `implements` clause carries the type arguments.
- `extract_method` produced uncompilable code when the extracted block declared a variable used after the block. The call site now redeclares the returned variable. The extracted method also inherits the containing method's type parameters when needed.
- `convert_anonymous_to_lambda` silently produced incorrect lambdas when the anonymous body referenced `super` or declared fields, initializers, or nested types, and over-refused on `EnclosingClass.this`. The refusal-decision matrix is now complete.
- `find_circular_dependencies` and `get_dependency_graph` missed cycles established only through static imports (`import static com.foo.Bar.method`) or nested-class imports (`import com.foo.Outer.Inner`).
- `find_large_classes` skipped top-level enums, records, and annotation types.
- `find_naming_violations` did not check `record` or `annotation` declarations.
- `find_possible_bugs` did not detect null-dereferences despite listing them as a category.
- `find_tests` did not recognize `@TestFactory` or `@TestTemplate` test methods.
- `get_call_hierarchy_outgoing` did not emit callees for `this(...)`, `super(...)`, or method references.
- `analyze_method` did not include `this(...)`, `super(...)`, or method references in its callee list.
- `get_document_symbols` did not emit record components as child symbols.
- `get_hover_info` and `get_signature_help` omitted type parameters from generic-type and generic-method signatures.
- `get_javadoc` did not parse `@apiNote`, `@implSpec`, or `@implNote`, and did not merge `@exception` into the `throws` list.
- Position-resolving tools (`go_to_definition`, `find_references`, `rename_symbol`, …) returned the enclosing member for positions inside string literals, character literals, text blocks, comments, and past end-of-file instead of refusing the position.
- Type-analysis output across `analyze_type`, `analyze_file`, `get_type_members`, and related tools omitted the `sealed` and `non-sealed` modifiers.

### Changed

- `rename_symbol` now uses indexed search for fields, methods, and types instead of project-wide AST parsing. Substantially faster on large projects, and correct under index pressure at scale. Local variables and type parameters continue to use AST traversal scoped to the declaring file. Output shape unchanged.
- `analyze_type` response now includes `typeParameters` and `typeParameterBounds`.
- `analyze_method` response now includes method-level `typeParameters` and `typeParameterBounds`.
- Descriptions for `analyze_file`, `analyze_method`, `analyze_type`, and `get_type_usage_summary` now follow the `USAGE:` / `OUTPUT:` convention used by the rest of the tool surface.

### Removed

- `docs/TESTING.md`.

## 1.3.2 - 2026-05-19

### Output shape — unified across reference-search tools

JavaLens output is consumed only by LLMs that re-read each tool's schema on call. The 1.3.x reference-search tools previously returned the same conceptual list under different field names per tool, forcing the consumer to remember three conventions. Renamed for consistency:

- `find_references` now returns `locations` (was `references`) and `totalCount` (was `totalReferences`).
- All fine-grain reference tools — `find_casts`, `find_instanceof_checks`, `find_type_instantiations`, `find_throws_declarations`, `find_catch_blocks`, `find_type_arguments`, `find_annotation_usages`, `find_method_references` — now return `locations` / `totalCount` / `advice` under those exact names. Previously each tool used a different result-list field (`casts`, `instanceofChecks`, `instantiations`, `throwsDeclarations`, `catchBlocks`, `typeArgumentUsages`, `usages`) and a different count field.
- `analyze_change_impact.affectedFiles[]` entries now use `filePath` (was `file`) to match every other tool's path field.

### Input parameter — unified for `find_*` tools

- `find_throws_declarations` and `find_catch_blocks` now accept `typeName` (was `exceptionType`).
- `find_annotation_usages` now accepts `typeName` (was `annotation`).

### Bug fixes

- **`change_method_signature` on constructors** no longer emits a syntactically-invalid `void Foo(...)` signature. The return-type prefix is now omitted for constructors, and the response's `oldReturnType` / `newReturnType` keys are omitted entirely (rather than reported as `"void"`).
- **`rename_symbol` of a method** now propagates the rename to overriding methods in subtypes and overridden methods in supertypes. Previously, renaming an interface method would edit only the declaration and leave implementors uncompilable.
- **`analyze_type` of an annotation type** now includes the `annotationUsages` counter alongside `instantiations`, `casts`, `instanceofChecks`, `typeArguments`. Missing in 1.3.1.
- **`get_type_members` nested `@interface`** now reports `kind: annotation`, matching the top-level case. Previously reported `Interface` because the nested-type branch checked `isInterface()` before `isAnnotation()`.

### Validation — strict `maxResults`

Six tools — `search_symbols`, `find_implementations`, `find_references`, `find_field_writes`, `get_call_hierarchy_incoming`, `get_document_symbols` — previously silently clamped `maxResults` to `[1, N]`, so a caller passing `0` got `1` result with no signal. All `maxResults`-accepting tools now reject negative values with `INVALID_PARAMETER` and honor `0` literally (return zero results). The `INVALID_PARAMETER` message names the offending parameter.

`search_symbols.offset` gets the same treatment: negative offset rejected explicitly.

### Type kind strings unified to lowercase

Every tool that reports a type kind now emits one of `class`, `interface`, `enum`, `record`, `annotation` (lowercase). 1.3.1 mixed capitalization across tools — `get_dependency_graph` emitted lowercase while others emitted `Class` / `Interface` / etc. The mixed shape made it harder for AI consumers to write robust filters.

### Response envelope on paginated tools

All 18 tools that accept `maxResults` now populate `meta.truncated`, `meta.totalCount`, and `meta.returnedCount` on every successful response. The four that were missing one or more of these fields — `search_symbols`, `suggest_imports`, `get_di_registrations`, `find_reflection_usage` — have been fixed. `truncated` is now driven by the actual pre-cap count compared to the returned-list size, so it is accurate when matches exactly equal `maxResults`.

### Suggest imports — filter internal packages

`suggest_imports` now omits candidates from packages matching `sun.*`, `com.sun.*`, `*.internal.*`, or `*.impl.*`. Previously the LLM could be handed implementation-detail types as if they were legitimate import targets.

### Test infrastructure

- 1.3.2 ships the source-side coverage audit across all 60+ MCP tools. Every documented branch now has at least one assertion that would fail if the branch broke, including absence-of-key contracts (when an optional field is documented to be omitted in a given case).
- New `JdtContractTest` pins the JDT-API behaviors JavaLens depends on (dotted-vs-$-form `findType`, `@interface` ordering quirk on `isAnnotation` / `isInterface`, search-match subclass per kind, `R_REGEXP_MATCH` unimplemented). A future JDT upgrade surfaces "JDT changed" cleanly vs "our wrapper broke".
- New `ResponseEnvelopeContractTest` and `MaxResultsBoundaryContractTest` enforce the envelope + maxResults contracts across every paginated tool — no per-tool drift.
- New `ErrorCodeContractTest` enforces the documented `INVALID_PARAMETER` failure path on every tool with required params.
- New fixtures (`broken-symbols`, `OrganizeImportsFixture`, `Java21Modern`, additions to `TypeKindsFixture`) cover problem-trigger paths, static/on-demand imports, modern Java syntax, and the 200-character Javadoc truncation contract.

### Internal refactors

These do not change behavior but reduce surface area for future regressions:

- `ProjectImporter` split from a 1484-LOC god class into a 416-LOC orchestrator plus `MavenImporter`, `GradleImporter`, `BazelImporter`, `LinkedFolderConfigurator`, and a `BuildSystemImporter` strategy interface. Adding a new build system is now an interface implementation plus a single map entry.
- `SearchService` collapses 8 named fine-grain methods into one `findReferences(IType, ReferenceKind, int)` entry point.
- 8 fine-grain `find_*` tools collapse onto a shared `AbstractFineGrainReferenceTool` base.
- `TypeKindResolver` and `ElementKindResolver` centralize kind-string emission so capitalization and ordering quirks are fixed in one place.
- `ModifierFormatter`, `MethodFormatter`, `MatchResolver`, `SchemaBuilder` consolidate previously-duplicated patterns.
- `ErrorInfo.fromThrowable` unwraps JDT `CoreException` into structured error info (plugin id, status code, severity) on every tool's catch-all.

## 1.3.1 - 2026-05-15

### Tool output fixes

Tools that previously returned wrong or empty answers in 1.3.0 now return correct answers. If your workflow relied on any of these, the output will change.

- **Annotation types** (`@interface`) are now classified as `Annotation` instead of `Interface`. Affects `analyze_type`, `analyze_file`, `find_implementations`, `get_hover_info`, `get_type_at_position`, `get_document_symbols`, `get_type_hierarchy`, `get_type_members`, `get_enclosing_element`, `get_symbol_info`, `go_to_definition`, `rename_symbol`, `search_symbols`, `get_type_usage_summary`, `get_dependency_graph`, `find_references` — every tool that reports a type kind.
- **Record types** now report `kind: Record` instead of `Class`.
- **`find_references.referenceKind`** classifies by the reference's actual role (`TYPE_REFERENCE` / `FIELD_ACCESS` / `METHOD_INVOCATION`) instead of by the enclosing element. A type usage inside a field declaration is no longer `FIELD_ACCESS`; a field read inside a method body is no longer `METHOD_INVOCATION`.
- **`search_symbols`** `?` single-char wildcard now works as documented. `?Shape` matches `IShape`. Previously returned empty.
- **`find_implementations`** returns transitive implementors (subinterface chains and multi-level class hierarchies), not just direct ones. Also now finds annotation-type users.
- **`extract_interface`** no longer pulls `Object` methods (`toString`/`hashCode`/`equals`) into the extracted interface by default.
- **`change_method_signature`** updates constructor call sites, not just method invocations.
- **`organize_imports`** correctly identifies unused imports. In 1.3.0 every import was reported as used because the import declaration itself was being counted as a reference to its own type.
- **`get_diagnostics`**, **`analyze_type`**, **`analyze_file`**, **`get_quick_fixes`** reliably surface compilation errors and warnings. These tools silently returned empty results in 1.3.0 for many files.
- **`get_quick_fixes`** offers "Remove unused import" fixes for files with unused imports.
- **`get_diagnostics`** `maxResults` cap is now actually applied (was a no-op).
- **`find_reflection_usage`** `maxResults` is now per reflection-API category as documented (was per method overload, returning more than asked).
- **`get_enclosing_element`** more reliably resolves the enclosing method at the requested position.

### Search engine

- Fine-grain reference searches — `find_annotation_usages`, `find_casts`, `find_instanceof_checks`, `find_throws_declarations`, `find_catch_blocks`, `find_type_arguments`, `find_type_instantiations` — now find references to **nested types** like `Outer.Inner` (previously returned nothing).
- Fine-grain searches for common JDK types no longer stall scanning the JDK index.
- Fine-grain search results no longer include JDK-internal entries with broken file paths.

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
- **Bazel symlink scan platform divergence**: on Linux/macOS, `bazel-bin` and `bazel-out` are real symbolic links whereas on Windows they are junctions. `Files.walk` without `FOLLOW_LINKS` treats a Linux symlink-to-directory as a non-directory single entry — so the previous scan, which kept the original symlink path after canonicalization, descended on Windows but emitted zero entries on Linux/macOS. `canonicalizeAndDedupe` now returns canonical paths so the walk always starts at a real directory.
- **Nested aggregator Maven modules** (issue #8 — reported by external user): a child module that is itself an aggregator (`<packaging>pom</packaging>` with `<modules>` and no `src/main/java`) caused source discovery to stop at depth 1. Sources under `core/core-lib`, `core/core-api`, etc. were never indexed. `getAllSourcePaths` is now recursive, with cycle protection via canonical-path visited set against relative `<module>` entries that loop.
- **JDT search index race**: `loadProject` returned before JDT's background indexer finished, so the first `find_references`/`search_symbols` call after load could miss results. `loadProject` now flushes the indexer with `WAIT_UNTIL_READY_TO_SEARCH`.
- **Compiler compliance silently inherited workspace defaults**: `JavaCore.COMPILER_SOURCE`/`COMPLIANCE`/`CODEGEN_TARGET` now read from `maven.compiler.release`/`source`/`target`, Gradle `sourceCompatibility`, or Bazel `javacopts` (`-source`/`-target`/`--release` / `--release=N`). Java 21 record patterns and similar level-specific syntax now parse correctly.
- **Generated source directories absent from classpath**: annotation-processor outputs at `target/generated-sources/<processor>/` (Maven) and `build/generated/sources/<task>/main/java` (Gradle) are now discovered as source folders, so references to generated symbols (Lombok getters, MapStruct mappers, JPA metamodel) resolve.

#### Added
- **Gradle classpath support**: replaces the `getGradleDependencies` stub that returned `List.of()`. Ships an init script that registers a `javalensWriteClasspath` task on every Java subproject, runs it via the project's Gradle Wrapper or `gradle` on `PATH`, then unions the resulting `build/javalens-classpath.txt` files. Compile/test/runtime configurations are unioned so `compileOnly` deps (Lombok and similar) reach JDT.
- **JDT annotation processing wiring**: `org.eclipse.jdt.apt.core.util.AptConfig.setEnabled(true)` plus per-processor jar registration on the factory path. Maven processor jars resolve from pom `<annotationProcessorPaths>` against `~/.m2/repository`; Gradle processors come from the `annotationProcessor` configuration via the init script. For Bazel — which has no equivalent processor-path declaration that we parse — JavaLens scans the resolved Bazel classpath jars for `META-INF/services/javax.annotation.processing.Processor` and auto-registers any that match. The scan runs only on the Bazel path; Maven and Gradle continue to use their build-file-driven detection so we don't double-register.
- **Plain Java compliance fallback**: projects with no build file fall back to `Runtime.version().feature()` rather than silently inheriting older JDT defaults.
- **`org.eclipse.jdt.apt.core` bundle** added to imports so APT wiring works at runtime.

#### Test infrastructure
- 12 new fixtures under `org.javalens.core.tests/test-resources/sample-projects/` covering single-module, multi-module, generated sources, compliance mismatch, broken-deps, Lombok APT, and realistic three-module representative projects per build system.
- 11 new `buildsystem/` test classes plus end-to-end integration tests (`EndToEndIntegrationTest`, `EndToEndGradleIntegrationTest`, `EndToEndBazelIntegrationTest`) that load a representative project and assert every fix is exercised in a single pass.
- New MCP-tool-level tests: `LoadProjectToolTest` extended with warnings-array shape checks; new `CrossModuleNavigationToolTest` exercises `find_references` across reactor modules through the actual `tool.execute(args)` JSON-RPC entry point an AI agent uses.
- `TestEnvironment.requireOrSkip` helper: tests skip on missing tools locally; setting `JAVALENS_TESTS_REQUIRE_TOOLS=true` flips them to hard failures so CI cannot silently weaken coverage.
- Subprocess test helpers (`runMaven`/`runBazel` across all integration tests) now capture stdout into a bounded buffer and include it in the failure exception so CI failures are debuggable without the runner artifact upload.
- CI workflow installs Maven, Gradle, and `bazelisk` on Linux/macOS/Windows and runs with `JAVALENS_TESTS_REQUIRE_TOOLS=true`. Triggers on push to master in addition to PRs.
- Gradle install unified across all three OSes via the vendor-supported `gradle/actions/setup-gradle@v3` action pinned at 8.10.2. Replaces `apt-get install gradle` (Ubuntu's Gradle 4.x is too old for the init script) and choco's gradle.bat (didn't propagate reliably to the surefire JVM on Windows).
- Maven on Windows switched from `choco install maven` to direct Apache distribution download and extract. Choco's `mvn.cmd` launcher tries to read `lib/jvm.cfg` from the JDK, which Temurin (the distribution `setup-java` provides) doesn't ship, so the launcher fails before reaching Maven itself. Direct extract gives a launcher that works with any JDK that has `bin/java`.

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

[1.3.5]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.3.4...v1.3.5
[1.3.4]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.3.3...v1.3.4
[1.3.3]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.3.2...v1.3.3
