# Drawing layer (Excalidraw) — handoff

Status as of 2026-09-28: MVP on branch `excalidraw-layer` (commit `fc1655d96`), not merged.

## What it is

A modal notation layer. One Excalidraw scene per document, laid over the whole box canvas.
**Alt+D** (`toggle-drawing-layer`, rebindable in `~/.afield/keybindings.yaml`, also in the command
palette) switches mouse and keyboard between Field and the drawing.

- **Off:** the drawing is visible but inert (`pointer-events: none`, Excalidraw in view + zen
  mode, its UI hidden by CSS). Field behaves exactly as before.
- **On:** the layer sits above the boxes (z-index 21) and takes the pointer and keyboard focus.
  Key events Excalidraw handles stop at the layer's host element, so Field's `n`, delete, etc. do
  not fire underneath.

Decisions made with Marc: **zoom pinned to 1**, **one root-level scene** (not per box).

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
at `p + mainTranslation`, and the layer's container starts at window (0, 0). With zoom = 1, scene
coordinates *are* Field world coordinates and `scroll` *is* `mainTranslation`. Field → drawing:
every writer of `mainTranslation` calls `drawingLayerViewportChanged`. Drawing → Field:
Excalidraw's `onScrollChange` → `panViewportForDrawingLayer` → `applyViewportTranslation`. Both
sides no-op when already equal, which is what stops the echo.

**Zoom pinning.** Ctrl/Meta-wheel and Safari gesture events are swallowed at the host in the capture
phase; `pinZoom` in `onScrollChange` resets anything that slips through (keyboard zoom,
zoom-to-fit). The zoom buttons are hidden by CSS.

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
6. Field zoom, if it is ever wanted. That is its own project: boxes are DOM, and scaling
   `mainCanvas` breaks Monaco and output-area hit testing.
