# Metadata Server — Field Integration

A simple Flask server for hosting `metadata.edn` templates that can be imported into Field. This
document covers the **Field-side integration** (how Field discovers and imports remote templates).

For running the server itself, its dependencies, endpoints, and where template files live, see the
canonical, code-colocated docs:

- **`server/README.md`** — how to run `metadata_server.py` (and the sibling `guide_chat_server.py`),
  the `/list`, `/download/<name>`, and `/telemetry` endpoints, and the
  `METADATA_SERVER_PORT` / `METADATA_SERVER_DEBUG` env vars.
- **`server/requirements.txt`** — Python dependencies (`pip install -r server/requirements.txt`).
- **`server/metadata_server.py`** — the server code. Templates it serves live in
  `server/metadata_store/<name>/metadata.edn` (the directory is auto-created next to the script).

> Note: the top-level `metadata_store/` directory (with `agent_demo/`, `example_template/`,
> `simple_hello/`) is **unrelated** to this server — it backs the offline-docs asset bundler
> (`tools/sync_offline_assets.py`), not `metadata_server.py`.

## Purpose

This server provides a lightweight way to share Field templates (`metadata.edn` files) that aren't
part of Field's built-in templating system. Templates can be listed via `/list` and fetched via
`/download/<name>`, then imported into Field.

## Integration with Field

This server complements Field's built-in template system. Field's built-in templates are stored in:

- `~/Documents/AFieldWorkspace/` (user workspace)
- `app/templates/` (application templates)

The metadata server provides an additional source of templates that can be shared across teams,
hosted remotely, version-controlled separately from Field, and distributed without modifying a
Field installation.

### Automatic Integration (Recommended)

Field can query template servers at startup and make their templates available alongside local
templates. The integration lives in `src/jvmMain/kotlin/afield/server/templating.kt`.

**Configuration:**

Set the `AFIELD_TEMPLATE_SERVERS` environment variable to a comma-separated list of server URLs
before starting Field:

```bash
export AFIELD_TEMPLATE_SERVERS="http://localhost:5001,http://templates.example.com:5001"
./gradlew run
```

Or use the equivalent system property:

```bash
./gradlew run -Dafield.templateServers="http://localhost:5001,http://templates.example.com:5001"
```

**Behavior:**

- Configured servers are fetched in a background coroutine at startup (`initializeTemplateServers`)
  to hide latency; results are held in an in-memory cache.
- Remote templates appear in Field's template list alongside local templates.
- When inserting a remote template, Field downloads and imports it with **fresh UUIDs** (the same
  ID transformation used by `cloneInto`) to avoid conflicts.
- No manual download or drag-and-drop is required.

### Manual alternatives

If you are not using automatic integration:

**Download and import**

```bash
curl -O http://localhost:5001/download/example_template
```

Drag the downloaded `metadata.edn` into Field's interface; Field imports the boxes with fresh UUIDs.

**Copy to local workspace** (for frequently used templates)

```bash
curl -o ~/Documents/AFieldWorkspace/my_template/metadata.edn \
  http://localhost:5001/download/my_template
```

It then appears in Field's built-in template list.

## Remote hosting & security

The server can be deployed on a shared host so a team's Field instances point at one server. CORS is
enabled so the Field frontend (on a different port) can reach it. For production deployment, consider:

- A production WSGI server (e.g. Gunicorn, uWSGI)
- Authentication (e.g. API keys, OAuth)
- HTTPS for secure transport
- A reverse proxy (e.g. nginx)

The server binds all interfaces (`0.0.0.0`) and enables debug mode by default; disable debug with
`METADATA_SERVER_DEBUG=false` and restrict the bind address for production use. It applies basic
path sanitization against directory traversal.
