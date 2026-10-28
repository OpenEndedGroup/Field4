# Offline output publication

The palette command **Publish document for the web (.zip)** captures the current document into
a relocatable static site. It uses the browser already rendering the document. Publication does
not execute boxes or pause their live transports. Channels are sampled in sequence; this is not
a simultaneous checkpoint of a running simulation.

## Boundary and format

`export-web-document` returns a fully inlined `export-document` package, the latest retained
`set-output` channels, and an enumerated list of canonical viewer assets. The publisher also
includes channels currently mounted in the frontend that are absent from the bounded replay
cache. This keeps live websocket and buffer paths unchanged.

`publication-capture.js` captures computed presentation and current form values, recurses into
same-origin iframes, embeds image resources and Iosevka, and removes original scripts and event
handlers. Field controls become disabled. A Plotly adapter records `data` and the current
`layout`, then initializes the bundled Plotly against those fixed values. Plots requiring external
map or image data use Plotly's actual image capture and report that fallback. The Three.js viewer's
`snapshotImage()` renders and reads the WebGL canvas in one task, before the buffer is cleared.
An unknown WebGL surface requires an adapter and is reported instead of exporting a blank canvas.

The archive contains:

```
index.html
offline.html, offline-viewer.js, offline-outputs.js, edn-parser.js
min/vs/..., woff2/..., iosevka.css, outputareas.css, plotly-2.24.1.min.js
document/metadata.edn
document/outputs.json
outputs/0.html, outputs/1.html, ...
README.md
```

`outputs.json` version 1 records `capturedAt`, `documentName` and an ordered `outputs` list.
Each entry has `uid`, `channel`, `open`, `width`, `height`, `kinds`, `warnings` and, when successful,
`path`. Output paths (`outputs/<number>.html`) are relative to the directory above the manifest.
Failed entries have no path and remain visible as warnings in the viewer. The `outputs` query
parameter opts the viewer into this format; ordinary EDN browsing continues without snapshots.
Publication EDN is immutable and fully inlined, so the viewer skips loose-sidecar refreshes.

Captured documents are loaded in sandboxed frames without same-origin privileges. Only generated
initialization code and the bundled Plotly run. The capture's CSP forbids network connections,
forms and remote images; images and fonts are embedded. The native browser ZIP writer uses raw
deflate for text where available, with stored ZIP entries as a compatible fallback.

Capture limits are explicit: 30,000 nodes and 30 seconds per channel, four nested iframe levels,
15 seconds per fetched resource and 32 MB per resource. The automation archive transfer is
bounded at 256 MB. Lazy inspectors capture materialized DOM, rather than traversing arbitrary
JVM objects. Current limitations include cross-origin frames, live media, unmaterialized inspector
branches, generic WebGL canvases without adapters, and 3D interaction after publication.

## Verification

The pilot uses actual `lines`, `.h`, `.h2`, Plotly inline and iframe outputs, and `threeLines`.
Open the capture browser before running it:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --document publication-pilot --with-playwright --timeout 180 \
  --command 'macro write web_publication --file dev_docs/testing/fixtures/web_publication.yaml' \
  --command 'macro run web_publication' \
  --command 'publish-document /tmp/publication-pilot.zip --timeout 240'
```

`publish-document` uses the same browser entry point as the palette. It transfers the archive in
bounded chunks, refuses an existing destination and removes partial files and browser buffers
after success or failure. The backdoor stops a server it started when it exits.

Unzip the archive, then verify in a separate browser:

```bash
node tools/check_web_publication.mjs /tmp/publication-pilot build/publication-evidence --pilot
```

The verifier requires Node's `playwright` module (override its location with
`FIELD_PLAYWRIGHT_MODULE`) and an installed Chromium (optionally
`FIELD_CHROMIUM_EXECUTABLE`). It serves the export under `/nested/publication/`, blocks requests
outside that directory, rejects missing assets and websocket attempts, checks code browsing and
Plotly zoom, and captures actual browser evidence. Archive/transfer regressions are in
`tools/test_field_publication.py` and run with the ordinary Python tools gate.

## Extensions

An adapter can later export the Three.js payload plus geometry buffers and camera state to retain
orbit/inspection over a frozen scene. Bounded inspector-data snapshots can similarly make more
rows and branches available locally. Both can extend version 1's per-channel kinds while the
generic HTML capture remains the fallback. Backend DSP, shaders and box code still need their
own runtime or rendered media if they are to continue changing after export.
