# Snapping

Field can snap boxes to each other while you drag or resize them. Turn it on with Caps Lock when
you want to tidy a layout, and off again when you go back to sketching.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x330" clip="0,0,460,330" hide-toasts=true video="assets/snapping/snapping.mp4" fps="20" transcript="assets/transcripts/snapping_setup.yaml" padding="0" wait="0" timeout="180" embed=true hidden=true name="snapping-guide" alt="Four boxes: left and right in a row with a wide gap between them, mover below them, and narrow at the bottom. F1 opens the command palette and Toggle Box Snapping turns snapping on. Mover is dragged left until a cyan guide line joins its left edge to left's, and it snaps into line. Then it is dragged up into the gap: guides show its top level with the other two, with tick marks for the equal gaps either side, and it lands evenly spaced. Last, narrow's right edge is dragged out until a guide meets mover's right edge, and it snaps to that width."}
// The setup transcript makes left and right (a row with a gap), mover and narrow. The window is
// wider than the clip so a selected box's editor opens beside the boxes, as on a real screen; lay
// it out for this window, since screenshot prep pins it to a size meant for a larger page.
fun freeEditorLayout() {
    ui.eval("""
        const c = document.querySelector(".monaco-editor-container")
        if (c) for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
        if (window.globalEditor && window.globalEditor.layout) window.globalEditor.layout()
        return true
    """.trimIndent())
}
fun rect(name: String): List<Double> = ui.eval("""
    const b = [...document.querySelectorAll(".box")].find(b => (b.name || "") === "$name" || (b.textContent || "").trim() === "$name")
    if (!b) return null
    const r = b.getBoundingClientRect()
    return [r.left, r.top, r.right, r.bottom].join(",")
""".trimIndent()).toString().trim('"').split(",").map { it.toDouble() }
fun guides(): Int = ui.eval("return document.querySelectorAll('#snap-guides > *').length").toString().toDouble().toInt()
// A hand-paced drag from (x0, y0) to (x1, y1), held at the end so the guides can be read.
fun drag(x0: Double, y0: Double, x1: Double, y1: Double, what: String) {
    ui.moveMouse(x0, y0, 6)
    ui.pause(300)
    val under = ui.eval("return !!document.elementFromPoint($x0, $y0)?.closest('.box')").toString()
    check(under == "true") { "something covers the box at ($x0, $y0) before dragging $what" }
    ui.mouseDown()
    for (i in 1..24) {
        ui.moveMouse(x0 + (x1 - x0) * i / 24, y0 + (y1 - y0) * i / 24)
        ui.pause(35)
    }
    ui.pause(900)
    check(guides() > 0) { "no snap guides while dragging $what" }
    ui.mouseUp()
    ui.pause(500)
    // Deselect, so the selected box's editor does not cover the next drag.
    ui.clickAt(440.0, 320.0)
    ui.pause(500)
}
fun toggleSnapping() {
    ui.press("F1")
    ui.waitForSelector(".afield-palette-shell", 8000.0)
    ui.pause(500)
    ui.typeSlowly("box snapping", 90)
    ui.pause(600)
    ui.press("Enter")
    ui.pause(700)
}
freeEditorLayout()
// Click the empty canvas so the palette opens for the document, then turn snapping on.
ui.moveMouse(420.0, 300.0, 8)
ui.clickAt(420.0, 300.0)
ui.pause(500)
toggleSnapping()
// Edge alignment: bring mover's left edge to within a few px of left's.
var m = rect("mover")
val l = rect("left")
drag((m[0] + m[2]) / 2, (m[1] + m[3]) / 2, (m[0] + m[2]) / 2 + (l[0] + 6 - m[0]), (m[1] + m[3]) / 2 - 10, "mover to left's edge")
check(kotlin.math.abs(rect("mover")[0] - l[0]) < 1.0) { "mover did not snap to left's edge" }
// Even spacing: into the gap, a little off centre and a little low.
m = rect("mover")
val r = rect("right")
val w = m[2] - m[0]
val centred = l[2] + (r[0] - l[2] - w) / 2
drag((m[0] + m[2]) / 2, (m[1] + m[3]) / 2, (m[0] + m[2]) / 2 + (centred + 6 - m[0]), (m[1] + m[3]) / 2 + (l[1] + 4 - m[1]), "mover into the gap")
m = rect("mover")
check(kotlin.math.abs((m[0] - l[2]) - (r[0] - m[2])) < 1.0) { "mover is not evenly spaced: $m" }
// Resize: narrow's right edge out to just short of mover's.
val n = rect("narrow")
drag(n[2] - 3, (n[1] + n[3]) / 2, m[2] - 5, (n[1] + n[3]) / 2, "narrow's right edge")
check(kotlin.math.abs(rect("narrow")[2] - m[2]) < 1.0) { "narrow's right edge did not snap to mover's" }
ui.pause(800)
```

## Turn snapping on

Snapping is active while Caps Lock is on, or after you run **Toggle Box Snapping** from the
command palette (`F1` over the canvas), which stays on until you run it again.

1. Press `Caps Lock`.
2. Drag a box near another box.
3. Release the mouse when the guide line lands where you want it.

When neither is on, dragging and resizing stay freeform.

## What snaps

Field looks for nearby layout relationships on each axis independently. That means one drag can snap horizontally and vertically at the same time.

Field can snap to:

- left-edge alignment
- right-edge alignment
- top-edge alignment
- bottom-edge alignment
- center alignment
- even horizontal spacing between neighboring boxes
- even vertical spacing between neighboring boxes

During a snap, Field draws cyan guide lines and tick marks so you can see what relationship it found.

## Edge and center alignment

- Drag near the left or right edge of another box to align vertical edges.
- Drag near the top or bottom edge of another box to align horizontal edges.
- Drag near another box's center line to align centers.

## Even spacing

If a dragged box can land between two other boxes with equal spacing, Field offers that as a snap
target and draws measurement ticks on the guide line, which is how you get an evenly spaced row or
a regular vertical stack without measuring by hand. Gaps under 5 px do not count as spacing, so a
tight cluster of boxes offers nothing to space evenly.

## Resize snapping

With Caps Lock on, the edge you drag while resizing snaps to nearby box edges, so adjacent boxes
can share a boundary.

## Thresholds

A box jumps to a snap once it is close enough to it. Alignment and spacing use different distances, and alignment draws its guide before it will take
the snap.

<div class="field-data-table" markdown>

| Alignment | guide is drawn within | box jumps within |
| --- | --- | --- |
| edge and center alignment | 20 px | 10 px |
| even spacing | 30 px | 30 px |

</div>

So a spacing snap engages anywhere its guide is visible, while an alignment guide can appear while
you are still too far out for the box to move. Resizing uses the same 10 px as alignment. Drag near
the relationship you want and watch for the guide line.

## When to use it

Snapping helps when you arrange a document for presentation, or tidy a graph after a run of
exploratory edits. It gets in the way while sketching, which is why it is off until you press
Caps Lock.

## Related features

- Use [Interactive Guides](interactive_guides.md) when the layout itself is part of a walkthrough.
- Use a [bookmark](moving_around_the_canvas.md#bookmarks) to save a useful view once you have arranged it.
- Use [Document History](document_history.md) if you want to roll back to an earlier document state rather than manually undoing a large layout change.
