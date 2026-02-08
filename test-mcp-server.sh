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

# Test 5: Call check_code tool (Kotlin - valid)
echo "Test 5: Check Kotlin code (valid)"
echo "-----------------------------------"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"check_code","arguments":{"code":"val x = 42\nprintln(x)","language":"Kotlin"}}}' | \
  python3 -m json.tool 2>/dev/null || echo "Server not responding"
echo ""

# Test 6: Call check_code tool (Kotlin - invalid)
echo "Test 6: Check Kotlin code (with error)"
echo "----------------------------------------"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"check_code","arguments":{"code":"val x = \nprintln(y)","language":"Kotlin"}}}' | \
  python3 -m json.tool 2>/dev/null || echo "Server not responding"
echo ""

# Test 7: Call check_code tool (Python - valid)
echo "Test 7: Check Python code (valid)"
echo "-----------------------------------"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"check_code","arguments":{"code":"x = 42\nprint(x)","language":"Python"}}}' | \
  python3 -m json.tool 2>/dev/null || echo "Server not responding"
echo ""

# Test 8: Call check_code tool (Python - invalid)
echo "Test 8: Check Python code (with error)"
echo "----------------------------------------"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"check_code","arguments":{"code":"def foo(\nprint(x)","language":"Python"}}}' | \
  python3 -m json.tool 2>/dev/null || echo "Server not responding"
echo ""

echo "==============================================="
echo "Tests completed!"
echo "==============================================="
