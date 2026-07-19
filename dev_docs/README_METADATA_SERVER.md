# Metadata template server integration

The durable Field-side contract is documented beside `templateServers` and `templating()` in
`src/jvmMain/kotlin/afield/server/templating.kt`. The Flask protocol and storage paths live beside
the implementation in `server/metadata_server.py` and `server/README.md`.

The similarly named top-level `metadata_store/` is not served by this Flask app; it is an input to
`tools/sync_offline_assets.py`. The Flask app serves
`server/metadata_store/<template>/metadata.edn`.

## Configure Field

Set the `AFIELD_TEMPLATE_SERVERS` environment variable to a comma-separated list of server URLs
before starting Field:

```bash
export AFIELD_TEMPLATE_SERVERS="http://localhost:5001,http://templates.example.com:5001"
./gradlew runJvm
```

Or use the equivalent system property:

```bash
./gradlew -Dafield.templateServers="http://localhost:5001,http://templates.example.com:5001" runJvm
```

Field fetches each configured `/list` once in a background coroutine. Successful non-empty results
remain in memory for the life of the process; there is no periodic refresh. The template picker
combines entries found in the workspace, `workspace/templates`, bundled templates, and the remote
cache. Insertion currently resolves `workspace/templates`, bundled templates, then remote servers.
An entry found only directly beneath the workspace can therefore be listed without being insertable;
that is a current implementation limitation, not a supported storage location.

Remote insertion downloads `/download/<name>` and remaps box IDs with fresh UUIDs. When two sources
have the same name, the insertion search order above determines which one wins.

## Manual import

```bash
curl -O http://localhost:5001/download/example_template
```

Drag the downloaded `metadata.edn` into Field's interface; Field imports the boxes with fresh UUIDs.

For a reusable local template, place it under the configured workspace's `templates` directory:

```bash
mkdir -p /path/to/workspace/templates/my_template
curl -o /path/to/workspace/templates/my_template/metadata.edn \
  http://localhost:5001/download/my_template
```

## Security boundary

`metadata_server.py` is a development server: CORS is unrestricted, Flask debug mode defaults on,
and `app.run` uses Flask's default all-interface bind. The download route reduces the requested name
to its final path component, but the service has no authentication or TLS. Do not expose it directly
as a production service.
