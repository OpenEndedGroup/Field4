# Patchy from Kotlin

[Patchy](https://github.com/SethRobinson/Patchy) is a desktop image editor with layers, painting,
vector shapes, masks, and PSD files. Its native `patchy-mcp` connector lets Field's Kotlin backend
edit the same open Patchy document, inspect its layers, and fetch a rendered preview. Install
Patchy first, then give Field the path to the connector if `patchy-mcp` is not on `PATH`. On macOS
the packaged executable is inside `Patchy.app/Contents/MacOS/patchy-mcp`.

In a Kotlin box, with the interactive Patchy app already running:

```kotlin
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

Patchy also has a [browser edition](https://rtsoft.com/patchy/), but it is a separate workspace
from the desktop app. The browser build does not include `patchy-mcp`, so embedding that page
would not connect it to this Kotlin session or its live desktop document. It also requires a
cross-origin-isolated parent page for its WebAssembly threads. Embedding the hosted page in
Field's current page produces Patchy's cross-origin-isolation startup error. A genuine in-box
editor with Kotlin control would need both the isolation headers and a browser-side bridge into
Patchy's scripting engine. This integration uses the desktop connector and keeps PSD as Patchy's
editable source. Field does not bundle Patchy.
