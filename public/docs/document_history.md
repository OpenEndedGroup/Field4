# Document History

Every time Field saves a document whose contents changed, it keeps the previous save. The history
view shows those saves as a timeline, lets you compare any of them with what you have now, and
puts back an earlier one — the whole document, or just the boxes you pick.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1440x900" clip="0,0,1440,900" screenshot="assets/document_history/history-version-selected.png" transcript="assets/transcripts/document_history_setup.yaml" document="docs-history-figure" fresh-document=true padding="0" wait="0" timeout="150" embed=true hidden=true name="document-history-version-selected" alt="Field with the document history view open below a box named sketch and its editor: the timeline holds three saves, #0, #1 and the current document, version #1 is selected and differs from the live document in one box, and the editor lays version #1 over the current code as a read-only inline diff, version #1's lines in green (radius 40.0, a println of radius and count) against the current lines in red (radius 64.0, the ring and its println)"}
// The setup box holds version 0 of its code. Two edits through the editor, each left for the
// 5 s autosave, give the document's backups three real versions; then the history view opens and
// the middle version is clicked, which lays that version's text over the editor as a diff.
val uid = ui.eval("""
    const b = [...document.querySelectorAll(".box")].find(b => (b.textContent || "").includes("sketch"))
    return b ? (b.uid || b.getAttribute("uid") || b.getAttribute("data-uid")) : null
""".trimIndent()).toString().trim('"')
check(uid.isNotBlank() && uid != "null") { "the setup transcript's sketch box is not on the canvas" }
ui.openEditor(uid)
ui.waitForSelector(".monaco-editor", 15000.0)
// Lay the editor pane out for this window, as a user's browser would: screenshot prep pins it to a
// size and place meant for a much larger page.
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
    val radius = 40.0
    val count = 12
    println("radius " + radius + ", count " + count)
    """.trimIndent(),
    """
    // A ring of points, saved three times.
    val radius = 64.0
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
    window.switchToHistoryForFile(window.currentDocumentFilename || "default")
    return window.currentDocumentFilename
""".trimIndent())
// Wait for the rail to show the three saved versions and the current one.
var rail = ""
for (i in 0 until 80) {
    ui.pause(250)
    rail = ui.eval("""
        const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
        const d = f && f.contentDocument
        const dots = d ? d.querySelectorAll("#rail [data-version-index]").length : 0
        return "dots=" + dots
    """.trimIndent()).toString()
    if ((Regex("dots=(\\d+)").find(rail)?.groupValues?.get(1)?.toInt() ?: 0) >= 3) break
}
check((Regex("dots=(\\d+)").find(rail)?.groupValues?.get(1)?.toInt() ?: 0) >= 3) { "the history rail never showed three versions: " + rail }
ui.pause(1200)
// Click version #1 where it is drawn, in page coordinates.
val at = ui.eval("""
    const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
    const fr = f.getBoundingClientRect()
    const dot = f.contentDocument.querySelector('#rail [data-version-index="1"]')
    const r = dot.getBoundingClientRect()
    return [fr.left + r.left + r.width / 2, fr.top + r.top + r.height / 2].join(",")
""".trimIndent()).toString().trim('"').split(",").map { it.toDouble() }
ui.moveMouse(at[0], at[1], 6)
ui.clickAt(at[0], at[1])
var shown = ""
for (i in 0 until 60) {
    ui.pause(250)
    shown = ui.eval("""
        const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
        const target = f.contentDocument.querySelector("#target-version")?.textContent?.trim() || ""
        const zones = document.querySelectorAll(".monaco-editor .view-zones > div").length
        const decorations = document.querySelectorAll(".monaco-editor [class*='aidiff'], .monaco-editor [class*='ai-diff']").length
        return "target=" + target + " zones=" + zones + " decorations=" + decorations
    """.trimIndent()).toString()
    if (shown.contains("target=#1") && !shown.contains("zones=0 decorations=0")) break
}
check(shown.contains("target=#1")) { "clicking version #1 did not select it: " + shown }
check(!shown.contains("zones=0 decorations=0")) { "version #1 is selected but the editor shows no diff: " + shown }
ui.moveMouse(5.0, 5.0, 2)
ui.pause(800)
```

Above, an earlier save of the box `sketch` is selected in the timeline, and the editor shows how it differs from the current code. To scrub one line back through its versions without leaving the editor, use [Code History](history_lens.md) instead.

## Get back an earlier version

1. Run **Show Document History** from the command palette. (There is no keyboard shortcut; you
   can also open `/history?file=<document-name>` on the Field server.) The history view opens over
   the document.
2. Click a version in the timeline. With a box selected, the editor lays that version's text over
   the current text as a read-only inline diff, so you can read the change before you commit to it.
3. Choose **Apply — full restore** and confirm ("Restore the whole document to version #N?").

The restore is itself saved, so the state you restored *from* becomes the newest backup and
stays in the timeline: a restore can always be undone by restoring again. Edits made since the
last autosave (5 s) are not in any backup, so let the document save before you restore over work
you care about.

### Restore just a few boxes

When one box has drifted and the rest of your work is good, take only that box. With a version
selected, the change list on the right shows the boxes and properties that differ from the
current document. Click the boxes (or single properties) you want — **Pick Boxes** picks all of
them — and choose **Apply — selected boxes**. Everything you did not pick is left as it is now.

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
- `versions.edn` holds version names and the list of full-restore jumps.

A document that has been saved only once has nothing earlier to show. Deleting backup files
renumbers every later version, and version names follow the numbers, so leave `backups/` alone if
you have named versions.

## Related features

- Use [Code History](history_lens.md) to scrub a single line through its versions without leaving the editor.
- Use a [bookmark](moving_around_the_canvas.md#bookmarks) to return to a saved working view without changing content.
- Use [Interactive Guides](interactive_guides.md) when you want to explain a document state instead of restoring one.
