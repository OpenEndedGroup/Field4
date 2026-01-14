# Metadata Server

A simple Flask server for hosting and serving metadata.edn files that can be imported into Field.

## Purpose

This server provides a lightweight way to share Field templates (metadata.edn files) that aren't part of Field's built-in templating system. Users can:

1. Store additional templates in the `metadata_store/` directory
2. List available templates via the `/list` endpoint
3. Download specific templates via the `/download/<name>` endpoint

These templates can then be imported into Field through the "import from workspace" feature.

## Installation

1. Install Python dependencies:
   ```bash
   pip install -r metadata_server_requirements.txt
   ```

2. Run the server:
   ```bash
   python metadata_server.py
   ```

The server will start on `http://localhost:5001` by default. You can configure the server using environment variables:

   **Port Configuration:**
   ```bash
   METADATA_SERVER_PORT=8000 python metadata_server.py
   ```

   **Debug Mode (disable for production):**
   ```bash
   METADATA_SERVER_DEBUG=false python metadata_server.py
   ```

## Usage

### Adding Templates

To add a new template to the server:

1. Create a directory in `metadata_store/` with your template name:
   ```bash
   mkdir metadata_store/my_template
   ```

2. Place your `metadata.edn` file in that directory:
   ```bash
   cp /path/to/template/metadata.edn metadata_store/my_template/
   ```

3. The template will automatically be available through the server

### API Endpoints

#### List Available Templates
```bash
GET http://localhost:5001/list
```

Returns JSON array of available templates:
```json
[
  {
    "name": "my_template",
    "has_edn": true
  }
]
```

#### Download a Template
```bash
GET http://localhost:5001/download/my_template
```

Downloads the `metadata.edn` file from the specified template directory.

#### Get Telemetry Data
```bash
GET http://localhost:5001/telemetry
```

Returns telemetry data about server usage:
```json
{
  "list_queries": 42,
  "downloads": {
    "my_template": 10,
    "another_template": 5
  },
  "last_updated": "2025-12-28T07:01:47.311306"
}
```

Telemetry tracks:
- `list_queries`: Total number of times the `/list` endpoint has been queried
- `downloads`: Object mapping template names to their download counts
- `last_updated`: ISO timestamp of the last telemetry update

Telemetry data is persisted to `metadata_server_telemetry.json` and survives server restarts.

## Directory Structure

```
.
├── metadata_server.py              # The Flask server
├── metadata_server_requirements.txt # Python dependencies
├── README_METADATA_SERVER.md       # This file
└── metadata_store/                 # Storage directory for templates
    ├── example_template_1/
    │   └── metadata.edn
    └── example_template_2/
        └── metadata.edn
```

## Integration with Field

This server is designed to complement Field's existing template system. While Field's built-in templates are stored in:
- `~/Documents/AFieldWorkspace/` (user workspace)
- `app/templates/` (application templates)

This metadata server provides an additional source for templates that can be:
- Shared across teams
- Hosted remotely
- Version controlled separately from Field
- Distributed without modifying Field's installation

### Automatic Integration (Recommended)

Field can automatically query template servers at startup and make their templates available alongside local templates.

**Configuration:**

Set the `AFIELD_TEMPLATE_SERVERS` environment variable with a comma-separated list of server URLs before starting Field:

```bash
export AFIELD_TEMPLATE_SERVERS="http://localhost:5001,http://templates.example.com:5001"
./gradlew run
```

Or use the system property:

```bash
./gradlew run -Dafield.templateServers="http://localhost:5001,http://templates.example.com:5001"
```

**Features:**

- Templates from configured servers are fetched in the background at startup to hide latency
- Remote templates appear in Field's template list alongside local templates
- When inserting a remote template, Field automatically downloads and imports it with fresh UUIDs
- No manual download or drag-and-drop required

### Using Templates from the Metadata Server

There are several ways to use templates from the metadata server with Field:

#### Option 1: Automatic Integration (Recommended)
See "Automatic Integration" section above. This is the easiest way to use remote templates.

#### Option 2: Manual Download and Import
1. Download a template from the server:
   ```bash
   curl -O http://localhost:5001/download/example_template
   ```
2. Drag and drop the downloaded `metadata.edn` file into Field's interface
3. Field will import the boxes with fresh UUIDs to avoid conflicts

#### Option 3: Copy to Local Workspace
For frequently used templates, you can copy them to your local workspace:
```bash
# Download template
curl -o ~/Documents/AFieldWorkspace/my_template/metadata.edn \
  http://localhost:5001/download/my_template

# Now it appears in Field's built-in template list
```

### Remote Hosting

The metadata server can be deployed on a team server or cloud instance to share templates across your organization:

1. Deploy the server on a shared host
2. Team members configure their Field instances to point to your server
3. Templates are automatically available to everyone

For remote deployment, consider:
- Using a production WSGI server (e.g., Gunicorn)
- Adding authentication (e.g., API keys, OAuth)
- Enabling HTTPS for secure transport
- Using a reverse proxy (e.g., nginx) for better performance

## CORS

The server has CORS enabled to allow the Field frontend (running on a different port) to access the metadata files.

## Security Notes

- The server uses basic path sanitization to prevent directory traversal attacks
- Debug mode is enabled by default for development but should be disabled in production using `METADATA_SERVER_DEBUG=false`
- For production use, consider:
  - Using a production WSGI server (e.g., Gunicorn, uWSGI)
  - Adding authentication (e.g., API keys, OAuth)
  - Enabling HTTPS for secure transport
  - Using a reverse proxy (e.g., nginx) for better performance
- The server runs on all interfaces (0.0.0.0) - adjust if needed for security
