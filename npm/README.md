# javalens-mcp

[![GitHub release](https://img.shields.io/github/v/release/pzalutski-pixel/javalens-mcp)](https://github.com/pzalutski-pixel/javalens-mcp/releases)
[![npm](https://img.shields.io/npm/v/javalens-mcp.svg)](https://www.npmjs.com/package/javalens-mcp)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://github.com/pzalutski-pixel/javalens-mcp/blob/master/LICENSE)

An MCP server providing **64 semantic analysis tools** for Java, built directly on Eclipse JDT for compiler-accurate code understanding.

## Requirements

- **Java 21 or later** — required as the server runtime, analyzes Java source from version 1.1 through 25 (markdown Javadoc, module imports, compact source files, flexible constructor bodies)
- **Node.js 18+** — required to run this package via `npx`. If you don't have Node.js, download JavaLens directly from [GitHub Releases](https://github.com/pzalutski-pixel/javalens-mcp/releases) instead.

**Lombok** is supported out of the box: a bundled agent makes Lombok-generated members resolve, so `@Data` accessors appear in the model and code using them is not flagged as undefined. Set `JAVALENS_LOMBOK_JAR` to override the bundled agent jar.

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

### Analysis (16 tools)
Diagnostics, syntax validation, call hierarchy (incoming/outgoing), field write tracking, test discovery, unused code detection, possible bug detection, change impact analysis, data flow analysis, control flow analysis, and Spring DI registration scanning.

### Compound Analysis (4 tools)
Combine multiple queries to reduce round-trips: analyze file, analyze type, analyze method, and type usage summary.

### Refactoring (10 tools)
All return text edits rather than modifying files directly: rename, organize imports, extract variable/method/constant/interface, inline variable/method, change method signature, and convert anonymous to lambda.

### Quick Fixes & Metrics (9 tools)
Import suggestions, quick fixes, JDT clean-ups (e.g. convert loops to enhanced for), cyclomatic complexity, dependency graphs, circular dependency detection, large class detection, and naming convention violations.

### Project & Infrastructure (6 tools)
Health check, project loading, project structure, classpath info, type member lookup by name, and superclass method resolution.

## Build System Support

Single-module and multi-module projects load end-to-end across Maven, Gradle, and Bazel.

| System | Detection | Multi-module | Compiler compliance | Generated sources | Annotation processors |
|--------|-----------|:-:|:-:|:-:|:-:|
| Maven | `pom.xml` | ✅ | ✅ | ✅ | ✅ |
| Gradle | `build.gradle` / `build.gradle.kts` | ✅ | ✅ | ✅ | ✅ |
| Bazel | `MODULE.bazel` / `WORKSPACE.bazel` / `WORKSPACE` | ✅ | ✅ | n/a | ✅ |
| Plain Java | `src/` directory | n/a | ✅ | n/a | n/a |

If `mvn` / `gradle` is missing or the subprocess fails, JavaLens reports a structured warning (e.g. `MAVEN_SUBPROCESS_FAILED`) in the `load_project` response so callers know the classpath is degraded.

## Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `JAVA_PROJECT_PATH` | Auto-load project on startup | (none) |
| `JAVALENS_TIMEOUT_SECONDS` | Operation timeout | 30 |
| `JAVA_TOOL_OPTIONS` | JVM options, e.g. `-Xmx2g` for large projects | 512m |
| `JAVALENS_LOG_LEVEL` | TRACE/DEBUG/INFO/WARN/ERROR | INFO |
| `JAVALENS_LOMBOK_JAR` | Path to the Lombok agent jar attached at launch; overrides the bundled one | (bundled) |

## Documentation

Full documentation, tool reference, and architecture details: [GitHub](https://github.com/pzalutski-pixel/javalens-mcp)
