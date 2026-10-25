# Offline viewer distribution

The canonical bundle definition is
[`tools/sync_offline_assets.py`](../tools/sync_offline_assets.py). Do not maintain a
second hand-written file list here.

Build the standalone distribution with:

```bash
./tools/build-offline-docs.sh
```

This synchronizes the canonical resources and the `hello_display` and `shared_types_demo`
transcript-generated documents into `dist/offline-viewer/`. Both transcripts are registered for
execution by `DemoTranscriptExecSmokeTest`; the bundle reuses their generated documents. The same
Python synchronizer is used by the documentation build, so fixes belong in the
source resources or in that script rather than in generated output.

The bundle is a static site and must be served over HTTP; opening `offline.html`
directly as a `file:` URL is not a supported path. A local smoke is:

```bash
cd dist/offline-viewer
python3 -m http.server 8000
```

Then open
`http://localhost:8000/offline.html?file=assets/agent_transcripts/hello_display/metadata.edn`.
Remote `?file=` URLs require the document server to permit the viewer's origin
with CORS.

## Trust boundary

Rendering is client-side and does not execute box code. The `Open in Field·4`
button is different: after an explicit click it submits the source EDN to an
embedding Field instance, or to `https://localhost:9092/api/import-offline-doc`.
That endpoint creates a new document directory in the active workspace and writes
the supplied text as `metadata.edn`. Imported documents therefore remain
untrusted input; inspect them before running their code.

The exact viewer behavior and source ownership are recorded in
[`OFFLINE_VIEWER.md`](OFFLINE_VIEWER.md) and in the header comment of
[`offline-viewer.js`](../src/commonMain/resources/offline-viewer.js).
