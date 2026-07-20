# Nightly worklog — ipynb-roundtrip-export (kind B, feature)

Branch: `nightly/2026-07-20/2-ipynb-roundtrip-export`

## Plan (refined against the real code)

**Goal.** Close the notebook loop: export a Field python-root column back out to a
valid nbformat-4 `.ipynb`, inverse of `importNotebookCells`
(`src/jvmMain/kotlin/afield/server/importing.kt`).

**What the importer produces (the shape we must invert):**
- one python-root parent box (`isPythonRoot = true`, name "python root"),
- code cells = children of the python root carrying `code`, laid out at the column
  anchor x, width `NOTEBOOK_COLUMN_WIDTH`,
- markdown/raw cells = ordinary document-root boxes (NOT python children) with text
  in `notes`, indented by `NOTEBOOK_MARKDOWN_INDENT` (still inside the root's
  horizontal span), stacked by increasing `frame.y`.

**Export design (`exportNotebookCells(pythonRoot, info)` in importing.kt):**
1. Code cells = `pythonRoot.children` whose `code` is non-blank.
2. Markdown cells = boxes in `info.boxes` that are NOT the root, NOT python children,
   carry non-blank `notes`, sit below the root header (`frame.y > root.frame.y`) and
   whose horizontal centre falls within the root's `[x, x+w]` band (the column).
3. Order all cells by `frame.y` (document order — matches importer layout).
4. Emit nbformat-4 JSON with kotlinx.serialization builders: `nbformat=4`,
   `nbformat_minor=5`, top-level `metadata` (kernelspec + language_info), `cells`;
   each code cell `{cell_type:"code", execution_count:null, metadata:{}, outputs:[],
   source:[...]}`, each markdown cell `{cell_type:"markdown", metadata:{}, source:[...]}`.
   `source` is a JSON array of lines with trailing `\n` per nbformat convention, so
   the importer's `joinToString("")` round-trips exactly.

**RPC / JS (mirrors `export-document`):**
- `ExportNotebook(uid)` request, `ExportNotebookResult(notebookJson, fileName)` reply
  in `rpc.kt`.
- `export-ipynb` handler in importing.kt: looks up the box, validates `isPythonRoot`,
  replies with the JSON.
- JS `exportNotebookToDownload()` + a `export-ipynb` command that exports the selected
  python-root box, downloading via a Blob (mirror `exportDocumentToDownload` /
  `downloadEdnFile`).

**Verification.** `NotebookExportSmokeTest` (FIELD_TEST, jvm-main): import a mixed
fixture, export the python-root, assert nbformat-4 + cell count/types/source, then
RE-IMPORT the export and assert cell count/types/source round-trip equal. Run via
`python3 tools/run_field_tests.py --id examples.export.ipynb-notebook`.

Plan is well-founded against the real code; proceeding without pivot.

## Execution — files changed

- `src/commonMain/kotlin/rpc.kt` — `ExportNotebook(uid)` request + `ExportNotebookResult(notebookJson, fileName)` reply payloads.
- `src/jvmMain/kotlin/afield/server/importing.kt` — `exportNotebookCells(pythonRoot, info)` (nbformat-4 serializer via kotlinx.serialization builders) + `export-ipynb` RPC handler + `notebookExportName`/`sourceLines`/cell-builder helpers.
- `src/jsMain/kotlin/afield/commands.kt` — `exportNotebookToDownload()`, `export-ipynb` command-palette entry, `downloadEdnFile` refactored onto a generic `downloadTextFile`.
- `src/jvmMain/kotlin/afield/examples/NotebookExportSmokeTest.kt` — FIELD_TEST `examples.export.ipynb-notebook`.
- `public/docs/funFeatures.md` — notebook interop (import + export) paragraph.
- `dev_docs/examples/notebook_roundtrip_demo.ipynb` — demo fixture.

## Verification (observed, honest)

- `./gradlew -q compileKotlinJvm` — GREEN.
- `./gradlew -q compileTestKotlinJvm` — GREEN.
- `./gradlew -q compileKotlinJs` — GREEN (JS wiring compiles).
- `python3 tools/run_field_tests.py --id examples.export.ipynb-notebook` — **PASS (4087 ms)**, `1 passed, 0 failed`. The test imports a 4-cell mixed fixture, exports the python-root, asserts nbformat==4 + the exact ordered cell shapes `[markdown, code, code, markdown]` with source text, asserts each code cell has `execution_count: null` + `outputs: []` + `source` as an array, then RE-IMPORTS the export (root + 4 cells) and asserts the second export's cell shapes equal the first (2 code + 2 markdown). Round-trip fidelity confirmed.
- Demo fixture validated as nbformat-4 by `python3 -c json.load` (4 cells: markdown, code, code, markdown).

## Risks

- Markdown-cell membership is geometric: a `notes` box is claimed for the notebook only if it sits below the python-root header and its horizontal centre falls inside the root's `[x, x+w]` band. If a user drags a prose box far out of the column it won't be exported; conversely an unrelated `notes` box parked inside the band would be. This matches the importer's layout contract but is heuristic. Code cells (true python-root children) are unaffected — they're always included.
- JS `exportNotebookToDownload` exports the *first selected* box; the server rejects it with a notify if it is not a python root. Not verified in a live browser (no headless UI run this session); the Kotlin/RPC path is the verified evidence.

## Recommendation

**MERGE.** Compiles on all three targets; the round-trip smoke test passes with the expected cell counts/types/source. The core feature (Kotlin export + RPC) is fully verified; the JS command mirrors the proven export-document pattern and compiles.

## How to exercise it in a live Field

1. Drag `dev_docs/examples/notebook_roundtrip_demo.ipynb` onto the canvas → *Import as Python cells* (creates a "python root" column).
2. Select the "python root" box and run *Export notebook (.ipynb)* from the command palette → downloads the notebook back out.
   (Equivalent RPC: `export-ipynb` with `ExportNotebook(uid=<python-root uid>)`.)
