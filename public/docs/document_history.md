# Document History

Every time Field saves a document whose contents changed, it keeps the previous save. The history
view shows those saves as a timeline, lets you compare any of them with what you have now, and
puts back an earlier one — the whole document, or just the boxes you pick.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1440x900" clip="0,0,1440,900" screenshot="assets/document_history/history-version-selected.png" transcript="assets/transcripts/document_history_setup.yaml" document="docs-history-figure" fresh-document=true padding="0" wait="0" timeout="150" embed=true hidden=true name="document-history-version-selected" alt="Field with the document history view open below a box named sketch and its editor: the timeline holds three saves, #0, #1 and the current document, version #1 is selected and differs from the live document in one box, and the editor lays version #1 over the current code as a read-only inline diff, version #1's lines in green (radius 40.0, a println of radius and count) against the current lines in red (radius 64.0, the ring and its println); below the timeline, the sidebar's This Box list shows sketch's values across the versions"}
/*
The setup box holds version 0 of its code. Two edits through the editor, each left for the
5 s autosave, give the document's backups three real versions; then the history view opens and
the middle version is clicked, which lays that version's text over the editor as a diff.
*/
val uid =
    ui.eval(
            """
            const b = [...document.querySelectorAll(".box")].find(b => (b.textContent || "").includes("sketch"))
            return b ? (b.uid || b.getAttribute("uid") || b.getAttribute("data-uid")) : null
            """
                .trimIndent(),
        )
        .toString()
        .trim('"')
check(uid.isNotBlank() && uid != "null") { "the setup transcript's sketch box is not on the canvas" }
ui.openEditor(uid)
ui.waitForSelector(".monaco-editor", 15000.0)
// Lay the editor pane out for this window, as a user's browser would: screenshot prep pins it to a
// size and place meant for a much larger page.
ui.eval(
    """
    const c = document.querySelector(".monaco-editor-container")
    for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
    if (window.globalEditor && window.globalEditor.layout) window.globalEditor.layout()
    return true
    """
        .trimIndent(),
)
ui.pause(6500)
val versions =
    [
        """
        // A ring of points, saved three times.
        val radius = 40.0
        val count = 12
        println("radius " + radius + ", count " + count)
        """
            .trimIndent(),
        """
        // A ring of points, saved three times.
        val radius = 64.0
        val count = 12
        val ring = (0 until count).map { it * 360.0 / count }
        println("radius " + radius + ", " + ring.size + " points")
        """
            .trimIndent(),
    ]
for (text in versions) {
    val quoted = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    ui.eval("window.globalEditor.setValue($quoted); return true")
    ui.pause(6500)
}
ui.eval(
    """
    window.switchToHistoryForFile(window.currentDocumentFilename || "default")
    return window.currentDocumentFilename
    """
        .trimIndent(),
)
// Wait for the rail to show the three saved versions and the current one.
var rail = ""
for (i in 0 until 80) {
    ui.pause(250)
    rail =
        ui.eval(
                """
                const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
                const d = f && f.contentDocument
                const dots = d ? d.querySelectorAll("#rail [data-version-index]").length : 0
                return "dots=" + dots
                """
                    .trimIndent(),
            )
            .toString()
    if ((Regex("dots=(\\d+)").find(rail)?.groupValues?.get(1)?.toInt() ?: 0) >= 3) break
}
check((Regex("dots=(\\d+)").find(rail)?.groupValues?.get(1)?.toInt() ?: 0) >= 3) {
    "the history rail never showed three versions: " + rail
}
ui.pause(1200)
// Click version #1 where it is drawn, in page coordinates.
val at =
    ui.eval(
            """
            const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
            const fr = f.getBoundingClientRect()
            const dot = f.contentDocument.querySelector('#rail [data-version-index="1"]')
            const r = dot.getBoundingClientRect()
            return [fr.left + r.left + r.width / 2, fr.top + r.top + r.height / 2].join(",")
            """
                .trimIndent(),
        )
        .toString()
        .trim('"')
        .split(",")
        .map { it.toDouble() }
ui.moveMouse(at[0], at[1], 6)
ui.clickAt(at[0], at[1])
var shown = ""
for (i in 0 until 60) {
    ui.pause(250)
    shown =
        ui.eval(
                """
                const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
                const target = f.contentDocument.querySelector("#target-version")?.textContent?.trim() || ""
                const zones = document.querySelectorAll(".monaco-editor .view-zones > div").length
                const decorations = document.querySelectorAll(".monaco-editor [class*='aidiff'], .monaco-editor [class*='ai-diff']").length
                return "target=" + target + " zones=" + zones + " decorations=" + decorations
                """
                    .trimIndent(),
            )
            .toString()
    if (shown.contains("target=#1") && !shown.contains("zones=0 decorations=0")) break
}
check(shown.contains("target=#1")) { "clicking version #1 did not select it: " + shown }
check(!shown.contains("zones=0 decorations=0")) { "version #1 is selected but the editor shows no diff: " + shown }
ui.moveMouse(5.0, 5.0, 2)
ui.pause(800)
```

Above, an earlier save of the box `sketch` is selected in the timeline, and the editor shows how it
differs from the current code; the sidebar's **This Box** list, [described below](#one-box-edit-by-edit), shows
the same box's values version by version. To scrub one line back through its versions without leaving the
editor, use [Code History](history_lens.md).

## Get back an earlier version

1. Run **Show Document History** from the command palette. (There is no keyboard shortcut; you
   can also open `/history?file=<document-name>` on the Field server.) The history view opens over
   the document.
2. Click a version in the timeline. With a box selected, the editor lays that version's text over
   the current text as a read-only inline diff, so you can read the change before you commit to it.
3. Choose **Apply — full restore** and confirm ("Restore the whole document to version #N?").

Field saves the restore as a new change and keeps the state you restored *from* in the timeline,
so you can return to it. Edits made since the last autosave (5 s) are not in a backup yet; let the
document save before restoring over work you care about. Every edit that changes what is saved counts: a new box, a move, a rename, a
deletion or a connection as well as code. Closing the document and stopping Field both save what the
autosave has not reached yet.

### Restore just a few boxes

When one box has drifted and the rest of your work is good, take only that box. With a version
selected, the change list on the right shows the boxes and properties that differ from the
current document. Click the boxes (or single properties) you want — **Pick Boxes** picks all of
them — and choose **Apply — selected boxes**. Everything you did not pick is left as it is now.

### One box, edit by edit

With a box selected on the canvas, the sidebar's **This Box** list shows the selected property
(usually `code`) of that box across every version, newest first. Saves that left the box alone are
folded together, so a range like `#1–2` is one value the box kept for two saves, and a box that did
not exist yet reads *not in these versions*. Each row shows the first line that value added and how
many lines it added and removed. Click a row to select that version in the timeline.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1440x900" clip="0,0,1440,900" screenshot="assets/document_history/history-box-story.png" transcript="assets/transcripts/document_history_setup.yaml" document="docs-history-box-story" fresh-document=true padding="0" wait="0" timeout="180" embed=true hidden=true name="document-history-box-story" alt="Field's history view with the sidebar's This Box list for the box sketch: four values over five versions, newest first, each with its version range, the first line it added and its added and removed line counts; the row for version #1 is selected and the editor lays that version's code over the current code"}
val uid =
    ui.eval(
            """
            const b = [...document.querySelectorAll(".box")].find(b => (b.textContent || "").includes("sketch"))
            return b ? (b.uid || b.getAttribute("uid") || b.getAttribute("data-uid")) : null
            """
                .trimIndent(),
        )
        .toString()
        .trim('"')
check(uid.isNotBlank() && uid != "null") { "the setup transcript's sketch box is not on the canvas" }
ui.openEditor(uid)
ui.waitForSelector(".monaco-editor", 15000.0)
ui.eval(
    """
    const c = document.querySelector(".monaco-editor-container")
    for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
    if (window.globalEditor && window.globalEditor.layout) window.globalEditor.layout()
    return true
    """
        .trimIndent(),
)
ui.pause(6500)
// Three more saved edits of sketch's code: radius 64, then a ring of points, then radius 80.
val edits = [
    "// Successive radius values, saved as three source versions.\nval radius = 64.0\nprintln(\"radius \" + radius)\n",
    "// Successive radius values, saved as three source versions.\nval radius = 64.0\nval ring = (0 until 12).map { it * 30.0 }\nprintln(\"radius \" + radius + \", \" + ring.size + \" points\")\n",
    "// Successive radius values, saved as three source versions.\nval radius = 80.0\nval ring = (0 until 12).map { it * 30.0 }\nprintln(\"radius \" + radius + \", \" + ring.size + \" points\")\n",
]
for (text in edits) {
    val quoted = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    ui.eval("window.globalEditor.setValue($quoted); return true")
    ui.pause(6500)
}
ui.eval("window.switchToHistoryForFile(window.currentDocumentFilename || \"default\"); return true")
// Wait for the sidebar's This Box list to hold sketch's values.
var story = ""
for (i in 0 until 100) {
    ui.pause(250)
    story =
        ui.eval(
                """
                const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
                const d = f && f.contentDocument
                return "rows=" + (d ? d.querySelectorAll("#box-story-list .box-story-item").length : 0)
                """
                    .trimIndent(),
            )
            .toString()
    if ((Regex("rows=(\\d+)").find(story)?.groupValues?.get(1)?.toInt() ?: 0) >= 4) break
}
check((Regex("rows=(\\d+)").find(story)?.groupValues?.get(1)?.toInt() ?: 0) >= 4) {
    "This Box never listed sketch's four values: " + story
}
ui.pause(800)
// Click the row holding version #1 (radius 64.0), where it is drawn.
val at =
    ui.eval(
            """
            const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
            const fr = f.getBoundingClientRect()
            const row = [...f.contentDocument.querySelectorAll("#box-story-list .box-story-item")].find(r => r.querySelector(".range").textContent.trim() === "#1")
            row.scrollIntoView({ block: "center" })
            const r = row.getBoundingClientRect()
            return [fr.left + r.left + 30, fr.top + r.top + r.height / 2].join(",")
            """
                .trimIndent(),
        )
        .toString()
        .trim('"')
        .split(",")
        .map { it.toDouble() }
ui.moveMouse(at[0], at[1], 6)
ui.clickAt(at[0], at[1])
var shown = ""
for (i in 0 until 60) {
    ui.pause(250)
    shown =
        ui.eval(
                """
                const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
                const d = f.contentDocument
                return "target=" + (d.querySelector("#target-version")?.textContent?.trim() || "") +
                    " active=" + (d.querySelector("#box-story-list .box-story-item.active .range")?.textContent?.trim() || "")
                """
                    .trimIndent(),
            )
            .toString()
    if (shown.contains("target=#1") && shown.contains("active=#1")) break
}
check(shown.contains("target=#1") && shown.contains("active=#1")) {
    "clicking This Box's #1 row did not select version #1: " + shown
}
ui.pause(1200)
```

Choose **Restore** on a row to put that value back into this one property of this box; the rest of
the document is left as it is now. The restore is saved as a new version, so it appears at the top of
the list and the value it replaced stays below it.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1440x900" clip="0,0,1440,900" screenshot="assets/document_history/history-box-story-restored.png" transcript="assets/transcripts/document_history_setup.yaml" document="docs-history-box-restore" fresh-document=true padding="0" wait="0" timeout="180" embed=true hidden=true name="document-history-box-story-restore" alt="After Restore on the This Box row for version #0: the editor holds sketch's first code again, radius 40.0, and This Box lists a new newest value equal to version #0's"}
val uid =
    ui.eval(
            """
            const b = [...document.querySelectorAll(".box")].find(b => (b.textContent || "").includes("sketch"))
            return b ? (b.uid || b.getAttribute("uid") || b.getAttribute("data-uid")) : null
            """
                .trimIndent(),
        )
        .toString()
        .trim('"')
check(uid.isNotBlank() && uid != "null") { "the setup transcript's sketch box is not on the canvas" }
ui.openEditor(uid)
ui.waitForSelector(".monaco-editor", 15000.0)
ui.eval(
    """
    const c = document.querySelector(".monaco-editor-container")
    for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
    if (window.globalEditor && window.globalEditor.layout) window.globalEditor.layout()
    return true
    """
        .trimIndent(),
)
ui.pause(6500)
// Three more saved edits of sketch's code: radius 64, then a ring of points, then radius 80.
val edits = [
    "// Successive radius values, saved as three source versions.\nval radius = 64.0\nprintln(\"radius \" + radius)\n",
    "// Successive radius values, saved as three source versions.\nval radius = 64.0\nval ring = (0 until 12).map { it * 30.0 }\nprintln(\"radius \" + radius + \", \" + ring.size + \" points\")\n",
    "// Successive radius values, saved as three source versions.\nval radius = 80.0\nval ring = (0 until 12).map { it * 30.0 }\nprintln(\"radius \" + radius + \", \" + ring.size + \" points\")\n",
]
for (text in edits) {
    val quoted = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    ui.eval("window.globalEditor.setValue($quoted); return true")
    ui.pause(6500)
}
ui.eval("window.switchToHistoryForFile(window.currentDocumentFilename || \"default\"); return true")
// Wait for the sidebar's This Box list to hold sketch's values.
var story = ""
for (i in 0 until 100) {
    ui.pause(250)
    story =
        ui.eval(
                """
                const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
                const d = f && f.contentDocument
                return "rows=" + (d ? d.querySelectorAll("#box-story-list .box-story-item").length : 0)
                """
                    .trimIndent(),
            )
            .toString()
    if ((Regex("rows=(\\d+)").find(story)?.groupValues?.get(1)?.toInt() ?: 0) >= 4) break
}
check((Regex("rows=(\\d+)").find(story)?.groupValues?.get(1)?.toInt() ?: 0) >= 4) {
    "This Box never listed sketch's four values: " + story
}
ui.pause(800)
// Restore the oldest value, #0 (radius 40.0), from its row's Restore button, accepting the confirmation.
ui.eval("window.confirm = () => true; return true")
val at =
    ui.eval(
            """
            const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
            const fr = f.getBoundingClientRect()
            const rows = [...f.contentDocument.querySelectorAll("#box-story-list .box-story-item")]
            const b = rows.filter(r => r.querySelector("button")).pop().querySelector("button")
            b.scrollIntoView({ block: "center" })
            const r = b.getBoundingClientRect()
            return [fr.left + r.left + r.width / 2, fr.top + r.top + r.height / 2].join(",")
            """
                .trimIndent(),
        )
        .toString()
        .trim('"')
        .split(",")
        .map { it.toDouble() }
ui.moveMouse(at[0], at[1], 6)
ui.clickAt(at[0], at[1])
var after = ""
for (i in 0 until 80) {
    ui.pause(250)
    after =
        ui.eval(
                """
                const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
                const rows = f.contentDocument.querySelectorAll("#box-story-list .box-story-item").length
                return "rows=" + rows + " editor=" + (window.globalEditor.getValue().includes("radius = 40.0"))
                """
                    .trimIndent(),
            )
            .toString()
    if (after.contains("editor=true") && after.contains("rows=5")) break
}
check(after.contains("editor=true")) { "Restore did not put version #0's code back in the editor: " + after }
check(after.contains("rows=5")) { "the restore did not appear as a new value in This Box: " + after }
ui.pause(1200)
```

## What a full restore changes

- Boxes in the chosen version get back the properties they had then.
- Boxes the chosen version did not have are not deleted: they are stopped and marked as removed,
  and they come back if you restore a version that had them.
- The jump is recorded in the sidebar's **Version Jumps** list. Picking boxes is not recorded
  there; it is an ordinary edit, and the next save backs up the state before it.

## Reading the timeline

The newest entry, marked **Current**, is the document as it is now; the others are earlier saves,
numbered `#0` (oldest), `#1`, … Each shows its box count, the size of the saved file and how long
ago it was saved. Counts and diffs for older versions arrive a moment after the list, so an entry
can briefly read `0 boxes`.

- **Saves** / **Time** spaces the timeline one slot per save, or by when each save happened;
  **Focus**, **−** and **+** zoom it.
- **Whole Document** / **Selected Boxes** narrows the page to the box selected on the canvas.
- **Against Current** compares each version with the current document; toggled to
  **Since Previous**, it compares each version with the save before it.
- The sidebar shows the selected version's boxes, properties and lines.

## Find the version you want

- **search changes** keeps only the versions whose changes contain the text you type.
- **Line** keeps only the versions that changed the line your editor cursor is on — a per-line
  blame.
- **name this version** labels the selected version (`#12 before the refactor`); named versions
  are listed in the sidebar, and [Code History](history_lens.md) shows the same labels.

## Where versions come from

History is read from files in the document's folder:

- `metadata.edn` is the current version.
- `backups/metadata_backup_00000.edn`, `…00001.edn`, … are the earlier ones. A save writes one
  only when the document's contents changed, so the timeline holds real saved states, not every
  keystroke. Document backups are never pruned; the history goes back to the document's first
  save. (Sidecar files such as a box's `.kt` keep their own last ten backups in the same folder;
  history does not need them, since each document backup holds the box text too.)
- `backups/<sidecar>.external-<time>` files are disk edits Field overrode. When a sidecar is
  edited outside Field while the same box has an unsaved edit in Field, Field's edit wins and the
  disk text is kept here, with a notice naming the file. They are not versions and do not appear
  in the timeline; the newest ten per sidecar are kept.
- `versions.edn` holds version names and the list of full-restore jumps, for example
  `{"transitions" [{"fromIndex" 4 "toIndex" 0 "timestamp" …}] "names" {1 "radius sixty-four"}}`.
- `history_metrics.edn` records what happened between saves (how many boxes were edited, moved
  or run) for the timeline's per-version statistics. Losing it loses those statistics, not any
  version.

A document that has been saved only once has nothing earlier to show. Deleting backup files
renumbers every later version, and version names follow the numbers, so leave `backups/` alone if
you have named versions.

## Related features

- Use [Code History](history_lens.md) to scrub a single line through its versions without leaving the editor.
- Use a [bookmark](moving_around_the_canvas.md#bookmarks) to return to a saved working view without changing content.
- Use [Interactive Guides](interactive_guides.md) when you want to explain a document state instead of restoring one.
