#!/bin/bash
# Start the metadata server
# Usage: ./start_metadata_server.sh [port]

PORT=${1:-5001}
export METADATA_SERVER_PORT=$PORT

echo "Starting metadata server on port $PORT..."
python3 metadata_server.py
