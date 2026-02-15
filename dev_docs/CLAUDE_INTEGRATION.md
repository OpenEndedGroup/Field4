# Claude Desktop Integration Example

This document shows how to integrate the Field4 MCP Server with Claude Desktop.

## Configuration

### Step 1: Start the Field4 MCP Server

Make sure the Field4 MCP server is running:

```bash
cd /path/to/Field4
./mcp-server.sh
```

The server should start and display:
```
Starting Field4 MCP Server on http://127.0.0.1:3000/mcp
This server exposes Field4's AI tools via Model Context Protocol
Press Ctrl+C to stop
```

### Step 2: Configure Claude Desktop

Edit the Claude Desktop configuration file:

**macOS:**
```bash
nano ~/Library/Application\ Support/Claude/claude_desktop_config.json
```

**Windows:**
```powershell
notepad %APPDATA%\Claude\claude_desktop_config.json
```

**Linux:**
```bash
nano ~/.config/Claude/claude_desktop_config.json
```

### Step 3: Add Field4 MCP Server Configuration

Add the following configuration to the `mcpServers` section:

```json
{
  "mcpServers": {
    "field4": {
      "url": "http://127.0.0.1:3000/mcp",
      "transport": "sse"
    }
  }
}
```

If you already have other MCP servers configured, add the `field4` entry alongside them:

```json
{
  "mcpServers": {
    "existing-server": {
      "url": "http://localhost:8000/mcp"
    },
    "field4": {
      "url": "http://127.0.0.1:3000/mcp",
      "transport": "sse"
    }
  }
}
```

### Step 4: Restart Claude Desktop

Close and reopen Claude Desktop to load the new configuration.

### Step 5: Verify Connection

In Claude Desktop, you should see Field4 tools available. You can verify by asking:

> "What tools do you have available from Field4?"

Claude should respond with a list of available tools:
- commentary
- list_attachments
- get_attachment
- list_definitions
- list_contents
- edit_file
- workspace_symbol_search

## Example Usage

### Example 1: Using Commentary Tool

**You:** "Add a commentary note that we need to refactor the shader code"

**Claude:** *Uses the commentary tool to record the note*

### Example 2: Listing Code Definitions

**You:** "Show me all the Kotlin definitions available in the current file"

**Claude:** *Uses list_definitions tool to retrieve and display definitions*

### Example 3: Searching Workspace Symbols

**You:** "Search for all functions related to 'graphics' in the workspace"

**Claude:** *Uses workspace_symbol_search tool to find matching symbols*

### Example 4: Getting Attachment Content

**You:** "Show me the contents of shader.glsl"

**Claude:** *Uses get_attachment tool to retrieve the file contents*

## Troubleshooting

### Claude Desktop doesn't see the tools

1. Verify the MCP server is running:
   ```bash
   curl http://127.0.0.1:3000/mcp
   ```

2. Check the Claude Desktop logs:
   - macOS: `~/Library/Logs/Claude/`
   - Windows: `%APPDATA%\Claude\logs\`
   - Linux: `~/.config/Claude/logs/`

3. Verify the configuration file syntax is correct (valid JSON)

### Connection refused error

1. Make sure the MCP server is actually running
2. Check that no firewall is blocking localhost connections
3. Verify the port 3000 is not in use by another application

### Tools return "provider not available"

This means the tool was called but the underlying implementation wasn't configured. For the Field4 MCP server, this typically means:
- The server needs to be integrated with the actual Field4 session
- The ToolExecutionContext needs to be properly configured with providers

## Advanced Configuration

### Using a Different Port

If port 3000 is already in use, you can configure the server to use a different port:

1. Modify the McpServer instantiation in your code:
   ```kotlin
   McpServer(
       host = "127.0.0.1",
       port = 3001  // Use a different port
   ).start(wait = true)
   ```

2. Update your Claude Desktop configuration:
   ```json
   {
     "mcpServers": {
       "field4": {
         "url": "http://127.0.0.1:3001/mcp"
       }
     }
   }
   ```

### Configuring Tool Context

To provide actual implementations for the tools, configure the ToolExecutionContext:

```kotlin
val toolContext = ToolExecutionContext(
    attachments = listOf(/* your attachments */),
    resolveAttachment = { fileName -> /* resolve logic */ },
    listDefinitions = { /* list definitions logic */ },
    listFileContents = { /* list contents logic */ },
    editOtherFile = { request -> /* edit file logic */ },
    workspaceSymbolSearch = { query -> /* search logic */ }
)

McpServer(
    host = "127.0.0.1",
    port = 3000,
    toolContext = toolContext
).start(wait = true)
```

## Security Notes

- The MCP server only accepts connections from localhost by default
- Do not expose the server to the internet without adding authentication
- The server is designed for local development use only

## Further Reading

- [MCP Server Documentation](MCP_SERVER.md)
- [Model Context Protocol Specification](https://spec.modelcontextprotocol.io/)
- [Claude Desktop Documentation](https://claude.ai/desktop)
