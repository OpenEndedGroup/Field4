# Offline viewer implementation

The offline viewer is a static, read-only presentation of Field metadata. Its
behavior belongs with these files:

- `src/commonMain/resources/offline-viewer.js`: loading, filtering, drawing,
  navigation, read-only Monaco/literate views, and the import button.
- `src/commonMain/resources/edn-parser.js`: the supported EDN subset.
- `src/commonMain/resources/offline.html`: layout and static styles.
- `tools/sync_offline_assets.py`: authoritative distribution contents.
- `src/jvmMain/kotlin/afield/Server.kt`, route
  `/api/import-offline-doc`: optional transfer into a running Field workspace.

The renderer omits the root, tombstones, boxes without a nonblank name, and boxes
whose string `code` property is blank. It draws stored frames and parent edges,
and exposes string properties from its supported property list as read-only
editor tabs. Monaco failure degrades to the box graph rather than failing the
whole page. It does not reproduce live outputs, graphics, execution, websocket
updates, or editing.

Navigation is implemented directly in `offline-viewer.js`: click selects,
middle-drag or Shift+left-drag pans, the wheel and arrow keys pan, Escape
deselects, and `?`/`h` toggles help when the editor is not consuming input.

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

Run `./build-offline-docs.sh`, serve `dist/offline-viewer/`, and verify the sample
URL documented by the script. Check graph-only fallback by withholding
`min/vs/loader.js`. Import testing must use a disposable workspace and confirm
both name sanitization and collision suffixing; do not aim the test at a user's
active workspace.
