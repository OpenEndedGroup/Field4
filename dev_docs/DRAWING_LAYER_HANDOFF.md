# Drawing layer (Excalidraw) — handoff

Status as of 2026-09-28: MVP on branch `excalidraw-layer` (commit `fc1655d96`), plus canvas zoom
(see "Zoom" below), not merged.

## What it is

A modal notation layer. One Excalidraw scene per document, laid over the whole box canvas.
**Alt+D** (`toggle-drawing-layer`, rebindable in `~/.afield/keybindings.yaml`, also in the command
palette) switches mouse and keyboard between Field and the drawing.

- **Off:** the drawing is visible but inert (`pointer-events: none`, Excalidraw in view + zen
  mode, its UI hidden by CSS). Field behaves exactly as before.
- **On:** the layer sits above the boxes (z-index 21) and takes the pointer and keyboard focus.
  Key events Excalidraw handles stop at the layer's host element, so Field's `n`, delete, etc. do
  not fire underneath.

Decisions made with Marc: **one root-level scene** (not per box). Zoom was pinned to 1 in the
MVP and then unlocked by giving Field's canvas a zoom of its own.

## Where things are

| Piece | File |
| --- | --- |
| Field-side logic: toggle, camera sync, persistence, lazy load | `src/jsMain/kotlin/afield/drawingLayer.kt` |
| React/Excalidraw adapter (the only React in Field) | `tools/excalidraw-bundle/entry.jsx` |
| Bundle build (esbuild) | `tools/excalidraw-bundle/build.mjs`, `package.json` |
| Vendored output, served at `/excalidraw/` | `src/jsMain/resources/excalidraw/` (committed, like Monaco) |
| Camera hooks | `Main.kt`: `applyViewportTranslation`, the `onwheel` and middle-drag `onmousemove` pans, `panViewportForDrawingLayer`; `bookmarks.kt`: `restoreScrollPosition` |
| Incoming scene | `Main.kt` `set-string` handler, `"drawing"` branch → `onRemoteDrawingScene` |
| Keybinding declaration | `KeybindingsClient.kt` `virtualCommands` |
| Persistence registration + save trigger | `Boxes.kt`: `uiResyncProperties`, `ioPersistentProperties`, and the `triggersSave` check in the resync `is String` branch |
| CSS | end of `src/commonMain/resources/field.css` |

Rebuild the bundle: `cd tools/excalidraw-bundle && npm ci && npm run build`. Output is ~9 MB
(entry 753 KB; mermaid, katex and locales are lazy chunks). Fonts are local except Xiaolai (CJK,
12 MB), which falls back to Excalidraw's CDN.

## How it works

**Camera.** Excalidraw draws scene point `p` at `(p + scroll) * zoom`; Field draws world point `p`
at `p * mainZoom + mainTranslation`, and the layer's container starts at window (0, 0). So scene
coordinates *are* Field world coordinates, Excalidraw's zoom is `mainZoom`, and its scroll is
`mainTranslation / mainZoom` (the conversion lives in `drawingLayer.kt`; `entry.jsx` only speaks
Excalidraw's terms). Field → drawing: every writer of `mainTranslation` calls
`drawingLayerViewportChanged`. Drawing → Field: Excalidraw's `onScrollChange` →
`panViewportForDrawingLayer` → `applyViewportTranslation`. Both sides no-op when already equal,
which is what stops the echo. Excalidraw's zoom range (0.1–30) is wider than Field's (0.1–4); an
out-of-range zoom is clamped about the window centre so the translation computed for the unclamped
zoom does not make the view jump.

**Keyboard.** `handleKeyboardGlobally={false}`, so Excalidraw only hears keys when its
`.excalidraw-container` (the one element with a tabIndex) has focus; `setActive(true)` focuses it.
The host stops bubbling of keydown/keyup/keypress while active. The Alt+D listener is on `window`
in the **capture** phase, so it works while Excalidraw has focus (same pattern as the bookmarks).
Field's other capture-phase key listeners (bookmark F-keys, keycast, sliders) still run; none of
them conflict.

**Persistence.** The scene is the root box's `drawing` property: a complete `.excalidraw` JSON
string, deleted elements included (they are the tombstones that carry deletions to other clients).
It follows the same generic path as `bookmarks`. Two non-obvious facts:

1. The root box **never writes sidecars** (`SavingContext.toTransfer`: `box.id != "root"`), so the
   scene is inline in `metadata.edn`.
2. The autosave loop in `Server.kt` saves **only when `pendingSidecarSaves` is non-empty**, and only
   `ioSeparateProperties` keys mark it. Without the `triggersSave` special case in `Boxes.kt`, a
   document whose only change was a drawing never saved. **`bookmarks` has this same gap** and was
   left alone here.

**Sync.** Local changes are debounced (250 ms, detected by summing element `version`s) and sent with
the exported `send("set-string", …)`, which stamps clientId/clientSequence so
`acceptSetStringSync` drops stale echoes. Incoming scenes merge with Excalidraw's
`reconcileElements` (per-element version, and it protects the element being edited) and apply with
`CaptureUpdateAction.NEVER`, so remote changes stay out of local undo.

**Lazy loading.** The bundle is dynamically imported on the first toggle, or as soon as a document
arrives with a non-empty `drawing`, so saved notation shows without a toggle.

## Zoom

Field's canvas camera is `screen = world * mainZoom + mainTranslation`: `mainCanvasTransform()` is
`translate(t) scale(mainZoom)` with `transform-origin: 0 0`. Translation stays in screen pixels, so
every existing pan delta, centring calculation and the `set-viewport` payload is unchanged, and at
zoom 1 behaviour is identical to before. Input: ctrl/meta-wheel on the viewport (which is also what
a trackpad pinch sends) calls `setCanvasZoom(z, anchorX, anchorY)`, which keeps the world point
under the cursor fixed. **Reset Canvas Zoom** is a palette command; `window.setCanvasZoom`,
`window.resetCanvasZoom`, `window.canvasZoom()` are exposed for tests.

What had to learn about zoom (divide screen measurements by `mainZoom`): box drag (client delta) and
resize (interact.js `deltaRect`/`rect`) in `Main.kt`, `boxWorldRect`, the connection layout snapshot
in `gestures.kt`, `lastMouse` (new-box placement) in `commands.kt`. Window-sized world-space SVG
layers (connection splines, overlay and front connection layers, actor arcs) use
`worldViewBox(w, h)`. The offscreen hints in `gestures.kt` (the thick edge lines for boxes outside
the window, and the name labels of offscreen parents/children of a selected box) test against the
visible *world* rect, `(-t / zoom, window / zoom)`; their line width and margin are divided by the
zoom and the labels are counter-scaled about their marker, so they stay screen-sized at the window
edge. The background dots are the visual unit for `frame` (25 and 100 world units), so their
spacing scales with the zoom. **The live grid is `src/commonMain/resources/fieldWebglBackground.js`**,
loaded by `index.html`; the inline copy in `Main.kt` (`installWebGLBackground`) returns early when
that script has already installed, so it is only a fallback — edit both. Both take a `u_zoom`
uniform, keep dot radii in screen pixels, and fade the small dots out below ~0.5× and the large
ones below ~0.15×. The CSS-gradient fallback in `updateBackgroundGrid` scales its tile size too.

Verified at zoom ≠ 1: box screen rect is exactly half its frame at 0.5; ctrl-wheel about (700, 400)
keeps world (1400, 800) under the cursor; a 60 px drag at 0.6 moves a box 100 world units; a
zoom-0.6 render of boxes + splines + drawing is identical to a zoom-1 render scaled by 0.6; zooming
inside Excalidraw (ctrl-wheel, and 40 clicks on its zoom-in button) moves Field's canvas so a box's
screen rect equals Excalidraw's own projection of it, including after clamping at 4×.

Not zoom-aware yet: slideshow annotation geometry (`slideshowSupport.kt` still builds a translation-only
viewBox and offsets), bookmarks (they
save and restore translation only), the server's per-box `viewport` rect, and marquee/snapping were
not exercised at zoom. The Monaco editor and docs/history iframes are outside the scaled canvas, so
they do not scale, which is intended.

## Verified (backdoor + server-owned Playwright, 2026-09-28)

- Toggle on → Excalidraw loads, `r` selects rectangle, a synthetic pointer drag draws one element
  at the expected world position; toggle off → `pointer-events: none`, drawing still rendered.
- Key isolation with a negative control: `n` creates a box with the layer off (+1), none with it on (+0).
- Wheel pan on Field's viewport moves the drawing (scroll 120, 40); wheel pan inside Excalidraw
  moves Field (`translate(60px, 10px)`); a ctrl-wheel leaves zoom at 1.
- Save: `drawing` appears in `metadata.edn`; a fresh Field process on the same document auto-loads
  it (1 element) and renders it without a toggle.
- Remote merge: a box calling `thisBox.inside.root.setPropertyRaw("drawing", …)` with a 2-element
  scene updated the live client from 1 to 2 elements.
- `DocumentWriteBackLedgerTest` (5) and `BoxPropertyBagConcurrencyTest` (9) pass;
  `check_offline_assets.py` passes.

Probe from a page: `window.drawingLayerState()` → `{ready, active, scrollX, scrollY, zoom, tool,
elementCount}`; `window.toggleDrawingLayer()`. The backdoor's `set` cannot address `root` ("No box
'root'"), which is why the remote test goes through box code.

## Not verified / known gaps

- **Not driven by a human yet.** All input was synthetic pointer/keyboard events. Real
  trackpad pinch, text editing, copy/paste and the eraser are untested.
- **Two real browser clients at once** were not tested; only server → client merge was.
- **Paste:** Excalidraw listens for `paste` on `document`. If Field also has a document-level
  paste handler, both may fire while drawing. Not checked.
- **No UI regression case** in `tools/run_ui_regressions.py` yet. The probes above are the obvious
  starting point (the scripts were scratch files and were not kept).
- **Layers are ignored:** the drawing shows on every Field layer.
- **Z-order:** active, the layer covers boxes, including their output areas. The Monaco editor
  container and the docs iframe were not checked for overlap.
- Excalidraw's hamburger menu sits top-left in active mode and overlaps any box there.
- Images are disabled (`tools: { image: false }`): `BinaryFiles` are not persisted.
- Scene size is unbounded, and freedraw points plus tombstones grow `metadata.edn`.

## Next steps, roughly in order

1. A `run_ui_regressions.py` case: toggle, draw, key isolation, pan sync, reload.
2. Tag elements with `customData.fieldLayer` at creation and filter on `setVisibleLayers`.
3. Box anchoring: per-box proxy rectangles so arrows bind to boxes, or elements anchored by
   `customData.boxUid` that move with their box.
4. A Kotlin `drawing()` stdlib accessor that returns elements, with freedraw/line/arrow → `FLine`.
5. Code → drawing: boxes emitting annotations into the layer.
6. Finish zoom: make slideshow annotations, bookmarks (save zoom too) and the server `viewport`
   rect zoom-aware, and exercise marquee selection, snapping and output areas at zoom ≠ 1.
