# Field4 - Live Coding Environment

Field4 is a sophisticated live coding environment that combines high-performance graphics, rich scripting support, and deep AI integration for rapid prototyping and visual experimentation.

## Quick Start

### Running Field4

```bash
./gradlew jvmRun
```

This will start the Field4 application with the browser UI.

### Running the MCP Server

Field4 includes a Model Context Protocol (MCP) server that exposes AI tools to external AI assistants:

```bash
# Unix/macOS/Linux
./mcp-server.sh

# Windows
mcp-server.bat

# Or use Gradle directly
./gradlew runmcp
```

The MCP server will be available at `http://127.0.0.1:3000/mcp`

See [dev_docs/MCP_SERVER.md](dev_docs/MCP_SERVER.md) for complete MCP server documentation.

## Features

- **Live Coding**: Real-time code execution and visualization
- **Multi-Language Support**: Kotlin (primary), Python, Clojure, and JavaScript
- **High-Performance Graphics**: Vulkan-based graphics system
- **AI Integration**: Deep integration with AI tools for code completion and generation
- **Visual Programming**: Box-based visual programming interface with Monaco editor
- **MCP Server**: Expose AI tools to external assistants like Claude and Codex

## Building

```bash
./gradlew build
```

## Running Other Tools

```bash
# AI tools
./gradlew runai

# AI filestore CLI
./gradlew runfilestore
```

## Documentation

- [MCP Server Documentation](dev_docs/MCP_SERVER.md) - Model Context Protocol server for AI tools
- [Metadata Server](dev_docs/README_METADATA_SERVER.md) - Template metadata server
- [Slideshow System](dev_docs/README_SLIDESHOW.md) - Interactive slideshow system
- [KDoc Implementation](KDOC_IMPLEMENTATION.md) - Documentation system
- [Dokka Quickstart](DOKKA_QUICKSTART.md) - API documentation generation

## Requirements

- Java 24+
- Gradle (included via wrapper)

## License

See LICENSE file for details.
