#!/bin/bash
# Start the Field4 MCP Server
# This server exposes Field4's AI tools via Model Context Protocol

echo "Starting Field4 MCP Server..."
echo "Server will be available at http://127.0.0.1:3000/mcp"
echo ""
echo "Press Ctrl+C to stop the server"
echo ""

# Run the MCP server using Gradle
./gradlew runmcp
