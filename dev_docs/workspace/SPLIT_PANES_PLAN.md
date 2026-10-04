# Split panes for the box canvas — scouting and plan

Status: P0 (the camera seam) built on `canvas/split-panes-p0`; nothing user-visible yet.
Scouted 2026-10-04.

Decided (Marc, 2026-10-04): **quadrant membership by position** (Option Q below), **shared zoom**,
**split, not freeze** (every pane scrolls along its linked axes).

The goal is spreadsheet-style split panes on the box canvas. A layout is 1×2, 2×1 or 2×2:

```
A | B
--+--
C | D
```

Panes in the same row share vertical scroll (A↔B, C↔D). Panes in the same column share
horizontal scroll (A↔C, B↔D). You create the split by dragging out from the top-left corner,
and you can drag the dividers afterwards.

## 1. What the scouting found

There is **one camera**: `mainTranslation` (screen px) plus `mainZoom`, with
`screen = world·zoom + t`. It is applied as one CSS transform on `mainCanvas`, the only
transformed node. Boxes are one absolutely positioned DOM node each inside `mainCanvas`, and
output panels and nested boxes travel inside their box. There is no per-frame render loop; the
browser composites the transform.

All the other layers are window-sized siblings that copy the camera:

| Layer | Where | How it follows the camera |
| --- | --- | --- |
| `canvas.field-webgl-background` (grid, axes, **vignette from canvas edges**) | inside `mainViewport` | uniforms `u_translation`, `u_zoom`, `u_resolution` |
| `svg#connectionsplinelayer` (splines; boxes blur it with `backdrop-filter`) | inside `mainViewport`, behind boxes | `worldViewBox(innerWidth, innerHeight)` |
| `#overlaysvglayer`, `.front-connection-layer` (hints, snapping guides, markers) | body | `worldViewBox` |
| `#overlaycanvas` (marquee, execution/connection line) | body | raw screen px, no camera |
| `.actor-arc-layer` | body | `worldViewBox` |
| `#field-drawing-layer` (one Excalidraw mount, `position:fixed; inset:0`) | body | `handle.setCamera`, two-way via `panViewportForDrawingLayer` |
| `.box-placeholder-layer` | body | translate only (**ignored zoom**; fixed in P0) |
| slideshow annotation SVG | body | viewBox from translation; points are screen rects + translation, so it is screen-anchored and zoom does not matter |

Who writes the camera:

- `applyViewportTranslation` in `Main.kt` is the canonical writer. It sets the transform and
  `data_x/y`, updates the grid, sends `set-viewport`, syncs the drawing layer, rebuilds
  connections, moves the slide note and refreshes spatial nav.
- Three places write it again by hand: wheel pan, middle-drag pan, and
  `restoreScrollPosition` in `bookmarks.kt`. The bookmarks copy does not update `data_x/y`.
- About 100 places read it: Main.kt ~65, gestures.kt ~13, bookmarks.kt ~12, drawingLayer.kt 6,
  slideshowSupport.kt 3, commands.kt 2. Some read `mainCanvas.data_x` instead of
  `mainTranslation`.

Code that treats the window as the viewport:

- `frameFreeSpaces`, `navigationSafeViewportRect`, `centerBoxForMarkdownNavigation`,
  `resetCanvasZoom`, `currentViewportRect` and the screenshot clip helpers
- `mainViewportRect` (offscreen hints) and `hasValidCachedLayoutForTranslation`
- the bookmark minimap
- `set-viewport`, which sends `Rect(tx, ty, innerWidth, innerHeight)` with no zoom and sets
  `viewport` on *every* box. No internal code consumes it; it exists only for user
  `onViewportChange` callbacks.

How input finds boxes:

- Hit testing goes through the DOM: `interact(".box")` takes the event target. Empty-canvas
  gestures start only when `event.target == mainViewport`.
- Marquee and the execution line snapshot every box with `getBoundingClientRect` and test in
  **screen space**.
- Drag and resize divide client deltas by `mainZoom`. `lastMouse` for new boxes is
  `(pageX - data_x)/mainZoom`.

Latent bugs found along the way, all fixed in P0:

- `showDropMenu` built world x/y from `pageX - data_x` without dividing by `mainZoom`. The
  file-proxy placement at the window centre had the same problem.
- The placeholder layer ignored zoom.
- `restoreScrollPosition` left the `data_x/y` mirror stale and never sent `set-viewport`. The
  mirror is gone now.

## 2. The model decision: what a pane shows

This choice decides everything after it, so it comes first.

**Option Q (recommended): quadrant membership derived from position.** A split is two
world-space lines, `wx` and `wy`, plus two screen-space divider positions.

- Pane A shows the world region `x < wx, y < wy`. B shows `x ≥ wx, y < wy`, C shows
  `x < wx, y ≥ wy`, and D shows `x ≥ wx, y ≥ wy`.
- A box belongs to the pane whose region contains its frame origin. There is no new per-box
  property.
- Each pane column has an X translation and each pane row a Y translation, so linking is
  structural: `pane(c, r)` uses `X[c]` and `Y[r]`. Zoom is shared.
- Each pane's translation is clamped so it never shows across its own `wx`/`wy` boundary. One
  consequence is that a screen point always maps to the world point that pane owns, so
  dropping a box into a pane puts it in the right quadrant.
- Creating a split at a screen point sets `wx`/`wy` to the world point under the cursor and
  starts every pane at the current camera, so **nothing moves when you split**. This is
  Excel's "Split".
- Removing the split loses nothing. Every box is still at its true world position, so the
  single pane shows the strips where they always were. Nothing overlaps.

**Option M: explicit membership** (a `pane` box property, like `layers`). Every pane shows the
whole world. This is more flexible, since any box can go anywhere. But collapsing the split
piles boxes on top of each other, a box cannot be in two panes anyway (live outputs, iframes,
Monaco), and it adds a persisted per-box property. I don't recommend it.

The rest of this plan assumes **Q**.

## 3. Rendering strategy

- **One canvas div per pane.** `mainViewport` holds 1, 2 or 4 `.field-pane` elements, each
  `position:absolute; overflow:hidden` at its screen rect and each holding its own
  `.field-main-canvas` with its own transform. The box DOM stays one node per box, so DOM hit
  testing, interact.js and output areas keep working.
- **Changing pane moves the DOM node.** A box that crosses a boundary has its node moved into
  the other pane's canvas, at drag end only. Plain `appendChild` reloads iframes (logs, tree,
  browse, HTML outputs). Electron 44 is Chromium 13x+, so use **`Element.moveBefore()`**, which
  keeps iframe and focus state, and fall back to `appendChild` where it is missing. Prove this
  in a spike first (§6 P1a).
- While a drag is in progress, the box stays in its origin pane. That pane's clip is lifted
  (`overflow: visible` on the dragging pane, or a temporary top-level drag host) so the box
  can visibly cross the divider.
- **WebGL background: one canvas, scissored per pane.** Loop over the panes with
  `gl.viewport`/`gl.scissor`, and give each pane its own `u_translation` and a new
  `u_paneRect`. Compute the vignette from the pane rect, so each pane reads as its own sheet.
  The dividers are CSS. This avoids 4 GL contexts. Both `fieldWebglBackground.js` and the
  inline fallback in `Main.kt` change.
- **Connections** come in two kinds:
  - Within one pane: a per-pane spline SVG inside the pane, behind its canvas. This keeps the
    `backdrop-filter` blur, and its viewBox comes from that pane's camera.
  - Between panes: one screen-space SVG over the panes, built from both endpoints' screen
    rects. `captureLayoutSnapshot` already measures those.
  - Offscreen hints are computed per pane rect.
- **`#overlaycanvas`** (marquee) is already in screen space. Keep it as it is and clip hits to
  the visible box ∩ pane rect.

## 4. Input

- Add `paneAt(clientX, clientY)` and `Pane.toWorld` / `Pane.toScreen`. Every screen→world
  conversion in the reader list goes through the pane under the event, or the box's pane.
- **Wheel** in `pane(c, r)`: `deltaX` changes `X[c]` and `deltaY` changes `Y[r]`. Linking then
  follows automatically. Middle-drag works the same way.
- **Zoom** stays shared and is anchored at the cursor in the active pane. The other column
  keeps the world point at its own left edge fixed, and the other row keeps its top edge.
- **Empty-canvas gestures**: the condition `target == mainViewport` becomes
  `target is a .field-pane`.
- Marquee is allowed across panes. Hits are already in screen space, so this needs only clip
  intersection.
- **New box at cursor**: `lastMouse` uses `paneAt`.
- **frameSelected / ensureBoxVisibleForNavigation** act within the box's pane: the free space
  is the pane rect minus `FRAME_OCCLUDERS`, and they move only that pane's `X[c]`/`Y[r]`. The
  linked panes follow, which is correct.
- **Splitter UI**:
  - a small grip in the top-left corner of the viewport; dragging it out creates the split
  - draggable divider bars; dragging one to an edge removes that split
  - palette commands **Split Canvas Here** (at the cursor) and **Remove Canvas Split**

## 5. Overlays and peripheral systems

| System | v1 | Later |
| --- | --- | --- |
| Excalidraw | One mount, clipped to pane D (the body), with the camera following D. Strokes in other quadrants are hidden while split. | One mount per pane, each showing its own quadrant's elements (membership by position again) with its own camera. `entry.jsx` already keeps its state per mount; the Kotlin side holds a single `handle`, and the merge goes through `NotationScene.merge` on the server. |
| `set-viewport` | Send pane D's world rect, and fix the missing zoom while there. | An optional `viewports` list. |
| Bookmarks | Store the full pane state. Old `{x, y, zoom}` bookmarks restore into D. | Minimap draws the dividers. |
| Slideshow annotations, actor arcs, spatial nav, slide note | Already measured from screen rects. Fix their zoom handling and clip to the target's pane. | |
| Layers panel | Unchanged (global). | Per-pane layer visibility. |
| Docs/screenshot clip helpers | Clip to the box's pane rect. | |

## 6. Phases

**P0: camera seam, no visible change.** This is the real intervention, and it is worth doing
even if split panes are never built.

1. Introduce `class Pane(rect, col, row, canvasEl)` and `object Canvas { panes, X[], Y[], zoom,
   wx, wy }`, with a single `Canvas.apply()` replacing `applyViewportTranslation`.
   `mainTranslation`/`mainZoom` become getters onto the single pane for now.
2. Route the three hand-written camera writers through `apply()`. Delete the reads of the
   `data_x/y` mirror, or make the mirror derived.
3. Replace every "window is the viewport" read (`innerWidth/innerHeight` in the list in §1)
   with `pane.rect`.
4. Fix the four latent zoom/staleness bugs listed in §1.
5. Gate: the existing `run_ui_regressions.py` suite, plus a new regression that pans and zooms
   and checks that the box screen rect, the connection endpoints, the grid uniforms and the
   Excalidraw camera agree.

What P0 built:

- `src/jsMain/kotlin/afield/canvasCamera.kt` holds `CanvasCamera`, which has per-column `x[]`,
  per-row `y[]`, a shared `zoom`, the `panes` list, `main`, `paneAt` and `paneOf`. It also holds
  `CanvasPane`, which has `rect`, `tx`/`ty`, the `worldX/worldY/worldRect` conversions,
  `visibleWorldRect`, `worldViewBox` and `canvasTransform`.
- A pane's translation is in **window** pixels, not pixels from the pane's corner. So
  `getBoundingClientRect` arithmetic is the same in every pane, and `canvasTransform()`
  subtracts the pane origin.
- `mainTranslation` and `mainZoom` are now read-only getters, which makes the compiler enforce
  a single writer. The wheel, middle-drag and bookmark copies are gone: they go through
  `panCanvasBy` and `moveCanvasCamera`. `mainCanvas.data_x/y` no longer exists.
- Framing, navigation, centring, zoom reset and the drawing-layer clamp use `pane.rect` instead
  of the window. The unused `centerViewportOn` was deleted.
- Still on the window, on purpose: the screenshot clip helpers (they really do mean the
  window), the overlay-panel placement (Monaco, docs), and the gestures layout cache, which
  measures the overlay SVG itself. The cache becomes per pane in P3.
- Regression `canvas-camera` (`tools/ui_regressions/canvas-camera.js`) covers wheel pan,
  middle-drag pan, the pinch-zoom anchor, the overlay viewBox, the Excalidraw camera and zoom
  reset. It does not read the grid shader uniforms. Negative control: removing the drawing-layer sync from `applyViewportTranslation`
  fails it with `excalidraw zoom: 1 vs 1.49`.

**P1: static split.**

- (a) Spike `moveBefore` on a box with an iframe output in Electron: is the state kept?
- (b) Pane DOM, per-pane transforms, quadrant membership on load and after `frame` changes,
  wheel routing, the WebGL scissor loop, and a root `panes` property.
- (c) The palette commands, with no splitter UI yet.
- Gate: a UI regression that splits 2×2, wheels in D, and asserts that B's X and C's Y moved
  while A did not.

**P2: interaction.**

- crossing a divider during a drag and reparenting on drop
- resize, marquee, new box at cursor
- frameSelected and navigation per pane
- splitter grip and divider dragging
- clamping each pane at its `wx`/`wy`

**P3: overlays.**

- per-pane and cross-pane connections, and offscreen hints
- bookmarks
- `set-viewport`
- slideshow, actor arcs, spatial nav

**P4: Excalidraw per pane.**

## 7. Persistence

Add a root property `panes` holding a JSON string:

```json
{"wx": 812.0, "wy": 140.0, "sx": 0.28, "sy": 0.18, "X": [0, -400], "Y": [0, -1200], "zoom": 1.0}
```

- `sx`/`sy` are screen fractions; absent means not split.
- Add `panes` to `Box.uiResyncProperties` and `ioPersistentProperties`. It is inline in
  `metadata.edn`, because root never writes sidecars.
- Root-only changes do not trigger a save unless named in `triggersSave` (`Boxes.kt`). Add
  `panes` there only if a split change alone should save; otherwise it rides along with the
  next save, as bookmarks do.
- The change is additive, so older documents have no `panes` and load unsplit.

## 8. Risks

- **Iframe reloads on reparent** if `moveBefore` doesn't keep state in our Electron, or fails
  in plain-browser use. The fallback is "outputs reload when a box changes pane".
- **A box straddling `wy`** is owned by its origin and clipped by its pane. Wide boxes near a
  boundary will look cut off; that is the spreadsheet behaviour too.
- **~100 camera reads** is a wide P0 diff. Keep it mechanical, and land it on its own before
  any pane exists.
- **Cross-pane connection lines** are screen-space and so sit above the box blur. They will
  look different from in-pane splines.
