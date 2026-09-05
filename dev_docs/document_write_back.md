# What opening a document writes

A Field document is two files: `metadata.edn` and `document.md`, plus one sidecar per
sidecar-backed property. Opening one and using it normally produces up to six more, all derived,
all written into the same directory. That is fine in a workspace. It is not fine when the document
is committed to a repository, and this repository has 117 of them — six under
`dev_docs/agents/demos/`, 101 under `public/docs/assets/`.

```
git ls-files '*metadata.edn' | wc -l        # 117 on 2026-09-05
```

## The producers

Seven hang off `SavingContext.save`, in `src/jvmMain/kotlin/afield/saving.kt`. All but the first
two sit inside `if (altered)`, so one changed byte fires the whole set.

| what | written by | file |
|---|---|---|
| sidecars | the transfer loop | `<box>_<property>_.kt` and friends |
| the document | `checkDifference` | `metadata.edn`, `document.md` |
| backups | `safeSave`, `maxBackups = 10` | `backups/*_backup_*.edn` |
| flight recorder | `FlightRecorder.checkpointOnMetadataChange` | `transcript.edn` |
| operation transcript | `OperationTranscript.checkpoint` | `operations.yaml` |
| history metrics | `HistoryMetrics.recordCurrentSave` | `history_metrics.edn` |
| four-window screenshot | `HistoryScreenshotCapture.captureFourWindowsOnSave` | `history_screenshots/four-window/` |

Two more do not go through `save` at all:

- **`ensureAgentGuides`**, reached from `ensureDirectory`, which `load` calls. So it fires on
  *open*, not on save, and seeds `AGENTS.md` and `README.md` into any document directory lacking
  them. 28 of the 117 directories here have no tracked `AGENTS.md`, including the shipped demo at
  `dev_docs/agents/demos/python_cells`.
- **`store-history-screenshot`** (`history.kt`), which writes
  `history_screenshots/electron-window/`. The client sends it from Electron's
  `api.captureHistoryScreenshot()`, so it never passes through `save` and the
  `afield.history.fourScreenshots` flag does not cover it. This is the screenshot that turns up in
  practice; the four-window one needs a live renderer window.

A first save into an empty directory therefore writes **six** files for one box, not three:
`metadata.edn`, `document.md`, the sidecar, `history_metrics.edn`, `AGENTS.md` and `README.md`.
`checkDifference` counts creating a file as an alteration, so a first write is "altered" and the
fan-out runs. `DocumentWriteBackLedgerTest` pins that set, and the three backups a second save adds.

## Monaco view state is not part of the document

Before 2026-09-05 the list above could fire from nothing but *selecting a box*. Monaco's
`monitorSelection` sends a `set-string` for `<property>_editorState` as soon as the editor binds,
`".*_editorState"` was in `Box.ioPersistentProperties`, and so a cursor position went into
`metadata.edn`. Measured against `dev_docs/agents/demos/midi_clip`: open it, click one box, move
nothing, and four paths appear in `git status`, with `code_editorState` the only content
difference in either tracked file.

`Box.ioNeverPersistedProperties` now vetoes it at the three write filters in `saving.kt`. A
deny-list rather than a deletion from `ioPersistentProperties`, because
`SavingContext.mergePersistentProperties` does `ioPersistentProperties.addAll(...)` with the list
stored in every `metadata.edn` it loads — and every document written before that date carries
`".*_editorState"` in that list, so the entry would come straight back on the first load.

The cost is that a cursor position no longer survives closing and reopening a document. Within a
session the client restores it from its own copy of the box. `code_decorationState` was already on
the other side of this line: in `uiResyncProperties`, deliberately absent from
`ioPersistentProperties`.

## Read-only

```bash
./field.sh dev_docs/agents/demos/midi_clip --read-only
AFIELD_READ_ONLY=1 ./field.sh ...
python3 tools/field_backdoor.py --port <p> open <document> --read-only
```

A read-only session loads and runs the document and never writes back to it.
`SessionInformation.readOnly` is the state; `writesToDisk` is the predicate the four write gates
ask (the autosave loop, `set-string`'s sidecar marking, `Box.setPropertyRaw`'s, and the backdoor's).
`ReadOnlyDocuments.blocksWritesTo` guards `SavingContext.save` itself, because that function has
eight call sites and `Ai4AutoBoxNamer` and `FileWatchManager` reach it without passing the loop.

`readOnly` is not `ephemeral`. An ephemeral session is not a real document — no welcome listing, a
synthesised session key, `scratch=1` in the client URL, dropped from `documents` on detach. A
read-only session is a real named document with the write path switched off.

Two limits. `readOnly` is fixed when the session is built, so `open --read-only` against a document
another session already has open writable logs a warning and returns a writable session; the
snapshot reports the real value, so check it rather than assume it. And read-only says nothing
about what a *box* does: a box that writes a file writes it.

## What is left dirty, and what is not

After the 2026-09-05 change, measured in a live Field on port 9532 against
`dev_docs/agents/demos/midi_clip`:

| action | `git status` |
|---|---|
| open, click a box, drag a box | clean — nothing written at all |
| open and rename a box | `M document.md`, `M metadata.edn`; the four side-artefacts exist but are ignored |
| any of the above with `--read-only` | clean |

The two tracked files still change when you change the document, which is correct. `.gitignore`
covers the derived five (`backups/`, `history_metrics.edn`, `transcript.edn`, `operations.yaml`,
`history_screenshots/`) anywhere in the tree rather than only under `metadata_store/`.

Thirteen files of this kind are already committed, all under `public/docs/assets/`, and
`rectsl_gallery/` is a complete instance including a four-window screenshot JPG. `.gitignore` does
not untrack them; they need deleting.
`DocumentWriteBackLedgerTest.noNewDerivedArtefactIsCommitted` pins the set so a fourteenth fails.

## The same disease outside Field: a test that writes into `public/docs/`

`./gradlew jvmTest` rewrites two committed PNGs, every run:

```
 M public/docs/assets/vector_algebra/read_swizzles.png
 M public/docs/assets/vector_algebra/write_swizzle.png
```

`VectorAlgebraDocCaptureTest` regenerates them in place. The bytes differ run to run, so the
working tree goes dirty whether or not anything changed. Measured 2026-09-05 in a clean worktree:
run `jvmTest`, get those two.

There is no guard against this. **`checkTestTreeWrites` does not exist** — the convention that tests
write to temp directories is prose in six files and nothing enforces it
(`grep -rl checkTestTreeWrites . --exclude-dir=.git --exclude-dir=build` returns zero files).
`TestProofDir.proofDir` is the pattern that solves it for visual proof artefacts; the doc-capture
tests do not use it, because their output is meant to be committed. That makes them a different
problem from the ones above and they are left alone here.

## Open: a box move does not trigger a save

`set-rect` records into `OperationTranscript` and nothing else — it does not mark a sidecar
pending, so it does not wake the autosave loop. Before this change, selecting a box happened to
trigger a save, which flushed any accumulated moves along with it. It no longer does, so "drag some
boxes, edit nothing, quit" loses the layout. Editing any sidecar-backed property still saves, and
still flushes the moves: verified on 2026-09-05 by dragging a box twice and then renaming it, after
which `metadata.edn` carried both drags.

Making `set-rect` mark the document dirty would fix that and is probably right. It would also mean
that moving a box in a committed demo dirties the repository again — correctly, because you changed
it, and that is what `--read-only` is for.
