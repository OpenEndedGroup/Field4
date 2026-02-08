# Field4 MCP Server

The Field4 MCP (Model Context Protocol) Server exposes Field4's AI4 tools to external AI assistants like Claude, Codex, and other AI tools that support the MCP protocol.

## What is MCP?

Model Context Protocol (MCP) is a standardized protocol for exposing tools and context to AI assistants. It allows AI models to interact with external systems in a structured way through JSON-RPC 2.0 over HTTP with Server-Sent Events (SSE).

## Available Tools

The MCP server exposes the following AI4 tools:

### 1. **commentary**
Assistant commentary or design notes about the code.
- **Parameters**: `text` (string) - Commentary text

### 2. **list_attachments**
List all attached context files available for this request.
- **Parameters**: None

### 3. **get_attachment**
Retrieve the code content of an attached file by its name.
- **Parameters**: `file` (string) - File name to retrieve

### 4. **list_definitions**
List all definitions visible from the current Kotlin file, including associated shaders.
- **Parameters**: None

### 5. **list_contents**
Return full contents for available text properties (code and shaders) with file refs and languages.
- **Parameters**: None

### 6. **edit_file**
Edit another file by specifying its name and new content.
- **Parameters**: 
  - `file` (string) - File name to edit
  - `newContent` (string) - New content for the file

### 7. **workspace_symbol_search**
Looks up symbols in the IDE workspace index.
- **Parameters**: 
  - `query` (string) - Search query
  - `limit` (integer, optional) - Maximum number of results (default: 25)

## Running the MCP Server

### Using Gradle

The easiest way to run the MCP server is using the Gradle task:

```bash
./gradlew runmcp
```

This will start the MCP server on `http://127.0.0.1:3000/mcp`

### From Source

You can also run the server directly from the Kotlin code:

```kotlin
import afield.ai.mcp.McpServer

fun main() {
    McpServer(
        host = "127.0.0.1",
        port = 3000
    ).start(wait = true)
}
```

## Configuration

The MCP server can be configured with the following parameters:

- **host**: The host address to bind to (default: `127.0.0.1`)
- **port**: The port number to listen on (default: `3000`)
- **toolContext**: A `ToolExecutionContext` that provides the actual implementations for the tools

## Protocol Endpoints

The MCP server exposes two endpoints under `/mcp`:

### GET /mcp
Returns a Server-Sent Events (SSE) stream with heartbeat messages to keep the connection alive.

### POST /mcp
Accepts JSON-RPC 2.0 requests and returns responses as SSE events. Supports batch requests.

## Supported Methods

### initialize
Initialize the MCP session and retrieve server capabilities.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {}
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-03-26",
    "serverInfo": {
      "name": "field4-mcp",
      "version": "1.0.0"
    },
    "capabilities": {
      "tools": {
        "listChanged": false
      }
    },
    "instructions": "Field4 AI tools: access to attachments, definitions, file contents, and workspace search."
  }
}
```

### tools/list
List all available tools.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "commentary",
        "description": "Assistant commentary or design notes about the code.",
        "inputSchema": { ... }
      },
      ...
    ]
  }
}
```

### tools/call
Execute a tool.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "list_attachments",
    "arguments": {}
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Available attachments:\nfile1.kt (Kotlin)\nfile2.glsl (GLSL)"
      }
    ],
    "isError": false
  }
}
```

## Connecting External AI Tools

### Claude Desktop

To connect Claude Desktop to the Field4 MCP server, add the following configuration to your Claude Desktop config file:

**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "field4": {
      "url": "http://127.0.0.1:3000/mcp"
    }
  }
}
```

### Custom Integration

To integrate with other AI tools, configure them to connect to the MCP endpoint at `http://127.0.0.1:3000/mcp` using the Streamable HTTP transport (SSE).

## Security Considerations

- The MCP server only accepts connections from `localhost` or `127.0.0.1` by default
- The server is intended for local development use only
- For production deployments, additional authentication and encryption should be added
- Never expose the MCP server directly to the internet without proper security measures

## Development

### Adding New Tools

To add a new tool to the MCP server:

1. Add the tool definition to `buildToolDefinitions()` in `McpServer.kt`
2. Implement the tool execution logic in `executeTool()` 
3. If the tool requires new capabilities, update the `ToolExecutionContext` in `Tools.kt`

### Testing

You can test the MCP server using curl:

```bash
# Initialize
curl -X POST http://127.0.0.1:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# List tools
curl -X POST http://127.0.0.1:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# Call a tool
curl -X POST http://127.0.0.1:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_attachments","arguments":{}}}'
```

## Troubleshooting

### Server won't start
- Check that port 3000 is not already in use
- Ensure Java 24+ is installed and available
- Check for any error messages in the console

### Tools return "provider not available"
- Make sure the `ToolExecutionContext` is properly configured with the necessary providers
- Check that the Field4 session is properly initialized

### Connection refused from AI tool
- Verify the MCP server is running
- Check that the URL in your AI tool configuration is correct
- Ensure no firewall is blocking localhost connections

## References

- [Model Context Protocol Specification](https://spec.modelcontextprotocol.io/)
- [Field4 AI4 Tools](../ai/core/Tools.kt)
- [OpenAI Tools Documentation](https://platform.openai.com/docs/guides/function-calling)
