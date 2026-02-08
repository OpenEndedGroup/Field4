#!/usr/bin/env python3
"""
Test client for Field4 MCP Server.
This script tests the MCP server endpoints to verify the protocol implementation.
"""

import json
import sys
import requests
from typing import Dict, Any

MCP_URL = "http://127.0.0.1:3000/mcp"

def make_request(method: str, params: Dict[str, Any] = None, request_id: int = 1) -> Dict[str, Any]:
    """Make a JSON-RPC request to the MCP server."""
    request = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": method,
    }
    if params is not None:
        request["params"] = params
    
    print(f"\n{'='*60}")
    print(f"REQUEST: {method}")
    print(f"{'='*60}")
    print(json.dumps(request, indent=2))
    
    try:
        response = requests.post(MCP_URL, json=request, headers={
            "Content-Type": "application/json"
        })
        
        # For SSE responses, parse the data
        if response.headers.get("content-type", "").startswith("text/event-stream"):
            lines = response.text.strip().split("\n")
            data_lines = [line[6:] for line in lines if line.startswith("data: ")]
            if data_lines:
                result = json.loads(data_lines[-1])  # Get the last response
                print(f"\nRESPONSE:")
                print(json.dumps(result, indent=2))
                return result
        else:
            result = response.json()
            print(f"\nRESPONSE:")
            print(json.dumps(result, indent=2))
            return result
    except requests.exceptions.ConnectionError:
        print(f"\n❌ ERROR: Could not connect to MCP server at {MCP_URL}")
        print("Make sure the server is running with: ./gradlew runmcp")
        sys.exit(1)
    except Exception as e:
        print(f"\n❌ ERROR: {e}")
        sys.exit(1)

def test_initialize():
    """Test the initialize endpoint."""
    print("\n" + "="*60)
    print("TEST: Initialize")
    print("="*60)
    response = make_request("initialize", {})
    
    if "result" in response:
        result = response["result"]
        print(f"\n✅ Server: {result.get('serverInfo', {}).get('name')} v{result.get('serverInfo', {}).get('version')}")
        print(f"✅ Protocol: {result.get('protocolVersion')}")
        print(f"✅ Instructions: {result.get('instructions')}")
    else:
        print(f"\n❌ Failed: {response.get('error', {}).get('message')}")

def test_list_tools():
    """Test the tools/list endpoint."""
    print("\n" + "="*60)
    print("TEST: List Tools")
    print("="*60)
    response = make_request("tools/list", {})
    
    if "result" in response:
        tools = response["result"].get("tools", [])
        print(f"\n✅ Found {len(tools)} tools:")
        for tool in tools:
            print(f"   - {tool['name']}: {tool.get('description', 'No description')}")
    else:
        print(f"\n❌ Failed: {response.get('error', {}).get('message')}")

def test_call_tool(tool_name: str, arguments: Dict[str, Any] = None):
    """Test calling a specific tool."""
    print("\n" + "="*60)
    print(f"TEST: Call Tool '{tool_name}'")
    print("="*60)
    
    params = {
        "name": tool_name,
        "arguments": arguments or {}
    }
    response = make_request("tools/call", params)
    
    if "result" in response:
        result = response["result"]
        content = result.get("content", [])
        is_error = result.get("isError", False)
        
        if is_error:
            print(f"\n⚠️  Tool returned error:")
        else:
            print(f"\n✅ Tool executed successfully:")
        
        for item in content:
            if item.get("type") == "text":
                print(f"\n{item.get('text')}")
    else:
        print(f"\n❌ Failed: {response.get('error', {}).get('message')}")

def main():
    """Run all tests."""
    print("\n" + "="*60)
    print("Field4 MCP Server Test Client")
    print("="*60)
    print(f"Server URL: {MCP_URL}")
    
    # Test initialize
    test_initialize()
    
    # Test list tools
    test_list_tools()
    
    # Test calling tools
    test_call_tool("list_attachments")
    test_call_tool("commentary", {"text": "This is a test commentary"})
    test_call_tool("list_definitions")
    test_call_tool("list_contents")
    
    print("\n" + "="*60)
    print("✅ All tests completed!")
    print("="*60)

if __name__ == "__main__":
    main()
