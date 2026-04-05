# javalens-mcp

[![GitHub release](https://img.shields.io/github/v/release/pzalutski-pixel/javalens-mcp)](https://github.com/pzalutski-pixel/javalens-mcp/releases)
[![npm](https://img.shields.io/npm/v/javalens-mcp.svg)](https://www.npmjs.com/package/javalens-mcp)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://github.com/pzalutski-pixel/javalens-mcp/blob/master/LICENSE)

An MCP server providing **56 semantic analysis tools** for Java, built directly on Eclipse JDT for compiler-accurate code understanding.

## Requirements

- **Java 21 or later** — required as the server runtime, analyzes Java source from version 1.1 through 23

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

This npm package bundles the full JavaLens distribution (~19 MB). No runtime downloads, no network dependency after install. It:

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

### Fine-Grained Reference Search (8 tools)
JDT-unique capabilities not available through LSP: find annotation usages, type instantiations, casts, instanceof checks, throws declarations, catch blocks, method references, and type arguments.

### Analysis (12 tools)
Diagnostics, syntax validation, call hierarchy (incoming/outgoing), field write tracking, test discovery, unused code detection, and possible bug detection (null risks, empty catches, resource leaks).

### Compound Analysis (4 tools)
Combine multiple queries to reduce round-trips: analyze file, analyze type, analyze method, and type usage summary.

### Refactoring (10 tools)
All return text edits rather than modifying files directly: rename, organize imports, extract variable/method/constant/interface, inline variable/method, change method signature, and convert anonymous to lambda.

### Quick Fixes & Metrics (6 tools)
Import suggestions, quick fixes, cyclomatic complexity, dependency graphs, and circular dependency detection.

## Build System Support

| System | Detection |
|--------|-----------|
| Maven | `pom.xml` |
| Gradle | `build.gradle` / `build.gradle.kts` |
| Bazel | `MODULE.bazel` / `WORKSPACE.bazel` / `WORKSPACE` |
| Plain Java | `src/` directory |

## Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `JAVA_PROJECT_PATH` | Auto-load project on startup | (none) |
| `JAVALENS_TIMEOUT_SECONDS` | Operation timeout | 30 |
| `JAVA_TOOL_OPTIONS` | JVM options, e.g. `-Xmx2g` for large projects | 512m |
| `JAVALENS_LOG_LEVEL` | TRACE/DEBUG/INFO/WARN/ERROR | INFO |

## Documentation

Full documentation, tool reference, and architecture details: [GitHub](https://github.com/pzalutski-pixel/javalens-mcp)
