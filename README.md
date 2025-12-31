# JavaLens: AI-First Code Analysis for Java

[![GitHub release](https://img.shields.io/github/v/release/pzalutski-pixel/javalens-mcp)](https://github.com/pzalutski-pixel/javalens-mcp/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

An MCP server providing 56 semantic analysis tools for Java, built directly on Eclipse JDT for compiler-accurate code understanding.

## Built for AI Agents

JavaLens exists because **AI systems need compiler-accurate insights that reading source files cannot provide**. When an AI uses `grep` or `Read` to find usages of a method, it cannot distinguish:

- A method call from a method with the same name in an unrelated class
- A field read from a field write
- An interface implementation from an unrelated class
- A cast to a type from other references to that type

This leads to incorrect refactorings, missed usages, and incomplete understanding of code behavior.

## Compiler-Accurate Analysis

JavaLens provides **compiler-accurate code analysis** through Eclipse JDT—the same engine that powers Eclipse IDE. Unlike text search, JDT understands:

- Type resolution across inheritance hierarchies
- Method overloading and overriding
- Generic type arguments
- Import resolution and classpath dependencies

**Example:** Finding all places where `UserService.save()` is called:

| Approach | Result |
|----------|--------|
| `grep "save("` | Returns 47 matches including `orderService.save()`, `saveButton`, comments |
| `find_references` | Returns exactly 12 calls to `UserService.save()` |

## AI Training Bias Warning

> ⚠️ **Important for AI developers and users**

AI models may exhibit **trained bias toward native tools** (Grep, Read, LSP) over MCP server tools, even when semantic analysis provides better results. This happens because:

1. Training data contains extensive grep/text-search patterns
2. Native tools are "always available" in the model's experience
3. The model may not recognize when semantic analysis is superior

**To get the best results:**

Add guidance to your project instructions or system prompt (e.g., `CLAUDE.md` for Claude Code):

```markdown
## Code Analysis Preferences

For Java code analysis, prefer JavaLens MCP tools over text search:
- Use `find_references` instead of grep for finding usages
- Use `find_implementations` instead of text search for implementations
- Use `analyze_type` to understand a class before modifying it
- Use refactoring tools (rename_symbol, extract_method) for safe changes

Semantic analysis from JDT is more accurate than text-based search,
especially for overloaded methods, inheritance, and generic types.
```

## What is JavaLens?

JavaLens is an MCP server that gives AI assistants deep understanding of Java codebases. It provides semantic analysis, navigation, refactoring, and code intelligence tools that go beyond simple text search.

## Why Not LSP?

Language Server Protocol was designed for IDE autocomplete and basic navigation—not for AI agent workflows that require deep semantic analysis.

| Capability | Native LSP | JavaLens |
|------------|------------|----------|
| Find all `@Annotation` usages | ❌ | ✅ |
| Find all `new Type()` instantiations | ❌ | ✅ |
| Find all casts to a type | ❌ | ✅ |
| Distinguish field reads from writes | ❌ | ✅ |
| Detect circular package dependencies | ❌ | ✅ |
| Calculate cyclomatic complexity | ❌ | ✅ |
| Find unused private methods | ❌ | ✅ |
| Detect possible null pointer bugs | ❌ | ✅ |

JavaLens wraps **Eclipse JDT Core** directly via OSGi, providing:

- **16 fine-grained reference types**: Find specifically casts, annotations, throws clauses, catch blocks, instanceof checks, method references, type arguments
- **Read vs write access distinction**: Track where fields are mutated vs just read
- **Indexed search**: 10x faster than AST walking for large codebases
- **Full AST access**: Direct manipulation for complex refactorings

## Installation

### Prerequisites

- **Java 21** or later

### Download

Download the platform-specific distribution from [Releases](https://github.com/pzalutski-pixel/javalens-mcp/releases):

| Platform | File |
|----------|------|
| Windows | `javalens-win32.win32.x86_64.zip` |
| Linux x64 | `javalens-linux.gtk.x86_64.tar.gz` |
| Linux ARM64 | `javalens-linux.gtk.aarch64.tar.gz` |
| macOS x64 | `javalens-macosx.cocoa.x86_64.tar.gz` |
| macOS ARM64 | `javalens-macosx.cocoa.aarch64.tar.gz` |

Extract to a location of your choice.

### Configure MCP Client

Add to your MCP configuration (e.g., `.mcp.json` for Claude Code):

```json
{
  "mcpServers": {
    "javalens": {
      "command": "/path/to/javalens/javalensc",
      "args": ["-data", "/path/to/javalens-workspaces"]
    }
  }
}
```

The `-data` argument specifies where JavaLens stores its workspace metadata. See [How Workspaces Work](#how-workspaces-work) below.

### Auto-Load a Project

Set `JAVA_PROJECT_PATH` to auto-load a project when the server starts:

```json
{
  "mcpServers": {
    "javalens": {
      "command": "/path/to/javalens/javalensc",
      "args": ["-data", "/path/to/javalens-workspaces"],
      "env": {
        "JAVA_PROJECT_PATH": "/path/to/your/java/project"
      }
    }
  }
}
```

## How Workspaces Work

Unlike in-memory code models, Eclipse JDT requires a **workspace directory** to store:

- Search indexes for fast symbol lookup
- Compilation state and caches
- Project metadata

### Workspaces Are Outside Your Source

JavaLens creates its workspace **outside your source project** to keep your codebase clean:

```
Your Java Project (unchanged)
├── src/main/java/
├── pom.xml
└── (no Eclipse files added)

JavaLens Workspace (specified by -data)
└── {session-uuid}/
    ├── .metadata/          <- JDT indexes and state
    └── javalens-project/   <- Links to your source (not copies)
```

**Why this matters:**

1. **No pollution**: Your source tree stays clean—no `.project` or `.classpath` files
2. **No conflicts**: Works alongside any build system without interference
3. **Session isolation**: Each MCP session gets its own workspace, enabling concurrent analysis

### Session Lifecycle

1. JavaLens starts and creates a unique workspace: `{base}/{uuid}/`
2. `load_project` creates linked folders pointing to your source
3. JDT builds indexes in the workspace (not in your project)
4. When the session ends, the workspace is cleaned up

## Tools

### Navigation (10 tools)

| Tool | Description |
|------|-------------|
| `search_symbols` | Search types, methods, fields by glob pattern |
| `go_to_definition` | Navigate to symbol definition |
| `find_references` | Find all usages of a symbol |
| `find_implementations` | Find interface/class implementations |
| `get_type_hierarchy` | Get inheritance chain |
| `get_document_symbols` | Get all symbols in a file |
| `get_symbol_info` | Get detailed symbol information at position |
| `get_type_at_position` | Get type details at cursor |
| `get_method_at_position` | Get method details at cursor |
| `get_field_at_position` | Get field details at cursor |

### Fine-Grained Reference Search (8 tools)

These use JDT's unique reference type constants—not available through LSP:

| Tool | Description |
|------|-------------|
| `find_annotation_usages` | Find all `@Annotation` usages |
| `find_type_instantiations` | Find all `new Type()` calls |
| `find_casts` | Find all `(Type) expr` casts |
| `find_instanceof_checks` | Find all `x instanceof Type` |
| `find_throws_declarations` | Find all `throws Exception` in signatures |
| `find_catch_blocks` | Find all `catch(Exception e)` blocks |
| `find_method_references` | Find all `Type::method` expressions |
| `find_type_arguments` | Find all `List<Type>` usages |

### Analysis (12 tools)

| Tool | Description |
|------|-------------|
| `get_diagnostics` | Get compilation errors and warnings |
| `validate_syntax` | Fast syntax-only validation |
| `get_call_hierarchy_incoming` | Find all callers of a method |
| `get_call_hierarchy_outgoing` | Find all methods called by a method |
| `find_field_writes` | Find where fields are mutated |
| `find_tests` | Discover JUnit/TestNG test methods |
| `find_unused_code` | Find unused private members |
| `find_possible_bugs` | Detect null risks, empty catches, resource leaks |
| `get_hover_info` | Get documentation/signature for symbol |
| `get_javadoc` | Get parsed Javadoc |
| `get_signature_help` | Get method signature at call site |
| `get_enclosing_element` | Get containing method/class at position |

### Compound Analysis (4 tools)

Combine multiple queries to reduce round-trips:

| Tool | Description |
|------|-------------|
| `analyze_file` | Get imports, types, diagnostics in one call |
| `analyze_type` | Get members, hierarchy, usages, diagnostics |
| `analyze_method` | Get signature, callers, callees, overrides |
| `get_type_usage_summary` | Get instantiations, casts, instanceof counts |

### Refactoring (10 tools)

All refactoring tools return **text edits** rather than applying changes directly:

| Tool | Description |
|------|-------------|
| `rename_symbol` | Rename across entire project |
| `organize_imports` | Sort and clean imports |
| `extract_variable` | Extract expression to local variable |
| `extract_method` | Extract code block to new method |
| `extract_constant` | Extract to `static final` field |
| `extract_interface` | Create interface from class methods |
| `inline_variable` | Replace variable with its initializer |
| `inline_method` | Replace call with method body |
| `change_method_signature` | Modify params/return, update all callers |
| `convert_anonymous_to_lambda` | Convert anonymous class to lambda |

### Quick Fixes (3 tools)

| Tool | Description |
|------|-------------|
| `suggest_imports` | Find import candidates for unresolved type |
| `get_quick_fixes` | List available fixes for problem at position |
| `apply_quick_fix` | Apply fix by ID (add import, remove import, add throws, try-catch) |

### Metrics (3 tools)

| Tool | Description |
|------|-------------|
| `get_complexity_metrics` | Cyclomatic/cognitive complexity, LOC per method |
| `get_dependency_graph` | Package/type dependencies as nodes and edges |
| `find_circular_dependencies` | Detect package cycles using Tarjan's SCC algorithm |

### Project & Infrastructure (6 tools)

| Tool | Description |
|------|-------------|
| `health_check` | Server status and capabilities |
| `load_project` | Load Maven/Gradle/plain Java project |
| `get_project_structure` | Get package hierarchy |
| `get_classpath_info` | Get classpath entries |
| `get_type_members` | Get members by type name |
| `get_super_method` | Find overridden method in superclass |

## Usage

### Basic Workflow

```
1. load_project(projectPath="/path/to/java/project")
2. search_symbols(query="*Service", kind="Class")
3. find_references(filePath="...", line=10, column=15)
4. analyze_type(typeName="com.example.UserService")
```

### Coordinate System

All line/column parameters are **zero-based**:
- Line 0, Column 0 = first character of file

### Path Handling

- Response paths are **relative** by default
- All paths use **forward slashes** for cross-platform consistency
- Input paths can be relative or absolute

## Important Notes

### No Live File Watching

JavaLens analyzes code at load time and does **not** watch for file changes. This is by design—the AI coding agent is responsible for maintaining synchronization:

| Event | Agent Action |
|-------|--------------|
| After writing/editing files | Call `load_project` to refresh indexes |
| Before complex refactoring | Call `load_project` to ensure fresh state |
| After external changes (git pull, etc.) | Call `load_project` to resync |

**Why not automatic watching?**

1. AI agents make discrete edits with clear boundaries—auto-sync adds complexity without benefit
2. The agent controls when analysis should reflect changes
3. Avoids race conditions between file writes and index updates

**Recommended pattern:**
```
1. Use JavaLens tools to analyze
2. Write changes to files
3. Call load_project to refresh
4. Use JavaLens tools to verify changes
```

### Refactoring Returns Edits

Refactoring tools return text edits but don't modify files. This gives visibility into what would change before applying.

### Session Isolation

Each MCP session is independent with its own workspace UUID. Multiple sessions can analyze the same project concurrently.

### Build System Support

| System | Detection |
|--------|-----------|
| Maven | `pom.xml` |
| Gradle | `build.gradle` / `build.gradle.kts` |
| Plain Java | `src/` directory |

## Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `JAVA_PROJECT_PATH` | Auto-load project on startup | (none) |
| `JAVALENS_TIMEOUT_SECONDS` | Operation timeout | 30 |
| `JAVALENS_LOG_LEVEL` | TRACE/DEBUG/INFO/WARN/ERROR | INFO |

## Building from Source

```bash
git clone https://github.com/pzalutski-pixel/javalens-mcp.git
cd javalens-mcp
./mvnw clean verify
```

Distributions are output to `org.javalens.product/target/products/`.

### Requirements

- Java 21+
- Maven 3.9+ (wrapper included)

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                      MCP Client                          │
└─────────────────────────────────────────────────────────┘
                            │ JSON-RPC over stdio
┌─────────────────────────────────────────────────────────┐
│  org.javalens.mcp                                        │
│    McpProtocolHandler → ToolRegistry → 56 Tools          │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│  org.javalens.core                                       │
│    JdtServiceImpl → WorkspaceManager, SearchService      │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│  Eclipse JDT Core (via OSGi/Equinox)                     │
│    IWorkspace, IJavaProject, SearchEngine, ASTParser     │
└─────────────────────────────────────────────────────────┘
```

## License

MIT License - see [LICENSE](LICENSE) for details.
