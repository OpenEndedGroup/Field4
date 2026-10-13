# Patchy from Kotlin

[Patchy](https://github.com/SethRobinson/Patchy) can also run as a live editor inside a Field
output. Choose **Patchy layered painting editor** in `./field.sh --setup` (Windows:
`field.bat --setup`) and approve its optional **BUILD** step. Or build it later:

```diffstatic {data-lang=bash}
./gradlew buildPatchyWeb
```

The recipe fetches a pinned upstream Patchy revision, applies Field's small bridge patch, and
builds the threaded WebAssembly editor. Its source, Qt and Emscripten toolchains, and finished
site are cached under `~/.field4/patchy-web/`, outside the Field checkout. Expect several GB of
downloads and a long first build. Rebuilds reuse the cache. Field finds the finished site on its
next launch and serves it from `/patchy/` with the isolation headers Patchy requires. This
optional task is outside Field's normal build and `check`.

Inside Field, Patchy uses a compact black Qt theme with flat controls and Iosevka Field
for Latin UI text. The bundled Noto fonts cover other scripts. This styling belongs to
the embedded editor; a standalone Patchy window keeps its own theme.

To use a separately built site while developing, set `AFIELD_PATCHY_WEB_ROOT` to its directory
before starting Field. It must contain `patchy.html`, `patchy.js`, `patchy.wasm`, and `patchy.data`.

In a Kotlin box, keep the layered painting in the Field document's `files/` directory:

```diffstatic_runner {data-lang=kotlin screenshot="assets/patchy/embedded-painting.png" capture="box" open-outputs=true wait-output=true wait-output-kind="inline" wait-output-contains="Patchy painting ready" wait="2" timeout="240" w="1040" h="800" padding="12" embed=true capture-profile="patchy-web" name="patchy-embedded-painting" alt="Patchy's dark embedded editor in a Field output, showing a new layered painting with a dark rectangular mark made by the Kotlin box"}
val p = patchyWeb(width = 900, height = 650, documentFile = "patchy.psd".file, autosave = true)
go {
    // A saved PSD is restored first. On a new document, make the first painting:
    if (p.state()["documents"].toString() == "[]") {
        val doc = p.newDocument(640, 480)
        val layer = p.addLayer(doc, "Kotlin ink")
        p.fillRect(doc, layer, 30, 30, 150, 80, "#202020")
    }
    println("Patchy painting ready: ${p.state()}")
}
```

The output is Patchy's editable canvas. Kotlin scripts change that same canvas, and manual edits
are reflected in `p.state()`. `p.script(...)` obtains Patchy's current edit token immediately
before each edit; a manual change during the request is rejected. Call `p.undo(documentId)` or
`p.redo(documentId)` in `go { ... }` to change its history. The panel is an active browser
session. With `documentFile` and `autosave = true`, Field restores the PSD before releasing
queued Kotlin edits and writes changed paintings after a short idle period. The file stays
separate from `metadata.edn`; `"patchy.psd".file` resolves to `<document>/files/patchy.psd`.
The status line shows transfer errors or a conflict if the PSD changed on disk. Patchy's
ordinary Save/Save As remains a browser download; a `patchyWeb()` without `documentFile`
still has no document persistence.

## Desktop connector

[Patchy](https://github.com/SethRobinson/Patchy) is a desktop image editor with layers, painting,
vector shapes, masks, and PSD files. Its native `patchy-mcp` connector lets Field's Kotlin backend
edit the same open Patchy document, inspect its layers, and fetch a rendered preview. Install
Patchy first, then give Field the path to the connector if `patchy-mcp` is not on `PATH`. On macOS
the packaged executable is inside `Patchy.app/Contents/MacOS/patchy-mcp`.

In a Kotlin box, with the interactive Patchy app already running:

```diffstatic {data-lang=kotlin}
val p = Patchy.connect(executable = "/Applications/Patchy.app/Contents/MacOS/patchy-mcp")
val before = p.state() // open documents, layers, IDs, and the current edit state
val doc = p.newDocument(640, 480)
val layer = p.addLayer(doc, "Kotlin marks")
p.fillRect(doc, layer, 40, 40, 180, 100, "#e5e5e5")
displayPatchy(p.preview(doc))
p.saveAs(doc, "/absolute/path/to/painting.psd")
```

`Patchy.connect()` reuses its session across box runs. `p.state()` gives the current document and
layer IDs, so Kotlin can work on a painting opened by hand in Patchy. The desktop connection
requires a state read or preview before its first edit. If the user changes the painting between
the read and Kotlin's edit, Patchy rejects that edit; read `state()` or `preview()` and decide what
to do next. Field never silently retries a rejected edit.

For the full Patchy JavaScript API, call `p.script("...")`. Its globals are fresh for each call,
while the documents stay open. `patchy.setResult(value)` returns a JSON value to Kotlin. Native
brush batches are available through `p.drawStrokes(documentId, layerId, strokes)`; consult
Patchy's installed scripting guide for stroke options. `p.undo(doc)` and `p.redo(doc)` operate on
Patchy's document history. `p.exportAs(doc, path)` writes a copy without changing the document's
save path.

For an independent offscreen workspace, use `Patchy.connect(attach = false)`. This is useful for
batch scripts and tests. Close that session when finished; closing an attached session never
closes the user's Patchy window. `displayPatchy(p.preview(doc))` shows a snapshot in Field's box
output; call it again to refresh after edits.

The hosted [browser edition](https://rtsoft.com/patchy/) remains independent of the desktop
connector. The Field bridge controls the iframe's own Patchy session. The WebAssembly files are
prepared only when the optional Patchy build is selected.
