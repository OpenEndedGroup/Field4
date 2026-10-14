# Moving around the canvas

A Field document is usually bigger than the window. You can pan and zoom with the mouse or trackpad; split the canvas so headings or labels stay in view while the rest
scrolls; step from box to box with the keyboard, by tapping `Cmd` twice and holding it; bring the
selection into view with `alt/option-F`; or jump to one of nine views you have saved. All of them change
what you are looking at and never the document itself.

## Panning and zooming

- **Scroll** with a mouse wheel or two fingers on a trackpad to pan the canvas. Over a box's output
  area the scroll goes to the output instead.
- **Drag with the middle mouse button** to pan.
- **Hold `Ctrl` or `Cmd` while scrolling**, or pinch on a trackpad, to zoom about the pointer: the
  point under it stays put. The zoom runs from 10% to 400%.
- **Reset Canvas Zoom** in the command palette returns to 100%, keeping the centre of the window
  where it is.
- **`alt/option-H`** (**Reset View** in the command palette, and a button at the top of the bookmarks
  panel) also returns to 100%, and closes every output panel. Use it when a panel has grown to fill
  the window: the wheel and pinch then go to the panel, so you cannot zoom or scroll away from it.

## Splitting the canvas

Like a spreadsheet's split, the canvas can be divided into panes that keep part of a document in
view while you move around the rest: a row of headings along the top, a column of labels
down the side, or both. Here the body of a sequencer-like document has been scrolled right and down.
The bar headings moved sideways with it, the instrument labels moved up with it, and the corner stayed
put:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x640" clip="0,0,1100,640" screenshot="assets/moving_around_the_canvas/split_canvas.png" transcript="../../dev_docs/agents/transcripts/docs_capture/split_canvas.yaml" padding="0" wait="0" timeout="240" hide-toasts=true embed=true hidden=true name="split-canvas" alt="Field's canvas split into four panes by a black cross with rounded inner corners. The top-left pane is empty corner space. Along the top, a row of boxes named bar 3 to bar 7. Down the left, boxes named bass, keys, pad and fx. The large bottom-right pane holds the pattern boxes for those bars and instruments, each under its bar heading and level with its instrument label, because the body was scrolled right and down and the headings and labels scrolled with it."}
// The split is made the way a reader makes it: drag the grip in the canvas's top-left corner out
// to the corner between the labels and the headings, and let go.
ui.moveMouse(6.0, 6.0)
ui.mouseDown()
ui.moveMouse(196.0, 114.0, 14)
ui.mouseUp()
// Off the divider it was released on, so its hover stripe is not lit in the shot.
ui.moveMouse(700.0, 420.0, 4)
ui.pause(500)
val split = ui.eval("const s = window.canvasPanesState(); return s.rows + 'x' + s.columns").toString()
check("2x2" in split) { "the grip did not split the canvas into four panes: " + split }
// Scroll the body (the bottom-right pane) right and down, as a trackpad would.
ui.eval("""
  const pane = document.querySelectorAll(".field-pane")[3];
  const r = pane.getBoundingClientRect();
  pane.dispatchEvent(new WheelEvent("wheel", { deltaX: 380, deltaY: 200, clientX: r.right - 40, clientY: r.bottom - 40, bubbles: true, cancelable: true }));
  return true;
""")
ui.pause(900)
```

To split it:

- **Drag the grip** in the top-left corner of the canvas out to where you want the dividers, and let
  go. Let go near the top edge for a left and a right pane only, or near the left edge for a top and
  a bottom pane only.
- Or use **Split Canvas Here**, **Split Canvas Columns Here** or **Split Canvas Rows Here** in the
  command palette, which add a divider (or one of each) where the pointer was. Run them again on a
  split canvas for more panes: up to three across and three down. The palette's preview for each of
  them is a small picture of what the canvas will look like, with the new dividers in cyan.

Splitting changes nothing on screen until you scroll. Then panes in the same row scroll up and down
together, panes in the same column scroll side to side together, and zooming zooms every pane.

Each pane shows its own part of the document, on its side of the dividers: a box belongs to the pane
that holds its top-left corner. Drag a box across a divider and it moves into the other pane, staying
where you let go of it.

A pane in the middle of three shows the strip of the document between its two dividers. If you
widen the pane past the end of that strip, the rest of it is plain grey, with no dots: there is
nothing more of the document for that pane to show.

- **Drag a divider** to move it. The boxes beside it move with it.
- **Drag a divider to the edge of the window**, or double-click it, to remove it.
- **Remove Canvas Split** in the command palette removes them all. Every box stays where it is.

Connections between boxes in different panes are drawn straight across the dividers, and follow
the boxes as the panes scroll. Here `keys 4` is selected, so its connections show: its parents are
its instrument label and its bar heading, and its children are `mix` in the corner and two pattern
boxes beside it. Then the body scrolls, and each connection stays attached to the box at its other
end in whichever pane that box is in:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x640" clip="0,0,1100,640" video="assets/moving_around_the_canvas/split_canvas_connections.mp4" fps="20" transcript="../../dev_docs/agents/transcripts/docs_capture/split_canvas_connections.yaml" padding="0" wait="0" timeout="300" hide-toasts=true embed=true hidden=true name="split-canvas-connections" alt="A sequencer-like document split into four panes by a black cross. The box keys 4 in the body is selected, with connections to keys in the label column, bar 4 in the heading row, mix in the corner and two pattern boxes beside it. The body scrolls right and down and then back: bar headings slide sideways and labels slide up with it, and the curved connections bend and settle as their ends move apart and together across the dividers."}
// Split at the corner between the labels and the headings, the way a reader does: drag the grip.
ui.moveMouse(6.0, 6.0)
ui.mouseDown()
ui.moveMouse(196.0, 114.0, 14)
ui.mouseUp()
ui.moveMouse(700.0, 420.0, 4)
ui.pause(500)
val split = ui.eval("const s = window.canvasPanesState(); return s.rows + 'x' + s.columns").toString()
check("2x2" in split) { "the grip did not split the canvas into four panes: " + split }

// Select keys 4, so its connections show. Selecting opens the editor, which would cover the canvas.
ui.click("sp_keys4")
ui.pause(300)
ui.eval("window.hideMonaco(); return true")
ui.pause(1500)

// Scroll the body (the bottom-right pane) in small steps, as a trackpad does, then back again,
// pausing each time for the connections to settle.
fun scrollBody(dx: Double, dy: Double) {
    ui.eval("""
      const pane = document.querySelectorAll(".field-pane")[3];
      const r = pane.getBoundingClientRect();
      pane.dispatchEvent(new WheelEvent("wheel", { deltaX: $dx, deltaY: $dy, clientX: r.right - 60, clientY: r.bottom - 60, bubbles: true, cancelable: true }));
      return true;
    """)
}
repeat(40) { scrollBody(9.0, 5.0); ui.pause(30) }
ui.pause(2500)
repeat(40) { scrollBody(-6.0, -4.0); ui.pause(30) }
ui.pause(2500)
```

The drawing layer (`alt/option-D`) works in every pane: its toolbar is in the bottom-right pane, and the
tool you pick there follows the pointer into the others.

The split is saved with the document. A bookmark saved while the canvas is split restores the split
and where each pane was scrolled to.

### Unlinking a pane

**Unlink Pane**, **Unlink Pane Horizontally** and **Unlink Pane Vertically** in the command
palette let the pane under the pointer scroll on its own, in both directions or just one, instead of
with its row or column: a corner of notes that stays where you left it, say, or a column of labels
you scroll through separately. The stretches of divider between an unlinked pane and the panes it
no longer moves with turn dashed. **Relink Pane** puts it back in step with its row and column.

Here the instrument labels are unlinked vertically from the palette (its preview shows the divider
beside them turning dashed), so the body scrolls down without them, and then they scroll on their
own. **Relink Pane** brings them back into line with the body:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x640" clip="0,0,1100,640" video="assets/moving_around_the_canvas/unlink_pane.mp4" fps="20" transcript="../../dev_docs/agents/transcripts/docs_capture/split_canvas.yaml" padding="0" wait="0" timeout="300" hide-toasts=true embed=true hidden=true name="unlink-pane" alt="A sequencer-like document split into four panes by a black cross, bar headings along the top and instrument labels down the left. With the pointer over the labels, the command palette opens and Unlink Pane Vertically is typed; its preview is a thumbnail of the split with the divider beside the labels drawn as cyan dashes. After Enter that divider turns dashed. The body scrolls down while the labels stay put, then the labels scroll on their own. The palette opens again for Relink Pane, and the labels jump back into line with their rows, the divider solid again."}
// Split at the corner between the labels and the headings, the way a reader does: drag the grip.
ui.moveMouse(6.0, 6.0)
ui.mouseDown()
ui.moveMouse(196.0, 114.0, 14)
ui.mouseUp()
ui.pause(400)
val split = ui.eval("const s = window.canvasPanesState(); return s.rows + 'x' + s.columns").toString()
check("2x2" in split) { "the grip did not split the canvas into four panes: " + split }

fun scrollPane(index: Int, dx: Double, dy: Double) {
    ui.eval("""
      const pane = document.querySelectorAll(".field-pane")[$index];
      const r = pane.getBoundingClientRect();
      pane.dispatchEvent(new WheelEvent("wheel", { deltaX: $dx, deltaY: $dy, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, cancelable: true }));
      return true;
    """)
}
fun palette(command: String) {
    // The pane under the pointer is the one a pane command acts on: the labels, bottom left.
    ui.moveMouse(90.0, 400.0, 8)
    ui.pause(300)
    ui.press("F1")
    ui.waitForSelector(".afield-palette-shell", 8000.0)
    ui.pause(400)
    ui.typeSlowly(command, 70)
    ui.pause(1600)
    ui.press("Enter")
    ui.pause(900)
}

palette("unlink pane vertically")
val free = ui.eval("return String(window.canvasPanesState().panes[2].freeY)").toString()
check("true" in free) { "the labels pane was not unlinked: " + free }
// The body scrolls down; the labels stay where they are.
ui.moveMouse(700.0, 420.0, 6)
repeat(30) { scrollPane(3, 0.0, 6.0); ui.pause(30) }
ui.pause(900)
// The labels scroll on their own.
repeat(24) { scrollPane(2, 0.0, 5.0); ui.pause(30) }
ui.pause(900)

palette("relink pane")
val linked = ui.eval("return String(window.canvasPanesState().panes[2].freeY)").toString()
check("false" in linked) { "the labels pane was not relinked: " + linked }
ui.pause(1500)
```

## Stepping between boxes

With a box selected, tap `Cmd` twice and hold it down on the second tap: Field outlines the box you
are on and every box an arrow key can reach from it. That moves the selection around a document
without touching the mouse, and leaves you with a box selected and its code in the editor.

### Cmd Cmd and hold

1. Select a box.
2. Tap `Cmd` twice quickly, holding it down on the second tap.
3. Press an arrow key to move to the box in that direction.
4. Release `Cmd`.

The two taps have to land within about a third of a second, with no other key pressed in between and
no other modifier held. `Ctrl Ctrl` does the same thing, and whichever key you started with is the one
you have to keep holding. Nothing happens if no box is selected, since the overlay has to draw itself
around something.

Each arrow selects the box in that direction, scrolls it into view, and redraws the overlay around
its new position, so you can keep arrowing without letting go. `Escape` leaves the mode early, and so
does clicking away from the window.

Letting go of the trigger key puts the focus in the editor of whichever box you landed on, ready to
type.

### What the overlay draws

While the mode is up, Field draws over the canvas:

- a solid outline around the box you are on
- a dashed outline around each box you can reach, up to four, one per direction
- a line from your box to each of those, and an arrow badge on the far side of each one
- an `F` chip on any box that a bookmark slot points at

A direction with no box in it is absent from the overlay, and pressing that arrow does nothing.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-spatial-nav-frame" screenshot="assets/spatial_navigation/overlay.png" transcript="../../dev_docs/agents/transcripts/docs_capture/spatial_navigation_overlay.yaml" padding="18" wait="0" timeout="240" embed=true hidden=true name="spatial-nav-overlay" alt="Field's spatial-navigation overlay, live. A solid outline surrounds the box named curve. Dashed outlines surround palette above it, render to its right and export below it, each joined to curve by a thin line and carrying an arrow badge on its far side: up, right and down. Nothing is drawn to the west of curve, which has no box. The palette outline also wears an F2 chip and the render outline an F5 chip, the two bookmark slots saved on those boxes."}
// Two bookmark slots, each saved with exactly one box selected: captureSelectedBox() records a
// uid only in that case, and the F chips are drawn from those uids.
ui.click("spatial_nav_north")
ui.pause(250)
ui.down("Meta"); ui.down("Shift"); ui.press("F2"); ui.up("Shift"); ui.up("Meta")
ui.pause(250)
ui.click("spatial_nav_east")
ui.pause(250)
ui.down("Meta"); ui.down("Shift"); ui.press("F5"); ui.up("Shift"); ui.up("Meta")
ui.pause(250)

// Stand on the anchor, then wait out the viewport animation. ensureBoxVisibleForNavigation runs a
// 420ms animateViewportTranslation, and a shot fired inside that window catches the overlay at one
// pan offset and the boxes at another. That is what made the previous capture of this page wrong.
ui.click("spatial_nav_center")
ui.pause(1400)

// The trigger: Meta down, up, down inside 360ms, with no other keydown between the taps and no
// other modifier held. Meta stays down from here on, because its keyup tears the mode down again.
ui.down("Meta"); ui.up("Meta"); ui.down("Meta")
ui.pause(400)

// Wait for the overlay to agree with the canvas, then frame the shot. Every highlight is its box's
// rect inflated by spatialNavPadding, so any other offset means the two disagree about where the
// canvas is -- which is exactly the defect this figure replaces: ensureBoxVisibleForNavigation
// animates the viewport for 420ms and the old capture fired inside that window. Polling rather
// than sleeping a guessed interval is the point, since the pass below both measures the offset and,
// only once it is zero, parks a zero-ink crop marker on the union of the overlay for
// clip-selector to frame. That union is the one collectGuideScreenshotRects builds for the box
// capture path, which cannot be used here because that path pans and drops the selection first.
var report = ""
var tries = 0
while (tries < 24 && !report.contains("overlay-settled")) {
    if (tries > 0) ui.pause(250)
    tries++
    report = (ui.eval("""
  const pad = 12;
  const boxes = Array.from(document.getElementsByClassName("box"));
  const uidOf = (el) => el.uid || el.getAttribute("uid") || el.getAttribute("data-uid") || "";
  const rectOf = (el) => el.getBoundingClientRect();
  const highlights = Array.from(document.querySelectorAll(".spatial-nav-highlight"));
  const arrows = Array.from(document.querySelectorAll(".spatial-nav-badge"));
  const chips = Array.from(document.querySelectorAll(".spatial-nav-bookmark-badge"));
  if (highlights.length === 0) {
    return "overlay-unsettled: nothing drawn, so the double tap did not activate the mode";
  }

  let worst = 0;
  let worstUid = "";
  for (const h of highlights) {
    const hr = rectOf(h);
    let best = null;
    for (const b of boxes) {
      const br = rectOf(b);
      if (br.width <= 0 || br.height <= 0) continue;
      const off = Math.abs((br.left - pad) - hr.left) + Math.abs((br.top - pad) - hr.top);
      if (best === null || off < best.off) best = { off: off, uid: uidOf(b) };
    }
    if (best !== null && best.off > worst) { worst = best.off; worstUid = best.uid; }
  }

  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  highlights.concat(arrows).concat(chips).forEach((el) => {
    const r = rectOf(el);
    if (r.width <= 0 || r.height <= 0) return;
    x0 = Math.min(x0, r.left); y0 = Math.min(y0, r.top);
    x1 = Math.max(x1, r.right); y1 = Math.max(y1, r.bottom);
  });

  const anchors = highlights.filter((h) => h.classList.contains("anchor")).length;
  const targets = highlights.length - anchors;
  const summary = " anchor=" + anchors + " targets=" + targets +
    " arrows=" + arrows.map((a) => a.textContent).join("") +
    " chips=" + chips.map((c) => c.textContent).join(",") +
    " drift=" + (Math.round(worst * 10) / 10) + (worstUid === "" ? "" : "@" + worstUid) +
    " frame=" + Math.round(x1 - x0) + "x" + Math.round(y1 - y0);

  if (anchors !== 1) return "overlay-unsettled:" + summary + " (wanted exactly one solid rect)";
  if (targets < 3) return "overlay-unsettled:" + summary + " (wanted three dashed rects)";
  if (chips.length < 1) return "overlay-unsettled:" + summary + " (wanted a bookmark chip)";
  if (worst > 1.5) {
    return "overlay-unsettled:" + summary + " (a rect is off its box, so the canvas moved under the overlay)";
  }

  let frame = document.getElementById("docs-spatial-nav-frame");
  if (frame === null) {
    frame = document.createElement("div");
    frame.id = "docs-spatial-nav-frame";
    document.body.appendChild(frame);
  }
  frame.setAttribute("style", "position:fixed;pointer-events:none;background:transparent;border:0;" +
    "left:" + x0 + "px;top:" + y0 + "px;width:" + (x1 - x0) + "px;height:" + (y1 - y0) + "px;");
  return "overlay-settled:" + summary;
""") ?: "").toString()
}

// Worth knowing how this reports: the runner appends its own ui.shotClip after this script, and a
// thrown error here does not stop it -- the REPL runs the next top-level statement anyway, and the
// error lands in `data.output` of the macro reply, which the runner's error extractor does not read
// (it only scans `events`). So a give-up here does not fail the build by itself. What it does do is
// leave the crop marker absent, and shotClip with a missing selector writes nothing and fails the
// drive op, so the build reports the selector instead of committing a full-window PNG.
if (!report.contains("overlay-settled")) {
    error("spatial navigation overlay never settled after " + tries + " tries: " + report)
}
```

## Framing the selection

`alt/option-F` (**Frame Selected** in the command palette, rebindable as `frame-selected`) brings the
selected boxes, and their open output panels, into view. It puts them in the largest part of the
window that the editor, the docs panel and Field's own panels leave uncovered. If they do not fit
there it zooms out until they do, and if the canvas was zoomed further out than that it zooms back
in, up to 100%. It never zooms in past that.

If none of the uncovered space could show the selection at even half the size the whole window
could, it uses the whole window instead and lets the editor cover part of it. A narrow strip beside
a wide editor is not worth squinting at.

## Bookmarks

Arrows reach neighbours. For everywhere else there are nine numbered slots.

- Save the current view: `ctrl/command-shift-F1` through `ctrl/command-shift-F9`
- Restore a saved view: `shift-F1` through `shift-F9`

`Cmd` is Command on macOS and Control everywhere else. Both sets are rebindable as
`save-bookmark-N` and `restore-bookmark-N`, and both appear in the command palette as
`Save Bookmark N` and `Restore Bookmark N`. See [Keyboard Shortcuts](keybindings.md).

A slot stores a view rather than a box reference. Restoring one reapplies, in this order:

- the canvas pan position and zoom, and the split with each pane's position if the canvas was split
- the open or closed state of the nested disclosures, before anything is selected
- the size of each output panel that was open, and the open or closed state of every output
  panel. A panel that did not exist when the slot was saved, or no longer exists, is left as it is
- the selected box, when exactly one box was selected at save time
- the property that was open in Monaco
- the editor cursor line and column

### The bookmarks panel

`alt/option-B` (**Show Bookmarks** in the command palette, rebindable as `show-bookmarks`) opens a panel
with a row for each of the nine slots, so you can see what a slot holds before you jump to it:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#bookmarks-panel" screenshot="assets/moving_around_the_canvas/bookmarks_panel.png" transcript="../../dev_docs/agents/transcripts/docs_capture/bookmarks_panel.yaml" padding="8" wait="0" timeout="240" embed=true hidden=true name="bookmarks-panel" alt="The bookmarks panel. Nine rows, F1 to F9. F1, named ideas, and F2, named render, are saved: each shows a small map of the document's three boxes with the saved view drawn as a dashed rectangle and the selected box in the accent colour, the view position, the box that was being edited, how many output panels were open and closed with a pill naming each open one and its size, and three options, restore view, restore selection and close unrecorded panels. Each has Restore, Save and delete buttons. The other seven rows read empty, with a Save here button."}
// Slot 1: `sketch` selected with its editor open. Slot 2: the same for `render`.
ui.openEditor("bm_sketch")
ui.pause(900)
ui.down("Meta"); ui.down("Shift"); ui.press("F1"); ui.up("Shift"); ui.up("Meta")
ui.pause(300)
ui.openEditor("bm_render")
ui.pause(900)
ui.down("Meta"); ui.down("Shift"); ui.press("F2"); ui.up("Shift"); ui.up("Meta")
ui.pause(300)
ui.down("Alt"); ui.press("KeyB"); ui.up("Alt")
ui.waitForSelector("#bookmarks-panel.is-visible", 8000.0)
// Name the two slots through the panel's own name fields, the way a reader would.
ui.eval("""
  const names = Array.from(document.querySelectorAll("#bookmarks-panel input[type=text]"));
  if (names.length < 2) return "only " + names.length + " saved slots";
  [["ideas", 0], ["render", 1]].forEach(([value, i]) => {
    names[i].value = value;
    names[i].dispatchEvent(new Event("change"));
  });
  return "named";
""")
ui.pause(600)
```

Each saved slot shows:

- a small map of the document, with the saved view as a dashed rectangle (one for each pane of a
  split canvas) and the selected box in the accent colour. The boxes are drawn where they are now,
  not where they were when the slot was saved
- the view position (and the zoom, when it is not 100%), and the box that was selected or being
  edited, with the cursor line
- how many output panels were open and closed, and a pill for each open one with its size

Click the name to rename a slot. **Restore** and **Save** do what `Shift+F`*n* and
`ctrl/command-shift-F`*n* do, and **×** empties the slot. The three checkboxes are saved with the slot:

- **restore view**: pan and zoom back to the saved view. Turn it off for a slot that should rearrange
  output panels without moving the canvas.
- **restore selection**: reselect the box and reopen its editor and cursor. With **restore view**
  off, the box is also brought into view.
- **close unrecorded panels**: also close every output panel the slot has no record of, instead of
  leaving it as it is. Use it for a slot that should leave the canvas tidy.

Saving over a slot keeps its name and its options.

While the navigation overlay is up, every box on screen that a slot points at wears an `F` chip
with that slot's number: `F3` is the view saved in slot 3, reached with `shift-F3`. A box wears
more than one chip if several slots were saved on it.

Bookmarks live on the root box as the `bookmarks` property and are saved with the rest of the
document state. They survive a reload, and they travel with the document rather than with your
browser session.

## How the neighbours are chosen

Field sorts the visible boxes by the dominant axis of their offset from the current one. A box
further to the left than it is above counts as west, and so on. Within each direction it takes the
nearest, counting sideways distance as a fifth of distance along the direction of travel, so a box
that is slightly off-axis does not beat one that is directly ahead.

Only one box per direction is offered, so a dense column collapses to its nearest member. Boxes that
are hidden, deleted, or drawn at zero size are not candidates.

## Limits

- one target per direction, so the overlay never offers a choice between two boxes on the same side
- a slot stores a single selected box, so a multi-selection is not restored as a set
- a slot with no saved zoom restores the pan position at whatever zoom you are at now
- there are nine slots and the numbering is fixed
- restoring a slot you have not set yet shows a notification and leaves the view alone
- box code that reads `viewport`, or reacts to it with `onViewportChange`, sees the bottom-right
  pane of a split canvas only

## Related features

- [Keyboard Shortcuts](keybindings.md) covers rebinding, and lists both commands for all nine slots.
- [Snapping](snapping.md) is the other half of arranging a document you intend to navigate this way.
- [Document History](document_history.md) saves the document's content rather than your view of it.
