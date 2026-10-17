# javalens-mcp

[![GitHub release](https://img.shields.io/github/v/release/pzalutski-pixel/javalens-mcp)](https://github.com/pzalutski-pixel/javalens-mcp/releases)
[![npm](https://img.shields.io/npm/v/javalens-mcp.svg)](https://www.npmjs.com/package/javalens-mcp)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://github.com/pzalutski-pixel/javalens-mcp/blob/master/LICENSE)

An MCP server providing **75 semantic analysis tools** for Java, built directly on Eclipse JDT for compiler-accurate code understanding.

## Requirements

- **Java 21 or later** — required as the server runtime, analyzes Java source from version 1.1 through 25 (markdown Javadoc, module imports, compact source files, flexible constructor bodies)
- **Node.js 18+** — required to run this package via `npx`. If you don't have Node.js, download JavaLens directly from [GitHub Releases](https://github.com/pzalutski-pixel/javalens-mcp/releases) instead.

**Lombok** is supported out of the box: a bundled agent makes Lombok-generated members resolve, so `@Data` accessors appear in the model and code using them is not flagged as undefined. Set `JAVALENS_LOMBOK_JAR` to override the bundled agent jar.

**Disk synchronization**: every answer is verified against the files on disk at query time — no reload is needed after editing, adding, or deleting source files (the agent's loop is just edit → query). Verification is hash-based and parallel (~2 ms at 72 files, ~25 ms at 1,000, ~180 ms at 10,000 per query); repairs cost only what changed. `load_project` remains for first use, `RELOAD_REQUIRED` responses (build-file changes), and full resets. Set `JAVALENS_DISK_SYNC=manual` for the pre-1.5.0 contract where the agent reloads after edits.

## Quick Start

```json
{
  "mcpServers": {
    "javalens": {
      "command": "npx",
      "args": ["-y", "javalens-mcp"],
      "env": {
        "JAVA_PROJECT_PATH": "/path/to/your/java/project"
      }
    }
  }
}
```

## What This Package Does

This npm package bundles the full JavaLens distribution (~23 MB). No runtime downloads, no network dependency after install. It:

1. Checks that Java 21+ is installed
2. Reads JVM configuration from the bundled `eclipse.ini`
3. Launches the JavaLens MCP server with stdio for protocol communication

## Why JavaLens?

AI systems need compiler-accurate insights that reading source files cannot provide. When an AI uses `grep` to find usages of a method, it cannot distinguish a method call from a method with the same name in an unrelated class, a field read from a field write, or an interface implementation from an unrelated class.

| Approach | Result |
|----------|--------|
| `grep "save("` | 47 matches including `orderService.save()`, `saveButton`, comments |
| `find_references` | Exactly 12 calls to `UserService.save()` |

## Features

### Navigation (10 tools)
Search symbols, go to definition, find references, find implementations, type hierarchy, document symbols, and positional queries.

### Fine-Grained Reference Search (9 tools)
JDT-unique capabilities not available through LSP: find annotation usages, type instantiations, casts, instanceof checks, throws declarations, catch blocks, method references, type arguments, and reflection usage detection.

### Analysis (20 tools)
Diagnostics, syntax validation, call hierarchy (incoming/outgoing), field write tracking, test discovery, unused code detection, project-wide unreachable-code detection over the whole-program call graph, affected-test discovery (which tests exercise a symbol, transitively), possible bug detection, change impact analysis (with a full transitive mode), data flow analysis (with opt-in cross-method null/taint tracking), control flow analysis, Spring DI registration scanning, JPA entity-model assembly, and HTTP route-table assembly (Spring and JAX-RS).

### Compound Analysis (4 tools)
Combine multiple queries to reduce round-trips: analyze file, analyze type, analyze method, and type usage summary.

### Refactoring (16 tools)
All return text edits (and new-file content where a refactoring creates one) rather than modifying files directly: rename, organize imports, extract variable/method/constant/interface/superclass, inline variable/method, change method signature, convert anonymous to lambda, encapsulate field, pull up / push down members, introduce parameter object, and move a nested type to its own file.

### Quick Fixes & Metrics (10 tools)
Import suggestions, quick fixes, 10 JDT clean-ups (convert loops, pattern matching, switch expressions, text blocks, ...), one-call diagnose-and-fix, cyclomatic complexity, dependency graphs, circular dependency detection, large class detection, and naming convention violations.

### Project & Infrastructure (6 tools)
Health check, project loading, project structure, classpath info, type member lookup by name, and superclass method resolution.

## Build System Support

Single-module and multi-module projects load end-to-end across Maven, Gradle, and Bazel.

| System | Detection | Multi-module | Compiler compliance | Generated sources | Annotation processors |
|--------|-----------|:-:|:-:|:-:|:-:|
| Maven | `pom.xml` | ✅ | ✅ | ✅ | ✅ |
| Gradle | `settings.gradle(.kts)` / `build.gradle(.kts)` | ✅ | ✅ | ✅ | ✅ |
| Bazel | `MODULE.bazel` / `WORKSPACE.bazel` / `WORKSPACE` | ✅ | ✅ | n/a | ✅ |
| Plain Java | `src/` directory | n/a | ✅ | n/a | n/a |

Maven, Gradle and Bazel projects are read from the build tool's own model (modules, source roots, classpath, levels, annotation processors), with nothing written into the project tree. When the tool cannot run, the build files are read as code, comments ignored, and `load_project` and `health_check` say so (`buildModel.source = static-read`, `BUILD_MODEL_STATIC`), along with every other structured warning (e.g. `BUILD_TOOL_UNAVAILABLE`, `BUILD_TOOL_FAILED`). A change to any file the build reads makes the next query report `RELOAD_REQUIRED`.

## Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `JAVA_PROJECT_PATH` | Auto-load project on startup | (none) |
| `JAVALENS_TIMEOUT_SECONDS` | Time limit on each tool call, 5 to 300 seconds; a call past it returns `TIMEOUT` | (none: no limit) |
| `JAVALENS_DISK_SYNC` | `strict` (answers verified against disk per query) or `manual` (reload after edits) | strict |
| `JAVA_TOOL_OPTIONS` | JVM options, e.g. `-Xmx2g` for large projects | 512m |
| `JAVALENS_LOG_LEVEL` | TRACE/DEBUG/INFO/WARN/ERROR; logs go to stderr | INFO |
| `JAVALENS_LOMBOK_JAR` | Path to the Lombok agent jar attached at launch; overrides the bundled one | (bundled) |
| `JAVALENS_MAVEN_ARGS` / `JAVALENS_GRADLE_ARGS` / `JAVALENS_BAZEL_ARGS` | Extra arguments for the build tool when it reads the model (profiles, properties, flags) | (none) |

## Documentation

Full documentation, tool reference, and architecture details: [GitHub](https://github.com/pzalutski-pixel/javalens-mcp)
