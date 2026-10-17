# Changelog

## [1.5.2] - 2026-10-10

### Fixed: critical (destructive edits and silent wrong answers, present in 1.5.1)

- **`rename_symbol` on a type corrupted imports and skipped constructors.** The edit for an import or a qualified reference was built at the first qualifier with the simple name's length, so the import line was mangled; explicit constructors were not renamed, and the file of a public top-level type was not renamed. Renames now edit each occurrence exactly, rename constructors, and report `renamedFiles` when the compilation unit must be renamed.
- **`rename_symbol` ran no conflict checks.** A rename that leaves code not compiling (a name already taken in the type, package, imports or hierarchy, or a shadowed reference) was answered with edits. It now runs JDT's own rename conditions and refuses with every conflict named; JDT's warnings are listed in a new `warnings` field.
- **`change_method_signature` changed only the selected method**, not the methods that override or implement it, and missed enum-constant arguments, static imports, implicit `super()` calls, Javadoc references and lambdas, so the result did not compile. Every related method and occurrence is now updated. Renaming a parameter (`oldName`) renames it in bodies, `@param` tags and Javadoc references instead of removing and re-adding it. Compile errors that follow from the requested change are listed in `introducedProblems`; a rename that would change what a name refers to is refused.
- **Refactorings on local and anonymous classes produced wrong edits.** Type hierarchies left out local and anonymous subtypes (2 of 4 implementors in a measured case), so `rename_symbol`, `push_down` and `pull_up` produced edits that did not compile, and `extract_superclass` on an anonymous class made the outer class extend the new superclass. Hierarchies now come from the Java model, and refactorings JDT does not allow on such classes are refused.
- **Path-taking tools could answer about the wrong file.** A file was mapped to a type by stripping a conventional source prefix, so with the same class name in two modules a tool could answer for the other module's file, and files in custom source roots were not found. Each path now maps to its exact compilation unit.
- **The search index could silently lose a file after a reload** (a JDT indexer defect drops a document's declarations when the index merges mid-indexing). `find_references` then reported a complete result while missing every reference in that file. The trigger is removed, the index is verified against the sources after every load and repair, and a gap that survives is reported (`INDEX_INCOMPLETE` load warning, `meta.indexIncomplete` on index-backed tool responses).
- **The JDK could silently disappear from every search for the rest of the session** (a JDT race between library indexing and index removal), so `suggest_imports` offered nothing for `List`. Library indexes are now verified and rebuilt after every load; a library still missing is reported (`LIBRARY_INDEX_INCOMPLETE`, `meta.libraryIndexIncomplete`).
- **A failed reload destroyed the project that was already loaded.** A reload now replaces the earlier project only after it succeeds; loads run one at a time and the newest request wins.
- **Logging never worked** in the released product since at least 1.3.1 (no SLF4J provider was bound), and `JAVALENS_LOG_LEVEL` was never read. Logs now reach stderr at the configured level.

### Fixed: build loading

- **A `gradle init` comment in a CRLF `settings.gradle` aborted `load_project` on Windows** (#34). JavaLens now takes the project structure, custom `projectDir`s, included builds, source sets, generated-source directories, classpath and per-project levels from Gradle itself, writing nothing into the project tree. When Gradle cannot run, `settings.gradle` / `settings.gradle.kts` is read as code, so comments and strings declare nothing. Content in a build file no longer aborts a load; problems become named load warnings.
- A Gradle build with a settings file and no root build file, the layout `gradle init` generates, was loaded as plain Java, so Gradle was never asked for its classpath. A `settings.gradle` or `settings.gradle.kts` now marks a Gradle build.
- **Maven projects are read from Maven's effective model.** Modules (a commented-out `<module>` is no module), `../` modules, source, test, build-helper and Kotlin directories, generated sources under the effective build directory, per-module classpaths, and levels as maven-compiler-plugin applies them: `release` before `source`, plugin configuration before properties (the reverse of what was applied before), inherited from parent poms. The project's own modules are linked as sources and never added again as jars or class folders. Annotation processors resolve in Maven's real local repository. A module Maven cannot resolve is named (`BUILD_TOOL_FAILED`) instead of the whole classpath being lost. Modules of an inactive profile, and Gradle projects of a `settings.gradle` beside the root pom, stay linked with `INCLUDED_OUTSIDE_BUILD`.
- **Bazel projects are read from Bazel.** Java targets and their sources come from `bazel query`; each target's level comes from its javac action as javac takes it, including the toolchain default and `.bazelrc` (a commented-out `javacopts` no longer wins as "highest"); the classpath and processor path come from the javac actions. The project's own target jars are not added as libraries. Source roots follow the package each source declares, so non-conventional layouts load.
- When a build tool cannot run, the build files are read as code: poms as XML, Gradle settings and Bazel BUILD files through a lexer, so comments and strings declare nothing; the load says so with `BUILD_MODEL_STATIC`.
- `RELOAD_REQUIRED` covers every file the build reads, including ones created after the load: `gradle.properties`, version catalogs, `buildSrc`, scripts applied with `apply from`, `.mvn/maven.config`, `.bazelrc`, `.bzl` files and poms of modules outside the root. It watched ten fixed file names before.
- `load_project` and `health_check` report a `buildModel` summary (build system, `build-tool` / `static-read` / `convention`, analysis level, modules) and every load warning, so a degraded load, including a `JAVA_PROJECT_PATH` auto-load, is visible.
- Conventional source folders the build does not declare stay linked (`INCLUDED_OUTSIDE_BUILD`); compiled output is on the classpath only when it holds classes with no source (`COMPILED_OUTPUT_INCLUDED`), which removes duplicate `target.classes.*` types; modules declaring different levels are reported (`COMPLIANCE_LEVEL_MIXED`).
- Relative paths given to tools resolve against the project root, not the server's working directory.

### Fixed: silent wrong or partial answers

- About 50 places that swallowed an error and answered empty or with a default (0 for a line, `""` for a name) now fail with `INTERNAL_ERROR` or report the real state.
- `find_references` counted matches of unrelated same-named methods as references; inaccurate matches are now listed separately in `potentialMatches` and never edited by refactorings. A position on a reference the compiler cannot resolve has no symbol (`SYMBOL_NOT_FOUND`).
- Hierarchy tools left out a supertype the compiler cannot resolve; it is now reported as unresolved.
- Capped lists report exact totals and `truncated` (`analyze_change_impact`, `get_type_usage_summary`, `get_di_registrations`, `find_reflection_usage`, `analyze_method`, `get_diagnostics`); `suggest_imports` and `get_quick_fixes` rank every candidate before cutting.
- `search_symbols kind=field` returned nothing for constants and enum constants, `kind=method` returned lambdas, and paging broke after filtering. Kinds now select exactly, and pages are filled after filters.
- Anonymous classes in and after lambda bodies were numbered wrongly, so tools answered about the wrong class. Empty names for anonymous and local classes, initializers and the default package are now `pkg.Outer$1`, `<initializer>` and `(default package)`.
- `load_project` echoed `"projectPath": ""`; it now echoes the absolute root.
- `find_possible_bugs` for a file that does not exist and `get_dependency_graph` for an unknown package now answer errors instead of empty successes.
- Generated code that did not compile (`extract_variable` declaring `void`, `extract_interface` without imports, capture types) now compiles or is refused as JDT does.
- Refactoring warnings no longer change order from one load to the next.
- `rename_symbol` on the import list or on a library type or field answered `INTERNAL_ERROR`; it now refuses, naming it.

### Changed

- `JAVALENS_TIMEOUT_SECONDS` was documented but never applied. It now limits each tool call (5 to 300 seconds; a call past it returns `TIMEOUT`). Unset means no limit; the documented default of 30 was never in effect and is removed.
- Unknown values of enum-like parameters (`kind`, `severity`, `memberKind`) and out-of-range limits are `INVALID_PARAMETER` instead of being ignored.
- `get_type_hierarchy` `maxDepth`, which limited list length, is renamed `maxResults`.
- Removed: `get_complexity_metrics` `granularity` (every value gave the same answer), the `package` field of `get_dependency_graph` package nodes, and `get_type_hierarchy`'s `external` marker.
- Build-tool warnings are uniform: `BUILD_TOOL_UNAVAILABLE`, `BUILD_TOOL_FAILED` and `BUILD_TOOL_TIMEOUT` replace `MAVEN_SUBPROCESS_FAILED`, `MAVEN_SUBPROCESS_TIMEOUT` and `GRADLE_SUBPROCESS_FAILED`.
- `JAVALENS_MAVEN_ARGS`, `JAVALENS_GRADLE_ARGS` and `JAVALENS_BAZEL_ARGS` pass extra arguments (profiles, properties, flags) to the build tool when it reads the model.
- npm releases are published through trusted publishing, and the release build requires Maven, Gradle and Bazel, so their tests can no longer be skipped there.
- README: Windows is not in the CI matrix (Linux and macOS); Windows line endings are covered by tests that write CRLF files explicitly.

## [1.5.1] - 2026-06-15

### Fixed

- `find_affected_tests` and `analyze_change_impact` (transitive mode) returned nothing when the selected symbol was a **class/type** whose tests exercise it through its members — a class with an explicit constructor, or covered only via method calls and field reads (#32). The reverse-closure walk looked only at the type node's direct callers (implicit-constructor instantiations); it now aggregates callers across the type **and all its members** (constructors, methods, fields). Both tools always claimed type support in their descriptions but no test pinned it — they now do, across all three member kinds.
- `search_symbols` line/column output is confirmed 0-based and round-trips into the position-taking tools with no adjustment; the reported off-by-one (#31) was a symptom of #32 (zero results prompting adjacent-position retries), not a coordinate bug. Pinned with a round-trip consistency test.
- `health_check` reported "no project loaded" after a successful `load_project` **tool** call. The loading state was flipped to LOADED only by the `JAVA_PROJECT_PATH` auto-load path; the tool's registration set the service but not the state. Loading via the tool now reflects LOADED in `health_check`.
- `find_reflection_usage` returned matches from JavaLens's own Eclipse workspace metadata — hundreds of absolute-path entries under the build directory — mixed in with the real project reflection sites. Its reference search ran against the full workspace scope instead of the project's sources; it now searches a sources-only scope filtered to `.java` files, so only project matches are returned.
- `get_dependency_graph` fabricated dependency nodes for primitives and `java.lang` types — `int`, `boolean`, `String` and the like rendered as `<context-package>.int` — and counted them as edges, inflating the graph. Unqualified type names are now resolved through the declaring type (so `String` resolves to `java.lang.String` and is filtered as external), primitives are dropped, and names that don't resolve are omitted instead of guessed into the context package.
- `apply_quick_fix` accepted a fix ID with an empty parameter (e.g. `add_import:`) and emitted a malformed edit (`import ;`) while reporting success. A trailing-empty parameter is now rejected as an invalid fix-ID format.

### Tests

- The MCP detection harness no longer relies on a captured "golden" snapshot. A captured snapshot is a characterization test, not a correctness oracle: its expected value is whatever the tool emitted on capture day, so a bug present at capture freezes as "correct" and ships green forever. An independent multi-agent audit found this concretely — roughly 18 of the 75 tools had a **degenerate** snapshot (an empty or error envelope, because the shared parity input never exercised the tool's real capability), and `find_reflection_usage`'s snapshot had frozen the workspace-scope leak fixed above as if it were the expected answer. The snapshot oracle and its golden files are removed.
- Every one of the 75 tools is now pinned by two authored seams, with every expected value derived by hand from the fixture source (never captured from the tool): an `execute()` correctness oracle (exact sets, counts, qualified names, 0-based coordinates, and edit ranges, plus negative/isolation cases) **and** an MCP-envelope check that drives the tool through the real `McpProtocolHandler.processMessage` and production `registerTools()` wiring (the path that surfaced the `health_check` desync above) and asserts the same authored values on the payload a client actually receives — each on the fixture where the tool's capability applies (inheritance refactorings on a class hierarchy, the framework extractors on a Spring/JPA project, interprocedural data flow on its flow fixture, and so on).
- Two permanent structural gates drive every tool through the envelope and make the audit's two failure classes unrepeatable across the whole surface: a **fixture-scope** gate (no tool payload may carry a path into the JDT workspace metadata — the search-scope escape behind the reflection leak) and a **non-degeneracy** gate (every tool returns populated success data or an explicitly-coded refusal, so a hollow envelope can no longer pass as coverage).
- Registration anchor: a literal `assertEquals(75, …)` plus bidirectional registry/coverage set equality, so a tool deleted from registration (or swapped) fails instead of shipping green.
- Detection-strength hardening from the audit: `analyze_change_impact` default-mode type/field blast radius pinned exactly; `analyze_data_flow` read/write counts tightened from `>=` to exact; `find_circular_dependencies` high-severity branch exercised by a new 3-package-cycle fixture; `find_reflection_usage` false-positive isolation (`Supplier.get()` is not `Field.get`). All expected values derive from the fixture source, not from running the tool under test.

## [1.5.0] - 2026-06-11

### Changed

- **Every answer is now verified against the files on disk at query time** (issue #26). Before any tool logic runs, JavaLens content-hashes the known source files, discovers edits/additions/deletions itself, repairs exactly what changed (scoped resource refresh, single-unit reconcile, search-index barrier, derived-cache invalidation), and only then answers.
  - **How the interaction changed**: the old loop was *edit → call `load_project` → query* — and forgetting the reload meant silently stale answers. The new loop is *edit → query*: there are no sync calls and nothing for the AI to remember; the server discovers changes itself. Tool descriptions and the MCP `instructions` field state the active contract, so the AI is always told the truth about what the server guarantees.
  - **Why**: a forgotten reload was the one way a compiler-accurate tool could be silently wrong. The accuracy guarantee is now structural — content-verified per query — instead of depending on caller discipline.
  - **Benefits**: the edit→verify cycle drops from a full project reindex (~30–60 s on a ~300-file project) to a delta repair (sub-second); verification itself measures ~2 ms per query at 72 files, ~25 ms at 1,000, ~180 ms at 10,000 (hash-based, parallel). Answers carry an unconditional disk-truth guarantee.
  - **Fallback**: `JAVALENS_DISK_SYNC=manual` restores the pre-1.5.0 behavior *and* the pre-1.5.0 tool descriptions exactly (the stated contract always matches the delivered one; `health_check` reports the active mode as `diskSync`). When a build file changes (`pom.xml`, `build.gradle`, ...), the server does not guess — the query fails with `RELOAD_REQUIRED` naming the file, and a verification I/O failure fails with `VERIFICATION_FAILED` rather than answering unverified.
  - **Memory implications**: one session-local stamp map of `path → (size, mtime, hash)` — roughly 300–400 bytes per source file (≈0.1 MB at 300 files, ≈10 MB at 30,000; negligible next to JDT's own model). File contents are never retained (streamed hashing, constant memory) and stamps are never persisted — every `load_project` rebuilds them, so they cannot go stale across sessions.

### Fixed

- Projects declaring a compiler level outside JDT's supported range — legacy poms with `1.5`/`1.6`/`1.7`, levels newer than this JDT knows, or unparseable values — suffered silent diagnostic blindness: JDT responds to an unsupported compliance value by producing no reconcile problems at all, so `get_diagnostics` and `diagnose_and_fix` reported a clean project regardless of real errors, while `validate_syntax` (which never consults compiler options) still caught them (#30). Declared levels are now clamped into the supported range (floor 1.8, ceiling this JDT's newest) and the clamp surfaces as a `COMPLIANCE_LEVEL_UNSUPPORTED` load warning naming both levels.

## [1.4.2] - 2026-06-10

### Added

- Whole-program reachability graph (built lazily per loaded project, cached until reload) powering three impact-analysis capabilities:
  - `find_unreachable_code`: project-wide dead code — types, methods, and fields unreachable from any `main` method or test (disabled tests still count as roots; a call through an interface or superclass reaches every override). Results mean "unreachable from declared entry points", not "safe to delete" — reflection/DI entry points are invisible to the graph.
  - `find_affected_tests`: the test methods (JUnit 4/5, TestNG) that exercise a symbol, directly or transitively through non-test helpers and interface dispatch — the set to run after changing it. Disabled covering tests are flagged.
  - `analyze_change_impact` gains `transitive=true`: the full reverse closure over the project graph (no depth ceiling, crosses override declarations) as `affectedMethods` + `affectedFiles`. The depth-mode contract is unchanged.
- `analyze_data_flow` gains opt-in interprocedural tracking (`followCalls`, bounded by `maxCallDepth`): null facts followed through argument-to-parameter hops to dereference sinks in callees, and parameter-taint facts propagated through aliases and concatenation to escape points into non-project callees. May-analysis; returned values are not tracked back.
- Framework-semantic extractors: `get_jpa_model` (entities with table names, id fields, and relationships — targets resolved through collection type arguments, mappedBy sides) and `get_http_endpoints` (route table with class-prefix/method-path composition for Spring verb shortcuts and JAX-RS). Frameworks absent from the classpath return empty results.

### Fixed

- `get_diagnostics` flagged `truncated` whenever the collected count reached `maxResults`, even when nothing was dropped. The flag now means items were actually dropped.
- `suggest_imports` overflowed its internal collection budget at `maxResults=Integer.MAX_VALUE` and silently returned nothing.

## [1.4.1] - 2026-06-09

### Added

- Six refactorings driven by JDT's own refactoring engine (via its public descriptors, running headlessly): `encapsulate_field`, `pull_up`, `push_down`, `extract_superclass`, `introduce_parameter_object`, and `move_type_to_new_file`. Refactorings that create a file return its content as `createdFiles`; everything else arrives as `editsByFile` text — nothing is written.
- `diagnose_and_fix`: the diagnose→fix loop in one call — diagnostics for a file plus each fixable problem's top quick-fix edits, combined as `editsByFile`. Read-only.
- `apply_cleanup` catalog grows from 1 to 10 clean-ups: convert_to_lambda, pattern_matching_instanceof, convert_to_switch_expression, string_concat_to_text_block, do_while_rather_than_while, invert_equals, boolean_value_rather_than_comparison, else_if, and overridden_assignment join convert_loops. The tool description lists them all.

### Changed

- Refactoring edit generation moved onto JDT's `ASTRewrite`/`ImportRewrite`: declarations, parameter lists, call-site argument lists, implements clauses, lambdas, and inline substitutions are now rendered structurally from AST modifications instead of hand-built strings and offset math. Response contracts are unchanged; `inline_method` parameter substitution is now binding-driven, so identical text inside string literals or under shadowing declarations is never touched.
- `extract_variable` now refuses extraction into an unbraced single-statement body (the old path emitted uncompilable edits there); `inline_variable` removes just the fragment when a multi-variable declaration is inlined (the old path deleted the whole statement).

## [1.4.0] - 2026-06-09

### Added

- Java 25 language support. The target platform moves to Eclipse 2025-12 (JDT 3.44), and the tools handle the new standard language features rather than merely parsing them: markdown documentation comments (`///`), module import declarations (`import module M;`), compact source files with an implicitly declared class, and flexible constructor bodies (statements before `super()`/`this()`).
- Lombok support. A Lombok agent is bundled with the distribution and attached at JVM launch (`-javaagent:lombok.jar=ECJ`), so JDT's model surfaces Lombok-generated members: `@Data` accessors appear in `get_type_members` and code calling them no longer reports false "undefined" errors. `JAVALENS_LOMBOK_JAR` overrides the bundled jar.
- `apply_cleanup` tool. Applies JDT's own clean-up operations headlessly (starting with `convert_loops`, which rewrites index/iterator `for` loops as enhanced `for` loops) and returns the rewritten source without writing the file.

### Fixed

- Reference search now finds constructor delegations (`super(...)`/`this(...)`) that are not the first statement of a constructor — the shape flexible constructor bodies introduce. JDT's indexed search misses these; `find_references`, `change_method_signature`, and the call-hierarchy tools all inherited the blind spot and now report them.
- `get_complexity_metrics` and `find_large_classes` now measure the implicitly declared class of a compact source file (previously skipped because it is not a `TypeDeclaration`); `find_large_classes` also reports its name and line span instead of an empty name and a `-1` count.
- `get_javadoc` parses markdown (`///`) documentation comments; `organize_imports`, `analyze_file`, `get_dependency_graph`, and `find_circular_dependencies` handle module imports instead of mis-deriving a package from them.
- `get_diagnostics` counts only errors and warnings; JDT info-severity problems no longer inflate the total.

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

[1.5.1]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.4.2...v1.5.0
[1.4.2]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.4.1...v1.4.2
[1.4.1]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.3.6...v1.4.0
[1.3.6]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.3.5...v1.3.6
[1.3.5]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.3.4...v1.3.5
[1.3.4]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.3.3...v1.3.4
[1.3.3]: https://github.com/pzalutski-pixel/javalens-mcp/compare/v1.3.2...v1.3.3
