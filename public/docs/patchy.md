# Patchy in a Field document

[Patchy](https://github.com/SethRobinson/Patchy) runs as a live, layered painting editor inside a
Field output. Kotlin and AI4 can edit the same canvas you paint in by hand. Choose
**Patchy layered painting editor** in `./field.sh --setup` (Windows:
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

This box opens a painting kept in the Field document, gives AI4 tools to inspect, create, paint,
and undo, then makes the first mark. Open the box's AI Chat panel and ask it to use the tools.

```diffstatic_runner {data-lang=kotlin screenshot="assets/patchy/embedded-painting.png" capture="box" open-outputs=true wait-output=true wait-output-kind="inline" wait-output-contains="Patchy painting ready" wait="2" timeout="240" w="1040" h="800" padding="12" embed=true capture-profile="patchy-web" name="patchy-embedded-painting" alt="Patchy's dark embedded editor in a Field output, showing a new layered painting with a dark rectangular mark made by the Kotlin box"}
val p = patchyWeb(width = 900, height = 650, documentFile = "patchy.psd".file, autosave = true)

aiTool("patchy_state") {
    describe("Inspect the open painting and its document and layer IDs.")
    effects(ToolEffect.READ_DOCUMENT)
    action { p.state().toString() }
}

aiTool("patchy_new") {
    describe("Create a new layered painting. Return its document and first layer IDs.")
    effects(ToolEffect.CONTROL_RUNTIME, ToolEffect.MUTATE_DOCUMENT, ToolEffect.FILESYSTEM_WRITE)
    val width by param<Int>("canvas width in pixels", default = 640)
    val height by param<Int>("canvas height in pixels", default = 480)
    action {
        val doc = p.newDocument(width(), height())
        val layer = p.addLayer(doc, "AI marks")
        mapOf("documentId" to doc, "layerId" to layer)
    }
}

val rectangle = aiTool("patchy_rectangle") {
    describe("Paint a solid rectangle on an existing layer.")
    effects(ToolEffect.CONTROL_RUNTIME, ToolEffect.MUTATE_DOCUMENT, ToolEffect.FILESYSTEM_WRITE)
    val documentId by param<String>("document ID from patchy_state or patchy_new")
    val layerId by param<String>("layer ID from patchy_state or patchy_new")
    val x by param<Int>("left edge in pixels")
    val y by param<Int>("top edge in pixels")
    val width by param<Int>("rectangle width in pixels")
    val height by param<Int>("rectangle height in pixels")
    val color by param<String>("colour such as #202020")
    action {
        p.fillRect(documentId(), layerId(), x(), y(), width(), height(), color())
        "painted on ${layerId()}"
    }
}

aiTool("patchy_undo") {
    describe("Undo one edit in an open painting.")
    effects(ToolEffect.CONTROL_RUNTIME, ToolEffect.MUTATE_DOCUMENT, ToolEffect.FILESYSTEM_WRITE)
    val documentId by param<String>("document ID from patchy_state")
    action { p.undo(documentId()) }
}

go {
    if (p.state()["documents"].toString() == "[]") {
        val doc = p.newDocument(640, 480)
        val layer = p.addLayer(doc, "Kotlin ink")
        rectangle.call(
            "documentId" to doc,
            "layerId" to layer,
            "x" to 30,
            "y" to 30,
            "width" to 150,
            "height" to 80,
            "color" to "#202020",
        )
    }
    val title = p.script("patchy.setResult(app.activeDocument ? app.activeDocument.name : null);")
    println("Patchy painting ready: $title")
}
```

The tools run in the same live Patchy instance as the person painting. Re-running this box
replaces its AI4 tools; the saved PSD is restored before a queued tool call starts. `p.state()`
reports the current documents, layers, and IDs, including edits made by hand. The four tools
above are box code: change their descriptions or add another operation as your painting evolves.
The final `go { ... }` makes the first mark when the box runs. Those Patchy calls wait for replies
from the iframe, so ordinary box code starts them in a coroutine. An AI tool's `action { ... }`
already supports suspend calls and needs no `go` wrapper.

## Kotlin API

Every operation below is on the `PatchyWebSession` returned by `patchyWeb(...)`. Its methods
are suspend functions, so call them inside [`go { ... }`](running_code.md#calling-suspend-functions-with-go)
or an `aiTool { action { ... } }`.

| Call | Result |
| --- | --- |
| `state()` | Live documents, layers, IDs, active document, and edit token as JSON. |
| `newDocument(width, height)` | Create a painting and return its document ID. |
| `addLayer(documentId, name)` | Add a paint layer and return its layer ID. |
| `fillRect(documentId, layerId, x, y, width, height, color)` | Paint a solid rectangle. |
| `undo(documentId)`, `redo(documentId)` | Change one history step; return whether anything changed. |
| `script(code)` | Run Patchy's JavaScript API and return the value passed to `patchy.setResult(value)`. |

`script(...)` is the route to Patchy operations beyond these Kotlin helpers: brush strokes,
selections, vector shapes, text, masks, filters, and other layer edits. The box above uses it to
read the active document's name. The visible iframe is the live view; this Kotlin session has no
separate preview snapshot method.

Patchy's [scripting guide](https://github.com/SethRobinson/Patchy/blob/main/scripts/bundled/scripting-guide.md)
describes the JavaScript objects and methods. Each `script` call reads the current edit token
before it runs; if a manual edit intervenes, Patchy rejects the call rather than overwriting it.
`p.connected` reports whether the iframe is ready, and `p.close()` ends this Kotlin session;
stopping the box closes it automatically.

## Saving in the Field document

`"patchy.psd".file` resolves to `<document>/files/patchy.psd`. With `documentFile` and
`autosave = true`, Field restores that PSD before Kotlin edits and writes changes after a short
idle period. The painting stays outside `metadata.edn`. A changed PSD on disk is reported as a
conflict in the output status line rather than silently overwritten. Patchy's own Save/Save As
still downloads through the browser.

`patchyWeb()` also accepts `channel` (the output panel name), `width` and `height` in pixels.
Without `documentFile`, the painting is only in the live browser session. Setting
`autosave = false` with a `documentFile` loads the PSD but does not write later edits to it.
