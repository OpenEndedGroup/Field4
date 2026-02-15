# Field4 MCP Server Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         External AI Tools                        │
│                  (Claude Desktop, Codex, etc.)                   │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ HTTP/SSE
                             │ JSON-RPC 2.0
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                    Field4 MCP Server                             │
│                   (http://127.0.0.1:3000/mcp)                   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Protocol Layer                               │  │
│  │  • JSON-RPC 2.0 Request/Response                         │  │
│  │  • Server-Sent Events (SSE) Streaming                    │  │
│  │  • Heartbeat & Progress Notifications                    │  │
│  └──────────────────────────────────────────────────────────┘  │
│                            │                                     │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              MCP Methods                                  │  │
│  │  • initialize    - Server capabilities                   │  │
│  │  • tools/list    - Available tools                       │  │
│  │  • tools/call    - Execute tools                         │  │
│  └──────────────────────────────────────────────────────────┘  │
│                            │                                     │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Tool Execution Layer                         │  │
│  │  • commentary            - Design notes                   │  │
│  │  • list_attachments      - List context files            │  │
│  │  • get_attachment        - Retrieve file contents        │  │
│  │  • list_definitions      - Kotlin definitions            │  │
│  │  • list_contents         - All text properties           │  │
│  │  • edit_file             - File editing                   │  │
│  │  • workspace_symbol_search - Symbol lookup               │  │
│  └──────────────────────────────────────────────────────────┘  │
│                            │                                     │
└────────────────────────────┼────────────────────────────────────┘
                             │
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                   ToolExecutionContext                           │
│                                                                  │
│  • attachments              - Context files                     │
│  • resolveAttachment        - File resolver                     │
│  • listDefinitions          - Kotlin definitions provider       │
│  • listFileContents         - Contents provider                 │
│  • editOtherFile            - File editor                       │
│  • workspaceSymbolSearch    - Symbol search provider            │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                     Field4 Core Systems                          │
│                                                                  │
│  • Box hierarchy and code management                            │
│  • Kotlin/Python/Clojure execution                             │
│  • Graphics rendering (Vulkan/OpenGL)                           │
│  • Monaco editor integration                                    │
│  • AI4 tools and completion                                     │
└──────────────────────────────────────────────────────────────────┘
```

## Request Flow

### 1. Initialize Request

```
AI Tool → MCP Server: POST /mcp
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize"
}

MCP Server → AI Tool: SSE Response
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
      "tools": { "listChanged": false }
    }
  }
}
```

### 2. List Tools Request

```
AI Tool → MCP Server: POST /mcp
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list"
}

MCP Server → AI Tool: SSE Response
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "list_attachments",
        "description": "List all attached context files",
        "inputSchema": { ... }
      },
      ...
    ]
  }
}
```

### 3. Tool Execution Request

```
AI Tool → MCP Server: POST /mcp
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "list_attachments",
    "arguments": {}
  }
}

MCP Server → ToolExecutionContext: Execute tool
ToolExecutionContext → Field4 Core: Get attachments
Field4 Core → ToolExecutionContext: Return data
ToolExecutionContext → MCP Server: Tool result

MCP Server → AI Tool: SSE Response
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Available attachments:\nfile1.kt (Kotlin)"
      }
    ],
    "isError": false
  }
}
```

## Component Details

### McpServer Class

**Location:** `src/jvmMain/kotlin/afield/ai/mcp/McpServer.kt`

**Responsibilities:**
- HTTP server using Ktor/Netty
- JSON-RPC 2.0 protocol handling
- SSE streaming for responses
- Tool definition generation
- Tool execution orchestration

**Key Methods:**
- `start(wait: Boolean)` - Start the server
- `buildToolDefinitions()` - Generate MCP tool definitions
- `executeTool(name, args)` - Execute a tool and return results

### ToolExecutionContext

**Location:** `src/jvmMain/kotlin/afield/ai/core/Tools.kt`

**Responsibilities:**
- Provide implementations for tools
- Bridge between MCP server and Field4 core
- Context management for tool execution

**Properties:**
- `attachments` - Available context files
- `resolveAttachment` - Function to retrieve file contents
- `listDefinitions` - Function to list Kotlin definitions
- `listFileContents` - Function to list all file contents
- `editOtherFile` - Function to edit files
- `workspaceSymbolSearch` - Function to search symbols

## Protocol Details

### Transport: Server-Sent Events (SSE)

The MCP server uses SSE for streaming responses. Each response is sent as an SSE event:

```
data: {"jsonrpc":"2.0","id":1,"result":{...}}

```

### Heartbeat

The server sends periodic heartbeat events on GET /mcp:

```
data: {"jsonrpc":"2.0","method":"notifications/heartbeat","params":{"seq":0}}

data: {"jsonrpc":"2.0","method":"notifications/heartbeat","params":{"seq":1}}

...
```

### Progress Notifications

For each request, the server sends progress notifications:

```
// Start
data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"forRequestId":1,"stage":"started"}}

// Actual response
data: {"jsonrpc":"2.0","id":1,"result":{...}}

// Finish
data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"forRequestId":1,"stage":"finished"}}

```

## Security Considerations

### Origin Checking

The server validates request origins:
```kotlin
private fun isAllowedOrigin(origin: String?): Boolean {
    if (origin == null) return true
    val o = origin.lowercase()
    return o.startsWith("http://localhost") || 
           o.startsWith("http://127.0.0.1")
}
```

### CORS Configuration

CORS is configured to allow all headers and methods but only from allowed origins.

### Network Binding

The server binds to 127.0.0.1 (localhost) by default, preventing external access.

## Extension Points

### Adding New Tools

1. Add tool definition in `buildToolDefinitions()`:
```kotlin
ToolDef(
    name = "my_new_tool",
    description = "Description",
    inputSchema = buildJsonObject { ... }
)
```

2. Add execution logic in `executeTool()`:
```kotlin
"my_new_tool" -> {
    // Implementation
    ToolCallResult(content = listOf(ToolText(text = result)))
}
```

### Custom Tool Context

Provide custom implementations via ToolExecutionContext:
```kotlin
val customContext = ToolExecutionContext(
    listDefinitions = { /* custom logic */ },
    workspaceSymbolSearch = { /* custom logic */ }
)

McpServer(toolContext = customContext).start()
```

## Performance Considerations

- **Async I/O**: Uses Kotlin coroutines for non-blocking operations
- **SSE Streaming**: Reduces latency for long-running operations
- **Connection Keep-Alive**: Heartbeats maintain persistent connections
- **Tool Caching**: Consider caching frequently accessed data in ToolExecutionContext

## Monitoring and Debugging

### Server Logs

The server logs to stdout:
```
Starting Field4 MCP Server on http://127.0.0.1:3000/mcp
This server exposes Field4's AI tools via Model Context Protocol
Press Ctrl+C to stop
```

### Request Inspection

Enable verbose logging by checking the Ktor logs.

### Testing Tools

Use the provided test scripts:
```bash
./test-mcp-server.sh       # Quick curl-based tests
python3 test-mcp-server.py # Comprehensive test suite
```
