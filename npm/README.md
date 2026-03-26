# javalens-mcp

MCP server for semantic Java code analysis, built on Eclipse JDT. Provides 56 tools for navigation, refactoring, and code intelligence.

## Prerequisites

- Java 21 or later

## Usage

Add to your MCP client configuration (e.g., `.mcp.json` for Claude Code):

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

The server downloads and caches the JavaLens distribution (~19 MB) on first run.

## Documentation

Full documentation, tool reference, and configuration options: [GitHub](https://github.com/pzalutski-pixel/javalens-mcp)
