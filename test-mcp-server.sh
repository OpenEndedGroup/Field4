#!/bin/bash
# Simple test script for Field4 MCP Server using curl

MCP_URL="http://127.0.0.1:3000/mcp"

echo "==============================================="
echo "Field4 MCP Server Test (curl)"
echo "==============================================="
echo "Server URL: $MCP_URL"
echo ""

# Test 1: Initialize
echo "Test 1: Initialize"
echo "-------------------"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | \
  python3 -m json.tool 2>/dev/null || echo "Server not responding"
echo ""

# Test 2: List Tools
echo "Test 2: List Tools"
echo "-------------------"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | \
  python3 -m json.tool 2>/dev/null || echo "Server not responding"
echo ""

# Test 3: Call list_attachments tool
echo "Test 3: Call list_attachments"
echo "-------------------------------"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_attachments","arguments":{}}}' | \
  python3 -m json.tool 2>/dev/null || echo "Server not responding"
echo ""

# Test 4: Call commentary tool
echo "Test 4: Call commentary"
echo "------------------------"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"commentary","arguments":{"text":"Test commentary"}}}' | \
  python3 -m json.tool 2>/dev/null || echo "Server not responding"
echo ""

echo "==============================================="
echo "Tests completed!"
echo "==============================================="
