# Field4 Server Components

This directory contains server-side components for Field4.

## Components

### Guide Chat Server (`guide_chat_server.py`)

Real-time chat server for Field4's interactive guide system. Provides WebSocket and HTTP endpoints for per-slide discussions.

**Features:**
- WebSocket support for real-time messaging
- HTTP REST API for message retrieval and posting
- Persistent storage in JSON files
- CORS enabled for browser access
- No authentication required

**Usage:**
```bash
pip install -r requirements.txt
python3 guide_chat_server.py
```

**Configuration:**
- `CHAT_SERVER_PORT`: Port number (default: 5002)
- `CHAT_SERVER_DEBUG`: Debug mode (default: true)

**Endpoints:**
- `GET /health`: Health check
- `GET /chat/<slideshow_id>/<slide_number>`: Get messages
- `POST /chat/<slideshow_id>/<slide_number>`: Post message
- `WebSocket /ws`: Real-time chat connection

See [../dev_docs/GUIDE_CHAT_QUICKSTART.md](../dev_docs/GUIDE_CHAT_QUICKSTART.md) for complete documentation.

### Metadata Server (`metadata_server.py`)

Template metadata server for Field4. Provides template listing and download functionality.

**Features:**
- List available templates
- Download template metadata.edn files
- Telemetry tracking
- CORS enabled

**Usage:**
```bash
python3 metadata_server.py
```

**Configuration:**
- `METADATA_SERVER_PORT`: Port number (default: 5001)
- `METADATA_SERVER_DEBUG`: Debug mode (default: true)

**Endpoints:**
- `GET /list`: List all templates
- `GET /download/<template-name>`: Download template
- `GET /telemetry`: Get usage statistics

## Development

### Installing Dependencies

```bash
pip install -r requirements.txt
```

### Running Both Servers

```bash
# Terminal 1: Metadata server
python3 metadata_server.py

# Terminal 2: Chat server
python3 guide_chat_server.py
```

### Testing

Test the health endpoints:
```bash
curl http://localhost:5001/telemetry
curl http://localhost:5002/health
```

Test chat functionality:
```bash
# Post a message
curl -X POST http://localhost:5002/chat/test-slideshow/0 \
  -H "Content-Type: application/json" \
  -d '{"username": "tester", "message": "Hello!"}'

# Get messages
curl http://localhost:5002/chat/test-slideshow/0
```

## Data Storage

### Chat Data
Messages are stored in `../chat_data/<slideshow_id>/slide_<number>.json`

### Metadata
Templates are stored in `../metadata_store/<template-name>/metadata.edn`

### Telemetry
Server usage data is stored in `../metadata_server_telemetry.json`

## Requirements

- Python 3.8+
- Flask 3.0+
- flask-cors 4.0+
- flask-socketio 5.3+
- python-socketio 5.10+
