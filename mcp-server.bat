@echo off
REM Start the Field4 MCP Server
REM This server exposes Field4's AI tools via Model Context Protocol

echo Starting Field4 MCP Server...
echo Server will be available at http://127.0.0.1:3000/mcp
echo.
echo Press Ctrl+C to stop the server
echo.

REM Run the MCP server using Gradle
gradlew.bat runmcp
