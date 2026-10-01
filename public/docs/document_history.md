# Document History

Field keeps document backups as you work. The history view lets you inspect those saved versions, compare them, and restore an earlier state — either the whole document or just a few boxes.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1440x900" clip="0,0,1440,900" screenshot="assets/document_history/history-version-selected.png" transcript="assets/transcripts/document_history_setup.yaml" document="docs-history-figure" fresh-document=true padding="0" wait="0" timeout="150" embed=true hidden=true name="document-history-version-selected" alt="Field with the document history view open below a box named sketch and its editor: the timeline holds versions #0, #1 and #2 and the current document, version #1 is selected and differs from the live document in one box, and the editor lays version #1 over the current code as a read-only inline diff, version #1's lines in green (radius 40.0, a println of radius and count) against the current lines in red (radius 64.0, the ring and its println)"}
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
val versions = listOf(
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
)
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

It is for document-level recovery rather than for undoing single edits. To scrub one line back through its versions without leaving the editor, see the [History Lens](history_lens.md).

## Open the history view

You can open history in two ways:

- run `Show Document History` from the command palette
- open `/history?file=<document-name>` directly on the Field server

There is no keyboard shortcut. The history view opens in a dedicated iframe layered over the document, rather than inside a box.

## What the timeline shows

The page builds a vertical timeline of the current document and its backups. Each entry shows:

- a **version label** — `#0`, `#1`, … (and any name you have given it), with the live document marked as **Current**
- **box count** for that version
- the **size** of the saved metadata
- a **relative save time** ("3m", "2d")

The newest entry is the current `metadata.edn`; older entries are the backups Field has rotated to disk. Box counts and diffs for older versions stream in a moment after the list first appears, so an entry may briefly read `0 boxes` until the stats arrive.

The timeline can be viewed against a **Saves** axis (one slot per version) or a **Time** axis (spaced by real save time), and you can zoom and focus it. A **Whole Document / selected box** toggle narrows everything on the page to the box you have selected, and a diff-basis toggle switches between comparing each version **against the current** state and against its immediate predecessor.

If a box is selected, clicking a version also shows it in the editor: the box's text as it was in that version is laid over the current text as a read-only inline diff, so you can read the change in place before you restore anything. The **Line** button next to the search field filters the timeline to the versions that changed the line your editor cursor is on.

## Restore an earlier version

To restore a whole version:

1. Open the history view.
2. Click a version in the timeline.
3. Review the details in the side panel.
4. Choose **Apply — full restore**.
5. Confirm when prompted ("Restore the whole document to version #N?").

Saving the restored result rotates the previous on-disk `metadata.edn` into the backup set, so the
last saved state stays available in history. Unsaved in-memory edits are not in the backup set at
all, so save before a restore you care about.

### Restore just a few boxes

You do not have to take a whole version. Select the boxes you care about and choose **Apply — selected boxes** to cherry-pick only those boxes out of the target version, leaving the rest of your document untouched. Use it when one box has drifted and the rest of your work is good.

## What restore does

Restore rebuilds document state from the target version rather than copying a file over the current one.

Boxes that exist in the chosen version are restored with their saved properties. Boxes that do **not** exist in that older version are **tombstoned** rather than deleted, so the restore stays reversible. Each restore records its own transition, so the jump is itself part of the history.

## Search, blame and named versions

Depending on what you have selected, the panel also offers:

- **Search changes** — filter versions to those that touched a given piece of text.
- **Blame a line** — see which version last changed a particular line.
- **Name a version** — give a meaningful label to a save point so you can find it again (named versions get their own list in the sidebar).
- **Review version jumps** — the sidebar lists the restore transitions you have made, so the act of moving through history is itself recoverable.
- **See per-version metrics** — boxes, properties and lines for the selected version.

## Where versions come from

History is built from the document backup files Field writes while saving. Each save, if the content changed, rotates the previous `metadata.edn` into a `backups/metadata_backup_*.edn` file, and older backups are pruned to a limit. So:

- the history view becomes useful once the document has been saved a few times
- the backup list reflects real saved states, not transient editor changes
- the newest entry corresponds to the current `metadata.edn`

If a document has no backups yet, the history page will have little or nothing to show.

## Related features

- Use the [History Lens](history_lens.md) to scrub a single line through its versions without leaving the editor.
- Use a [bookmark](moving_around_the_canvas.md#bookmarks) to return to a saved working view without changing content.
- Use [Interactive Guides](interactive_guides.md) when you want to explain a document state instead of restoring one.
