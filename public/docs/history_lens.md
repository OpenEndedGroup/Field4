# Code History

**Code History** shows what one line or selection of code said in earlier saves, in the editor, under the line itself. To take the whole document back to an earlier version, use [document history](document_history.md) instead.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1440x900" clip-selector="#docs-history-lens-crop" screenshot="assets/history_lens/history-lens-open.png" transcript="assets/transcripts/document_history_setup.yaml" document="docs-history-lens-figure" fresh-document=true padding="8" wait="0" timeout="150" embed=true hidden=true name="history-lens-open" alt="The editor for the box sketch with Code History open beneath the line that sets radius: the lens header reads history with a version label, box count, size and age, Rollback Property, Box and Document buttons sit at its right, and its rows list the earlier values of that line, radius 80.0, 64.0 and 40.0, with the changed number highlighted"}
// Three saved versions of the sketch box's code, as on the document history page: the setup
// transcript holds the first, and two edits through the editor, each left for the 5 s autosave,
// add the others. Then the cursor goes to the radius line and Code History is opened from its editor
// action, the same one the right-click menu runs.
val uid = ui.eval("""
    const b = [...document.querySelectorAll(".box")].find(b => (b.textContent || "").includes("sketch"))
    return b ? (b.uid || b.getAttribute("uid") || b.getAttribute("data-uid")) : null
""".trimIndent()).toString().trim('"')
check(uid.isNotBlank() && uid != "null") { "the setup transcript's sketch box is not on the canvas" }
ui.openEditor(uid)
ui.waitForSelector(".monaco-editor", 15000.0)
ui.eval("""
    const c = document.querySelector(".monaco-editor-container")
    for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
    if (window.globalEditor && window.globalEditor.layout) window.globalEditor.layout()
    return true
""".trimIndent())
ui.pause(6500)
val versions = [
    """
    // A ring of points, saved three times.
    val radius = 64.0
    val count = 12
    println("radius " + radius + ", count " + count)
    """.trimIndent(),
    """
    // A ring of points, saved three times.
    val radius = 80.0
    val count = 12
    val ring = (0 until count).map { it * 360.0 / count }
    println("radius " + radius + ", " + ring.size + " points")
    """.trimIndent(),
]
for (text in versions) {
    val quoted = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    ui.eval("window.globalEditor.setValue($quoted); return true")
    ui.pause(6500)
}
ui.eval("""
    const ed = window.globalEditor
    ed.focus()
    ed.setPosition({ lineNumber: 2, column: 5 })
    ed.getAction("history-lens-selection").run()
    return true
""".trimIndent())
var rows = ""
for (i in 0 until 80) {
    ui.pause(250)
    rows = ui.eval("""
        const n = document.querySelectorAll(".history-lens-variant[data-version-index]").length
        const meta = document.querySelector(".history-lens-meta")?.textContent || ""
        return "rows=" + n + " meta=" + meta
    """.trimIndent()).toString()
    if ((Regex("rows=(\\d+)").find(rows)?.groupValues?.get(1)?.toInt() ?: 0) >= 3) break
}
check((Regex("rows=(\\d+)").find(rows)?.groupValues?.get(1)?.toInt() ?: 0) >= 3) { "Code History never listed three values of the radius line: " + rows }
ui.pause(800)
// Mark the editor's top, down to the bottom of the lens, so the capture is the code and the lens.
ui.eval("""
    document.getElementById("docs-history-lens-crop")?.remove()
    const ed = document.querySelector(".monaco-editor").getBoundingClientRect()
    const lens = document.querySelector(".history-lens-zone").getBoundingClientRect()
    const m = document.createElement("div")
    m.id = "docs-history-lens-crop"
    m.setAttribute("style", "position:fixed;pointer-events:none;background:transparent;border:0;z-index:2147483001;" +
        "left:" + ed.left + "px;top:" + ed.top + "px;width:" + Math.min(ed.width, 900) + "px;height:" + (lens.bottom - ed.top + 60) + "px")
    document.body.appendChild(m)
    return true
""".trimIndent())
ui.moveMouse(5.0, 5.0, 2)
ui.pause(300)
```

Above, Code History is open under the line that sets `radius`, listing the values that line had in earlier saves.

## Open it

Select the box, put your cursor on a line of its code (or select a range), **right-click**, and choose **Show Code History for Line / Selection** from the editor context menu.

Code History opens as a small zone directly beneath your selection. Its shortcut is `alt+shift+h`.

## What it shows

It follows the selected line or range back through the saved versions, even as lines above
it are added or removed. It lists each version where the line changed, and the oldest value it
had. Saves that left it alone are skipped.

Each row is one matching version, labelled the same way the [history view](document_history.md)
labels it (`#12`, `#11`, … and any name you gave it), with that version's value and the tokens that
changed highlighted, so the difference between two rows is legible without reading both in full.
The header shows the box count, size and age of the version you are on.

Step through the versions with the ‹ › arrows, or click a row; the editor lays that version over the current text as a read-only diff. When you find the state you want, the **Rollback** buttons put it back at the scope you choose:

- **Property** — restore just this property to the selected version
- **Box** — restore the whole box
- **Document** — a full [document restore](document_history.md#get-back-an-earlier-version) to that version

## How it relates to the history panel

Code History and the [document history panel](document_history.md) read the same saved versions. They differ in how much of the document they work on:

- **History panel** — whole document, its own view, restore or cherry-pick whole boxes.
- **Code History** — one line or selection, inline in the editor, lift a single past value back.

Reach for Code History when the question is "what did *this* used to be?" and for the panel when the question is "what did the *document* used to be?".

## Not the `history()` visualizer

The `history()` visualizer is a different tool: `m.history(value)` charts an expression's recent *runtime* values next to your code, and is covered on [Visualizers & Controls](visualizers.md).
