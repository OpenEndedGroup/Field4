# Moving around the canvas

A Field document is usually bigger than the window. There are four ways to move around it: pan and
zoom with the mouse or trackpad; step from box to box with the keyboard, by tapping `Cmd` twice and
holding it; bring the selection into view with `Alt+F`; or jump to one of nine views you have saved.
All of them change what you are looking at and never the document itself.

## Panning and zooming

- **Scroll** with a mouse wheel or two fingers on a trackpad to pan the canvas. Over a box's output
  area the scroll goes to the output instead.
- **Drag with the middle mouse button** to pan.
- **Hold `Ctrl` or `Cmd` while scrolling**, or pinch on a trackpad, to zoom about the pointer: the
  point under it stays put. The zoom runs from 10% to 400%.
- **Reset Canvas Zoom** in the command palette returns to 100%, keeping the centre of the window
  where it is.

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

### How the four are chosen

Field sorts the visible boxes by the dominant axis of their offset from the current one. A box
further to the left than it is above counts as west, and so on. Within each direction it takes the
nearest, counting sideways distance as a fifth of distance along the direction of travel, so a box
that is slightly off-axis does not beat one that is directly ahead.

Only one box per direction is offered, so a dense column collapses to its nearest member. Boxes that
are hidden, deleted, or drawn at zero size are not candidates.

## Framing the selection

`Alt+F` (**Frame Selected** in the command palette, rebindable as `frame-selected`) brings the
selected boxes, and their open output panels, into view. It puts them in the largest part of the
window that the editor, the docs panel and Field's own panels leave uncovered. If they do not fit
there it zooms out until they do, and if the canvas was zoomed further out than that it zooms back
in, up to 100%. It never zooms in past that.

If none of the uncovered space could show the selection at even half the size the whole window
could, it uses the whole window instead and lets the editor cover part of it. A narrow strip beside
a wide editor is not worth squinting at.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-frame-selected-window" screenshot="assets/moving_around_the_canvas/frame_selected.png" transcript="../../dev_docs/agents/transcripts/docs_capture/bookmarks_panel.yaml" padding="0" wait="0" timeout="240" embed=true hidden=true name="frame-selected" alt="Field after Alt+F. The code editor is docked on the right. The box named render and its open output panel, a 520 by 300 gradient labelled render output, sit together in the uncovered canvas to the left of the editor, centred in it, with nothing of either under the editor."}
// Select `render` and open its editor. The editor docks on the right, which is where `render`
// was put, so before Alt+F most of its output panel is underneath it.
ui.openEditor("bm_render")
ui.pause(900)
ui.eval("if (document.activeElement) document.activeElement.blur(); return 'blurred'")
ui.down("Alt"); ui.press("KeyF"); ui.up("Alt")
// frameSelected animates for 420ms; wait it out so the shot is of where it lands.
ui.pause(1200)
// A zero-ink marker for clip-selector to frame: the union of the editor and the framed box with
// its open panel, from the window's left edge, since what the figure shows is where the selection
// ended up relative to the editor.
val framed = (ui.eval("""
  const ed = document.querySelector(".monaco-editor-container").getBoundingClientRect();
  const box = Array.from(document.getElementsByClassName("box")).find(b => b.uid === "bm_render");
  const rs = [box.getBoundingClientRect()].concat(Array.from(box.querySelectorAll(".outputArea .disclosure")).map(d => d.getBoundingClientRect()).filter(r => r.width > 0));
  const right = Math.max.apply(null, rs.map(r => r.right));
  const all = rs.concat([ed]);
  const y0 = Math.max(0, Math.min.apply(null, all.map(r => r.top)) - 24);
  const y1 = Math.min(innerHeight, Math.max.apply(null, all.map(r => r.bottom)) + 24);
  let frame = document.getElementById("docs-frame-selected-window");
  if (frame === null) { frame = document.createElement("div"); frame.id = "docs-frame-selected-window"; document.body.appendChild(frame); }
  frame.setAttribute("style", "position:fixed;pointer-events:none;background:transparent;border:0;left:0;top:" + y0 + "px;width:" + innerWidth + "px;height:" + (y1 - y0) + "px;");
  return right <= ed.left ? "framed-clear-of-editor panels=" + (rs.length - 1) : "framed-under-editor right=" + right + " editor=" + ed.left;
""") ?: "").toString()
if (!framed.contains("framed-clear-of-editor") || framed.endsWith("panels=0")) error("Frame Selected did not frame render and its panel clear of the editor: " + framed)
```

## Bookmarks

Arrows reach neighbours. For everywhere else there are nine numbered slots.

- Save the current view: `Cmd+Shift+F1` through `Cmd+Shift+F9`
- Restore a saved view: `Shift+F1` through `Shift+F9`

`cmd` is Command on macOS and Control everywhere else. Both sets are rebindable as
`save-bookmark-N` and `restore-bookmark-N`, and both appear in the command palette as
`Save Bookmark N` and `Restore Bookmark N`. See [Keyboard Shortcuts](keybindings.md).

A slot stores a view rather than a box reference. Restoring one reapplies, in this order:

- the canvas pan position and zoom
- the open or closed state of the nested disclosures, before anything is selected
- the size of each output panel that was open, and the open or closed state of every output
  panel. A panel that did not exist when the slot was saved, or no longer exists, is left as it is
- the selected box, when exactly one box was selected at save time
- the property that was open in Monaco
- the editor cursor line and column

### The bookmarks panel

`Alt+B` (**Show Bookmarks** in the command palette, rebindable as `show-bookmarks`) opens a panel
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

- a small map of the document, with the saved view as a dashed rectangle and the selected box
  in the accent colour. The boxes are drawn where they are now, not where they were when the slot
  was saved
- the view position (and the zoom, when it is not 100%), and the box that was selected or being
  edited, with the cursor line
- how many output panels were open and closed, and a pill for each open one with its size

Click the name to rename a slot. **Restore** and **Save** do what `Shift+F`*n* and
`Cmd+Shift+F`*n* do, and **×** empties the slot. The three checkboxes are saved with the slot:

- **restore view**: pan and zoom back to the saved view. Turn it off for a slot that should rearrange
  output panels without moving the canvas.
- **restore selection**: reselect the box and reopen its editor and cursor.
- **close unrecorded panels**: also close every output panel the slot has no record of, instead of
  leaving it as it is. Use it for a slot that should leave the canvas tidy.

Saving over a slot keeps its name and its options.

Bring up the navigation overlay and every box on screen that a slot points at wears an `F` chip
with that slot's number. A box can wear more than one if several slots were saved on it. So the
arrows tell you what is next to you, and the chips tell you which of the nine keys reaches what
is not.

The number on a chip is a slot, so `F3` means the view saved in slot 3, reached with `Shift+F3`.

Bookmarks live on the root box as the `bookmarks` property and are saved with the rest of the
document state. They survive a reload, and they travel with the document rather than with your
browser session.

## Limits

- one target per direction, so the overlay never offers a choice between two boxes on the same side
- a slot stores a single selected box, so a multi-selection is not restored as a set
- slots saved before zoom was recorded restore the pan position at whatever zoom you are at now
- there are nine slots and the numbering is fixed
- restoring a slot changes the view and never the document content
- restoring a slot you have not set yet shows a notification and leaves the view alone

## Related features

- [Keyboard Shortcuts](keybindings.md) covers rebinding, and lists both commands for all nine slots.
- [Snapping](snapping.md) is the other half of arranging a document you intend to navigate this way.
- [Document History](document_history.md) saves the document's content rather than your view of it.
