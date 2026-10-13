# Patchy from Kotlin

[Patchy](https://github.com/SethRobinson/Patchy) can also run as a live editor inside a Field
output. This experimental path uses a local build of the Field Patchy fork. Start Field with
`AFIELD_PATCHY_WEB_ROOT` pointing to the directory that contains `patchy.html`, `patchy.js`,
and `patchy.wasm`. Field serves that build from `/patchy/` and enables the isolation headers
required by Patchy's WebAssembly threads for this Field instance.

```diffstatic {data-lang=bash}
AFIELD_PATCHY_WEB_ROOT=/absolute/path/to/Patchy/build/wasm-release ./field.sh
```

In a Kotlin box:

```diffstatic {data-lang=kotlin}
val p = patchyWeb(width = 1200, height = 800)
go {
    val doc = p.script("patchy.setResult(app.newDocument(640, 480).id);")!!
    val layer = p.script("patchy.setResult(app.getDocument($doc).addLayer('Kotlin ink').id);")!!
    p.script("app.getDocument($doc).getLayer($layer).fillRect(30, 30, 150, 80, '#202020');")
    println(p.state())
}
```

The output is Patchy's editable canvas. Kotlin scripts change that same canvas, and manual edits
are reflected in `p.state()`. `p.script(...)` obtains Patchy's current edit token immediately
before each edit; a manual change during the request is rejected. Call `p.undo(documentId)` or
`p.redo(documentId)` in `go { ... }` to change its history. The panel is an active browser
session. Patchy's web Save still downloads a file; the Field document does not yet store the
layered painting, so save before reloading.

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
connector. The Field fork's web bridge controls the iframe's own Patchy session. Field does not
bundle the Patchy WebAssembly files; use a local fork build for the embedded editor.
