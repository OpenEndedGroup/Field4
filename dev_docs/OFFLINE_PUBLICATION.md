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
`snapshotForPublication()` copies the current normalized geometry payload and inspector/camera
state. It retains typed arrays as ordinary numeric arrays for the static viewer. Unknown WebGL
surfaces require an adapter and are reported instead of exporting a blank canvas.

`publication-three.js` packages the canonical viewer and vendored module graph into one shared
`three-publication-runtime.js` file. Within each sandbox, the runtime makes Blob URLs and an
import map for those modules. This avoids HTTP module requests from an opaque iframe origin,
so the static host needs no special CORS headers. Missing literal dependencies fail the bundle
check. No renderer fork or generated viewer source is maintained. Original author tool modules
are never imported. The output keeps orbit, zoom, parts visibility, light and X-ray controls,
and GLB export without server compression. Camera linking, review notes and editing controls are
hidden; live websocket startup is bypassed. An unsupported custom tool or animated shot uses the
real canvas image with all viewer controls omitted.

The archive contains:

```
index.html
offline.html, offline-viewer.js, offline-outputs.js, edn-parser.js
min/vs/..., woff2/..., iosevka.css, outputareas.css, plotly-2.24.1.min.js
document/metadata.edn
document/outputs.json
outputs/0.html, outputs/1.html, ...
three-publication-runtime.js, publication-font.js
README.md
```

`outputs.json` version 1 records `capturedAt`, `documentName` and an ordered `outputs` list.
Each entry has `uid`, `channel`, `open`, `width`, `height`, `kinds`, `warnings` and, when successful,
`path`. Output paths (`outputs/<number>.html`) are relative to the directory above the manifest.
Failed entries have no path and remain visible as warnings in the viewer. The `outputs` query
parameter opts the viewer into this format; ordinary EDN browsing continues without snapshots.
Publication EDN is immutable and fully inlined, so the viewer skips loose-sidecar refreshes.

Captured documents are loaded in sandboxed frames without same-origin privileges. Only generated
initialization code, bundled Plotly and the canonical frozen Three.js viewer run. The capture's CSP forbids network connections,
forms and remote images; images and fonts are embedded. The native browser ZIP writer uses raw
deflate for text where available, with stored ZIP entries as a compatible fallback.

Capture limits are explicit: 30,000 nodes and 30 seconds per channel, four nested iframe levels,
15 seconds per fetched resource and 32 MB per resource. The automation archive transfer is
bounded at 256 MB. Lazy inspectors capture materialized DOM, rather than traversing arbitrary
JVM objects. Current limitations include cross-origin frames, live media, unmaterialized inspector
branches and generic WebGL canvases without adapters.

## Verification

The pilot uses actual `lines`, `.h`, `.h2`, Plotly inline and iframe outputs, `threeLines` and a
two-part solid scene with `three { }`.
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
Plotly zoom, mouse orbit, parts visibility, X-ray pixel changes and a local GLB download, and
captures actual browser evidence. Archive/transfer and module-graph regressions are in
`tools/test_field_publication.py` and run with the ordinary Python tools gate.

## Documentation publications

Publication refresh is part of the normal docs build. `FIELD_DOCS_RUNNER_MODE=incremental`
(the default) captures new or changed supported documents before copying site assets. `strict`
does the same freshness check, `always` recaptures every supported recipe, and `never` starts no
Field process and fails if a publication needs refresh. CSS-only edits do not invalidate captures.

Run `uv run python tools/docs_publications.py sync` to refresh without rendering the site.
Unchanged documents start no Field JVM or browser. Documents with no output-area channels have
their own source fingerprints in `catalog.json`'s `emptyDocuments`; they remain code-only and
are reconsidered when their sources change. The supported population is the two pilot documents
plus the existing headless-safe transcript classification; native/device/service scenes stay
outside this automatic capture pass.

`checkDocsPublications`, included in `check` and `docsGates`, checks recipe coverage, source and
publisher fingerprints, manifests, output files and shared-runtime references without launching
Field. Captured-file checksums detect inconsistent generated-file merges; shared runtimes are
checked against their content-addressed names. The landing regenerator refreshes publications after transcript materialization and before
running this guard. A failed capture retains the previous files but fails the refresh; stale data
cannot be presented as a successful new publication. Removed or reclassified recipes are retired
from the registry. Refresh results are written to `build/docs-publications-refresh.json`.
Capture timeouts get one retry in a fresh Field session; a second timeout still fails the build.

`tools/docs_publications.py capture --group pilot` opens copies of the existing FLine and
Three.js companion documents in an isolated Field, runs their boxes, and captures the actual
outputs. `--group gallery` uses the existing `DemoTranscriptExecSmokeTest.EXECUTE` classification
to replay headless transcript scenes. Create-only transcripts also run their prepared boxes in
creation order. `--name` narrows either batch and `--force` refreshes a retained capture.
Capture failures are reported, never replaced with fabricated output. Documents without
output-area channels retain ordinary code browsing.

The installer stores frozen metadata and outputs under `public/docs/assets/web_publications/`.
Font, Plotly and Three.js runtimes are shared by content hash. Repeated computed style
declarations are factored into document-local CSS rules; the captured property values are
preserved. This avoids repeating a complete computed style on every path in a dense SVG.
Plotly's SVG text is rebuilt from data, so its rendered font family is recorded separately and
reapplied before drawing. The bundled Iosevka font loads before Plotly measures tick labels.

`catalog.json` records source-file hashes and capture results. The docs build verifies those
hashes before emitting `offline-publications.json` beside the common viewer. Existing
`offline.html?file=assets/.../metadata.edn` links discover the matching publication automatically.
The captured metadata travels with its outputs, so code and output are from the same run;
source documents and editable starting points remain separate. A source change requires
recapture before the docs build can publish that entry again.

Capture batches replace their owned Field JVM after sixteen documents, bounding compiler and
browser state. The synchronous `petri_tala_layout` and `morpho_circuit_search` benchmarks get a separate JVM with a 120-second
watchdog budget; ordinary capture JVMs use Field's normal 30-second limit.

The transcript gallery intercepts ordinary Open links to show one viewer beside the selected
entry. A new selection removes the old iframe. Narrow windows place the viewer below the entry;
modifier clicks and the separate-tab button retain ordinary navigation.

Serve the built site and verify the embeds, gallery placement and every captured channel with
`node tools/check_docs_publications.mjs http://localhost:9880/field4/ build/docs-publication-evidence`.
It rejects missing assets, browser errors, external requests and websockets. Use the same
`FIELD_PLAYWRIGHT_MODULE` and `FIELD_CHROMIUM_EXECUTABLE` overrides as the standalone verifier.

## Further extensions

Bounded inspector-data snapshots can make more rows and branches available locally. These can
extend version 1's per-channel kinds while the
generic HTML capture remains the fallback. Backend DSP, shaders and box code still need their
own runtime or rendered media if they are to continue changing after export.
