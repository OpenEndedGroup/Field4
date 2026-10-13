# Patchy integration

Field offers two ways to control [Patchy](https://github.com/SethRobinson/Patchy) from Kotlin.
The user-facing instructions and examples are in [Patchy from Kotlin](../public/docs/patchy.md).

## Embedded editor

`Box.patchyWeb()` in `src/jvmMain/kotlin/afield/PatchyWeb.kt` opens a Field output containing
`/patchy-field.html`, which embeds the optional WebAssembly site from `/patchy/`. The site is
served only when `AFIELD_PATCHY_WEB_ROOT` names a build directory or the installed
`~/.field4/patchy-web/site` exists. Field supplies the cross-origin isolation headers required
by threaded Qt/WebAssembly. A normal Field build does not build or download Patchy.

`patchy_field.js` bridges the output iframe to Field's `outputRequests` binding and passes
JSON-RPC messages to Patchy's iframe with same-origin `postMessage`. The matching native
bridge lives in `optional/patchy-web/field-bridge.patch`. The Kotlin `PatchyWebSession` queues
commands until Patchy reports ready; calls to `state()`, `script()`, `newDocument()`,
`addLayer()`, `fillRect()`, `undo()`, and `redo()` operate on the editor the user sees.
`script()` first reads Patchy's state token, then submits the edit with that token, so a
concurrent manual edit is reported as a conflict.

The output binding is scoped to the live box output and removed when the box stops. The iframe
polls it for Kotlin commands. It restores a saved painting before releasing those commands.
The embedded session is distinct from a native Patchy process and its `patchy-mcp` connector.

## PSD persistence

`patchyWeb(documentFile = "patchy.psd".file, autosave = true)` binds one PSD under the Field
document's `files/` directory. The route `/api/patchy-document/{binding}` in
`PatchyWebStorage.kt` requires a same-site request and a live opaque output binding. It
validates the path is inside that document, enforces a 512 MiB limit and a PSD signature,
respects read-only documents, and writes through a temporary file with an atomic rename where
supported. Its SHA-256 revision precondition prevents overwriting an external edit.

The iframe loads PSD bytes into Patchy's virtual filesystem, then opens the painting. It watches
document history IDs; after 1.5 seconds without a change, it asks Patchy to write a PSD and
uploads those bytes to Field. Errors and external-file conflicts appear in the output status
line. The saved file is a sidecar, never a value in `metadata.edn`. Only the active Patchy
document is saved by this binding; Patchy's ordinary Save/Save As downloads through the browser.
Without `documentFile`, the embedded editor does not persist its painting to the Field document.

## Optional build and source ownership

`optional/patchy-web/pin.json` pins upstream Patchy, Emscripten, Qt, aqtinstall, and Ninja.
`field-bridge.patch` contains Field's changes, including the iframe API, PSD byte exchange,
and embedded-only dark Qt styling. The small Iosevka subset is a separate build input. There is
no Patchy source checkout or WebAssembly bundle in this repository.

`tools/build_patchy_web.py` fetches the pinned source, applies the patch, builds with the host
Qt tools and the common `wasm_multithread` kit, then stages the site and a hash manifest under
`~/.field4/patchy-web/`. It is invoked explicitly by `buildPatchyWeb` or the
`patchy-web` first-run extra. The manual `first-run` GitHub Actions workflow checks the patch
on macOS, Linux, and Windows; its `patchy` input enables full builds on all three. Routine
CI checks the patch only. See `optional/patchy-web/README.md` for the exact build and patch
regeneration commands.

The Field-specific Qt theme applies only inside the same-origin Field iframe. It uses flat dark
controls, compact Iosevka UI text, and Noto fallback. Standalone Patchy retains its own theme.

## Documentation capture

`public/docs/patchy.md` captures the embedded editor with a `diffstatic_runner` fence.
The box prints `Patchy painting ready` only after its `go { }` block has created the layer
and painted through Patchy's live RPC. The runner waits for that inline output, then captures
the real iframe in the box output. The committed PNG is retained during ordinary docs builds;
the optional site is not a prerequisite for publishing the documentation.

To recapture after changing Patchy or its Field theme, build the optional site and run:

```sh
AFIELD_PATCHY_WEB_ROOT="$HOME/.field4/patchy-web/site" \
FIELD_DOCS_CAPTURE_PROFILES=patchy-web FIELD_DOCS_RUNNER_MODE=always \
uv run --project . --group docs python tools/build_zensical_docs.py --page patchy.md
```

Inspect the generated `public/docs/assets/patchy/embedded-painting.png` before committing it.

## Desktop connector

`Patchy.connect()` in the Kotlin backend starts `patchy-mcp` and attaches to a native Patchy
window; `attach = false` makes an independent offscreen session. It offers state, scripting,
preview snapshots, stroke batches, history, and file export. A preview displayed with
`displayPatchy(p.preview(doc))` is a snapshot and must be requested again after edits. The
desktop connector has no shared state with the embedded editor. Native Patchy is installed
separately and is not provided by the WebAssembly first-run extra.
