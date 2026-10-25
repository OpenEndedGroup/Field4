# Offline viewer implementation

The offline viewer is a static, read-only presentation of Field metadata. Its
behavior belongs with these files:

- `src/commonMain/resources/offline-viewer.js`: loading, filtering, drawing,
  navigation, read-only Monaco/literate views, and the import button.
- `src/commonMain/resources/edn-parser.js`: the supported EDN subset.
- `src/commonMain/resources/offline.html`: layout and static styles.
- `tools/sync_offline_assets.py`: authoritative distribution contents.
- `src/jvmMain/kotlin/afield/app/server/Server.kt`, route
  `/api/import-offline-doc`: optional transfer into a running Field workspace.

The renderer omits the root, tombstones, boxes without a nonblank name, and boxes
with neither notes nor nonblank `code`. Everything is drawn in world coordinates
under one camera, `screen = world * zoom + translation` (Field's `CanvasCamera`
convention): the root box's `drawing` (Excalidraw scene, JSON) as static SVG,
then parent edges, then box frames. The SVG goes through the same
`invert(93%) hue-rotate(180deg)` filter that Field's dark-theme Excalidraw uses,
so stored colours look as they do in Field (`DarkThemeColour.kt`). Shapes are
smooth, not rough.js hand-drawn; images and embeds are drawn as dashed outlines.

A selected box shows its `notes` as Markdown (a small escaping renderer:
headings, emphasis, code, links/images resolved beside `metadata.edn`, lists,
pipe tables, quotes, fences coloured by Monaco) above read-only tabs. The tabs
are every sidecar-backed property the parser saw (`__sidecars`), after the known
code properties, so new sidecar kinds show up without a list change. `code`
takes its Monaco language from `code_language`.

Sidecars: `#afield/file-inline` gives the inline copy at once; after first
paint the viewer fetches the loose files and swaps in any that differ, because
Field loads the loose file and keeps the inline copy as a fallback.
`#afield/file` references (AI chat histories, anything over 256 KB) have no
inline copy and are fetched before rendering; a missing one is reported in the
banner and in its tab. `resolveSidecars` in `edn-parser.js` does both.

Bookmarks (root `bookmarks`, JSON `BookmarkData`) are buttons and keys 1-9; a
bookmark with a recorded world rect (`viewX..viewH`) is framed in the canvas
area beside the editor panel, an older one restores `x`, `y`, `zoom`. Layers
(`layers`, comma-separated, default `default`) become filter chips when a
document uses more than one. Split panes (`panes`) are not reproduced: the
viewer has one camera.

Monaco failure degrades to the box graph rather than failing the whole page. It
does not reproduce live outputs, graphics, execution, websocket updates, or
editing.

Navigation is implemented directly in `offline-viewer.js`: click selects;
drag on empty canvas, middle-drag or Shift+left-drag pans; the wheel and arrow
keys pan; Ctrl/pinch-wheel zooms about the cursor (0.1x-4x); `+`/`-` zoom, `0`
fits, `d` toggles the drawing; Escape deselects; `?`/`h` toggles help.

URL parameters: `file` (required), `select=<box id or name>`, `reader=1`,
`bookmark=<slot>`, `layer=<name>`, `drawing=0`. For example
`offline.html?file=assets/offline_viewer_demo/metadata.edn&select=sketch`.

`examples-gallery.html` lists `offline-gallery.json`, which
`tools/sync_offline_assets.py` writes into every target from the documents under
its `assets/` (box, notes and drawing counts, and a screenshot from
`assets/agent_transcripts_gallery/` when one is named after the document).

The docs build calls the synchronizer and embeds the generated `offline.html`.
`ednFileCandidates()` handles the two supported mount shapes: a site mounted
under `/field4/`, and the built `public/field4/` directory served as the HTTP
root.

## Import boundary

Viewing does not execute code. Clicking `Open in Field·4` does mutate local
state: the viewer first asks its embedding Field docs frame to import, then falls
back to the HTTPS localhost endpoint. The server sanitizes the suggested name,
chooses a non-colliding workspace child, and writes the submitted EDN verbatim.
Opening or executing imported code is separate, and imported documents must be
treated as untrusted.

## Verification

`uv run --project . python -m unittest tools.test_offline_viewer_js` runs the
Node regressions (`tools/test_offline_edn_parser.js`: Field's EDN dialect, and
every shipped `metadata.edn` parses; `tools/test_offline_viewer_render.js`:
Markdown, Excalidraw, bookmarks, layers, tabs), checks that `edn-parser.js`
handles every tag `saving.kt` registers, and checks the gallery index. It is
part of `tools/run_python_tests.py`.

The demo document `public/docs/assets/offline_viewer_demo/` (three boxes with
notes, a drawing written by its `sketch` box, two bookmarks, two layers) was
made in Field and exercises every feature above.

Run `./tools/build-offline-docs.sh`, serve `dist/offline-viewer/`, and verify the sample
URL documented by the script. Check graph-only fallback by withholding
`min/vs/loader.js`. Import testing must use a disposable workspace and confirm
both name sanitization and collision suffixing; do not aim the test at a user's
active workspace.
