# Spatial Navigation

Field can move the selection from box to box without the mouse. There are two ways to do it: hop to a
neighbouring box with the arrow keys, or jump to one of nine saved views with a function key. Both
end with a box selected and its code in the editor.

## Cmd Cmd and hold

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

## What the overlay draws

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
// (it only scans `events`). So a give-up here does not fail the build. What it does do is leave the
// crop marker absent, and shotClip with a missing selector falls back to a full-page shot: the
// symptom is a 4400x5600 PNG instead of this figure's ~1300x1100, which is hard to miss and hard to
// mistake for a good capture.
if (!report.contains("overlay-settled")) {
    error("spatial navigation overlay never settled after " + tries + " tries: " + report)
}
```

## How the four are chosen

Field sorts the visible boxes by the dominant axis of their offset from the current one. A box
further to the left than it is above counts as west, and so on. Within each direction it takes the
nearest, counting sideways distance as a fifth of distance along the direction of travel, so a box
that is slightly off-axis does not beat one that is directly ahead.

Only one box per direction is offered, so a dense column collapses to its nearest member. Boxes that
are hidden, deleted, or drawn at zero size are not candidates.

## Bookmarks

Arrows reach neighbours. For everywhere else there are nine numbered slots.

- Save the current view: `Cmd+Shift+F1` through `Cmd+Shift+F9`
- Restore a saved view: `Shift+F1` through `Shift+F9`

`cmd` is Command on macOS and Control everywhere else. Both sets are rebindable as
`save-bookmark-N` and `restore-bookmark-N`, and both appear in the command palette as
`Save Bookmark N` and `Restore Bookmark N`. See [Keyboard Shortcuts](keybindings.md).

A slot stores a view rather than a box reference. Restoring one reapplies, in this order:

- the canvas pan position
- the open or closed state of the nested disclosures, before anything is selected
- the selected box, when exactly one box was selected at save time
- the property that was open in Monaco
- the editor cursor line and column

## Slots inside the overlay

Bring up the navigation overlay and every box on screen that a slot points at wears an `F` chip
with that slot's number. A box can wear more than one if several slots were saved on it. This is
where the two halves of the feature meet: the arrows tell you what is next to you, and the chips
tell you which of the nine keys reaches somewhere that is not.

The number on a chip is a slot, so `F3` means the view saved in slot 3, reached with `Shift+F3`.

## Persistence

Bookmarks live on the root box as the `bookmarks` property and are saved with the rest of the
document state. They survive a reload, and they travel with the document rather than with your
browser session.

## Limits

- one target per direction, so the overlay never offers a choice between two boxes on the same side
- a slot stores a single selected box, so a multi-selection is not restored as a set
- there are nine slots and the numbering is fixed
- restoring a slot changes the view and never the document content
- restoring a slot you have not set yet shows a notification and leaves the view alone

## Related features

- [Keyboard Shortcuts](keybindings.md) covers rebinding, and lists both commands for all nine slots.
- [Snapping](snapping.md) is the other half of arranging a document you intend to navigate this way.
- [Document History](document_history.md) saves the document's content rather than your view of it.
