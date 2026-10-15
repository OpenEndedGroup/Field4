# What a document is on disk

A Field document is a directory. Look inside one and you will find six or more files that the last
save wrote, in a fixed order, on a background thread; opening the document reads a single one of
them. This page covers what is in that directory, **which artifact wins when Field loads**, and when
each of them is written. Edit one of those files by hand and those two answers decide whether your
edit survives.

[File management](files_and_paths.md) covers the `files/` subdirectory — the resolver a
box uses to find `data.csv`. This page is about everything beside it.

## What one save writes

`SavingContext.save` runs in two phases. The main thread snapshots each box's persistent properties
into memory; a coroutine on `Runloop.miscPool` then does all the I/O, holding a per-document lock
keyed on the canonical path, so two saves of the same document serialize and a save of a different
one does not wait. Within the lock the order is fixed: sidecars first, then `metadata.edn`, then
`document.md`.

| File | Holds | Backups kept | Read by the loader |
| --- | --- | --- | --- |
| `metadata.edn` | every persistent property of every box, plus an inline copy of each sidecar | unlimited | yes — this is the file that is parsed |
| `<box>_<property>_.<ext>` | one sidecar-backed property, verbatim | 10 | yes, and it beats the inline copy |
| `document.md` | a Markdown rendering of the same boxes | none | no |
| `transcript.edn` | the flight recorder: what ran, and when | 10 | no |
| `history_metrics.edn` | per-version line counts for the history panel | 10 | no |
| `operations.yaml` | the operation transcript: moves, edits, executes | 10 | no |
| `versions.edn` | version names you typed, and full-restore transitions | none | no |

Only `metadata.edn` is always there. A document saved by an older Field has no `document.md`; one
never opened interactively has no `operations.yaml`; and `versions.edn` appears only once you name
a version or restore one.

The three backup policies are three calls to one function in the save body of `saving.kt`, with
different arguments: sidecars go through
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:462#val cd = checkDifference(target, value.inline, maxBackups = 10)`,
`metadata.edn` through
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:488#metadataChanged = checkDifference(metadataPath, ednText)`
with no `maxBackups` at all, and `document.md` through
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:499#markdownChanged = checkDifference(markdownPath, markdownText, createBackup = false)`.
`maxBackups = null` means unlimited — `src/jvmMain/kotlin/afield/document/persistence/saving.kt:1821#if (backupCreated && maxBackups != null)`
only prunes when a bound was given — so `backups/` in a document you have used for a while is mostly
one filename. Nothing anywhere prunes `metadata_backup_*.edn`.

A save that changes nothing writes nothing. `File.safeSave` reads the current contents first and
returns `false` on a byte-identical write
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1829#// If the content is the same, do nothing.`), and the
save path treats that `false` as "not altered" and skips history, metrics and the transcript
checkpoint entirely.

### An example

Thirteen saves of a three-box document, into a temp directory, driven by the real `save`:

```diffstatic {data-lang=text}
after three saves of a three-box document:
  AGENTS.md
  alpha_code_.kt
  beta_code_.kt
  beta_fragment_.glsl
  document.md
  history_metrics.edn
  metadata.edn
  backups/
    alpha_code__backup_00000.kt
    alpha_code__backup_00001.kt
    history_metrics_backup_00000.edn
    history_metrics_backup_00001.edn
    metadata_backup_00000.edn
    metadata_backup_00001.edn

resolved code for box alpha, loose file and inline copy diverged:
  live load      -> println("edited on disk")
  history load   -> println("three")

after 13 saves, backups/ by stem:
  alpha_code_        10
  history_metrics    10
  metadata           12
  document            0
```

Regenerate it with

```diffstatic {data-lang=text}
uv run --project . --group backdoor python tools/run_field_tests.py --id examples.document-on-disk.smoke
```

which runs
`src/jvmMain/kotlin/afield/examples/DocumentOnDiskSmokeTest.kt:35#object DocumentOnDiskSmokeTest`.

Two things in that listing are not about backups. `AGENTS.md` is seeded into any
new document directory from `templates/` by
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1682#private fun ensureAgentGuides(workspaceDir: String)`, once,
without overwriting a file you have edited. And there is no `operations.yaml` or `transcript.edn`
here at all: both are written only from state accumulated in memory by an interactive session, and a
document saved by a program that never moved a box has nothing to checkpoint.

## Which artifact wins on load

Opening a directory parses `metadata.edn`, and through it the sidecars it names
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1014#throw FileNotFoundException("missing $METADATA_EDN or $METADATA_JSON in $root")`
is the failure when it is absent). The loader never reads `document.md`, which says so in its own
first paragraph
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:673#This is a machine-readable narrative mirror of`), so
editing it cannot change what a document opens as. It is written for code and AI tools: a box graph
you can read without a Field. Field's own completion harness reads it that way, rebuilding each
document's box graph from the fences
(`src/jvmMain/kotlin/afield/runtime/completion/CompletionCorpus.kt:105#.mapNotNull { parseDocument(File(it, "document.md")) }`).

A `metadata.json` from a much older Field is still loadable, and is rewritten as EDN immediately —
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1020#save(root, boxes, "upgrade to edn")` runs as part of the
open.

Within `metadata.edn`, a sidecar-backed property is stored twice: as a loose file, and as an inline
string under an `#afield/file-inline` tag carrying both `path` and `inline`
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1630#registerEdn(FileInline::class, "afield/file-inline"`). One
line decides which copy a load uses:

```diffstatic {data-lang=kotlin}
is FileInline -> {
    val file = File(directory, value.path)
    if (!preferInlineFiles && file.exists()) file.readText() else value.inline
}
```

`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1357#if (!preferInlineFiles && file.exists()) file.readText() else value.inline`.
So **if the loose sidecar file exists, its contents win, and the inline copy in `metadata.edn` is not
read.** Regenerate a `metadata.edn` by hand with new code in the `inline` field, leave
`<box>_code_.kt` in place, and the document opens with the old code: that one property came from the
file. Frames, names, topology and everything else on the same box are still read from `metadata.edn`
exactly as written. The choice is per property, and only for the property names in
`src/jvmMain/kotlin/afield/document/graph/Boxes.kt:498#var ioSeparateProperties = mutableMapOf(`.

`preferInlineFiles` inverts it, and there is one caller who wants that: the history machinery, which
resolves an old version's properties with
`src/jvmMain/kotlin/afield/document/history/history.kt:809#private fun loadVersionSnapshot(`.
Reading a backup, the loose file on disk is the *current* code, not the historical one, so the inline
copy is the only correct source. That is why the inline copy exists;
`src/jvmMain/kotlin/afield/examples/HistoryFileInlineSmokeTest.kt:47#Live resolution should prefer the current sidecar`
checks both cases side by side.

If you have edited sidecars and want `metadata.edn`'s inline copies brought back into agreement
without opening the document,
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1277#fun refreshInlineFilesFromDisk(directory: String): List<String>`
does exactly that and nothing else.

## Editing the directory under a running Field

Field watches the document directory while it is open, and the three artifacts get three different
treatments. `src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt:291#if (file.name == SavingContext.METADATA_EDN) {`
is the branch.

**A sidecar edit is picked up.** An external write to `<box>_<property>_.<ext>` reloads that property
onto that box and resynchronizes it; if the box id in the filename is not in the session, the watcher
creates the box, gives it a free grid slot, loads the property and saves. Deleting a sidecar
externally only stops the watcher tracking it; the in-memory property is left alone
(`src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt:326#External deletion of sidecar`). This is the
supported way to edit a box's code from outside Field.

**A `metadata.edn` edit is reconciled at box granularity, not merged.** An external modification runs
`src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt:500#private fun reconcileMetadata(workspaceDir: File)`,
which adds boxes present on disk and absent from the session, and tombstones boxes that were in the
last `metadata.edn` Field wrote or loaded and are absent from the new one
(`src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt:578#box.setPropertyRaw("_tombstone", true, doResync = true)`).
A box created in the session since that write is left alone: it was never in the file, so the edit
cannot have removed it. The properties of boxes already present in the session are left alone
(`src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt:496#properties of boxes already present in the`).
So *adding* or *removing* a box by editing `metadata.edn` under a live Field
works, and *changing a property of a box that is already open* does not — that edit is not applied,
and the next save overwrites it from memory. Hand-edit the sidecar instead.

**A `document.md` edit does nothing**, for the reason above: nothing reads it, and the watcher does
not decode it either.

Two more details of the watcher account for an edit that looks lost. Field marks
its own writes, and a watch event whose size and modification time still match a recorded internal
save is discarded — a 15-second grace window on the timestamp and a 120-second window on the
signature
(`src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt:161#private fun isInternalSaveEvent(file: File, currentTime: Long): Boolean`).
An external edit that happens to leave the file exactly the same length, moments after Field's own
save, is therefore invisible. And the watcher registers the document directory itself and no
subdirectory
(`src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt:175#val key = path.register(`), so nothing under
`files/` is watched at all.

## The autosave guard

An autosave — a `save` with no message — that carries fewer boxes than the `metadata.edn` already on
disk is refused rather than written, when the document has at least four boxes and at least a
quarter of them (minimum four) are missing from the snapshot:
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:636#private fun suspiciousPartialAutosave(root: String, snapshot: SaveSnapshot, message: String?): PartialSaveBlock?`.
The notification says so —
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:444#metadata.edn was left untouched.` — and an explicit save
with a message bypasses the check entirely, so you can still delete half a document and save it.

## The three write-only files

`operations.yaml` is the operation transcript, and it accumulates in memory rather than on disk.
Every box move, property edit, execute, launch and stop appends an entry to an in-memory list;
nothing reaches the file until a save calls
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:511#OperationTranscript.checkpoint(fn)`, and that call is inside
the `if (altered)` branch, so a save that changed no bytes does not checkpoint either. The
checkpoint **rewrites the whole file** from the whole in-memory list
(`src/jvmMain/kotlin/afield/tools/automation/operation_transcript.kt:821#fun checkpoint(documentPath: String): Boolean`),
which is why it grows monotonically within a session and why nothing prunes it: there is no
append path to bound. Edits are coalesced — repeated edits to the same property within 12 frames
collapse into one entry, and `_editorState` properties are skipped outright
(`src/jvmMain/kotlin/afield/tools/automation/operation_transcript.kt:725#if (property.endsWith("_editorState")) return`).

It is written atomically, which matters because the save path and any other process sharing the
workspace can both be reading it. `File.safeSave` has an opt-in `atomic` mode that writes a temp
file in the same directory and renames it into place
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1845#Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`),
taking a *copy* rather than a rename for the backup so the original never briefly disappears. All
five transcript writes pass `atomic = true`. Document saves do not: `metadata.edn`, the sidecars and
`document.md` go through the plain `writeText` branch.

`transcript.edn` is the flight recorder, and it is per Field process rather than cumulative. It is
loaded fresh on every launch and previous contents are never read back
(`src/jvmMain/kotlin/afield/tools/automation/transcript.kt:402#We intentionally do not load any previous transcript from disk.`),
so the first write of a new process replaces it — and backs the old one up exactly once, because
`createBackup` is `!state.previousProcessArchived`. A document with ten
`transcript_backup_*.edn` files has been opened by ten Field processes that ran something.

`history_metrics.edn` is written on every altered save, and it is the one artifact here whose write
is bounded but not atomic:
`src/jvmMain/kotlin/afield/document/history/HistoryMetrics.kt:421#file.safeSave(Edn.write(payload, Edn.CustomTypes()), maxBackups = 10)`
passes `maxBackups` and not `atomic`.

## What is in `document.md`, for the tool that reads it

`document.md` re-encodes `metadata.edn` in a form a text tool can walk. Its shape is fixed by
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:664#private fun buildDocumentMarkdown(snapshot: SaveSnapshot, documentName: String): String`:
a `field-document` EDN header, then one section per box in parent-first order
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:777#private fun parentFirst(transfers: List<BoxTransferEdn>): List<BoxTransferEdn>`),
each with an `edn [field-box id="…" role="properties"]` block carrying every property, followed by
one fenced block per text property tagged `[field-property box="…" name="…" path="…"]`.

Each box's EDN block also carries a `blockProperties` manifest. It lists, per text property, the
`name`, the `language`, the sidecar `path` when there is one, and the `lines`, `characters` and
`sha256` of the text
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:948#private fun sha256Hex(text: String): String`). A tool can
therefore tell whether the fence it is reading still matches the sidecar beside it without opening
the sidecar. Fence lengths adapt: a property whose text contains a triple backtick is emitted inside
a longer fence rather than escaped
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:918#private fun appendFence(`), and a leading comment block on a
code property is lifted out as narrative prose above the fence
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:860#private fun extractLeadingNarrative(property: String, language: String, text: String): ExtractedNarrative?`).

## The persistent-property list is sticky, and process-global

`Box.ioPersistentProperties` is a `mutableSetOf`, not a `val` of constants, and three things add to
it while Field runs. Each small language adds its own root markers when it installs — Nano's
`isNanoRoot`, `nanoLaunchDelayMs` and `nanoMemoryInit` in one call at
`src/jvmMain/kotlin/afield/lang/nano/NanoInterface.kt:502#Box.ioPersistentProperties.addAll(setOf("isNanoRoot", "nanoLaunchDelayMs", "nanoMemoryInit"))`,
and the other language roots the same way. `Box.syncToProperty`, the helper that wires an
output-panel control to a box property, marks that property persistable in each of its five type
branches
(`src/jvmMain/kotlin/afield/ui/output/outputs.kt:2465#fun Box.syncToProperty(propertyName: String, initialValue: Any`).
And **loading any document merges that document's own list into the global set**:

```diffstatic {data-lang=kotlin}
// Merge the saved properties with the current global set
Box.ioPersistentProperties.addAll(propsList)
```

`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1215#Box.ioPersistentProperties.addAll(propsList)`, reached from
`readEdnTransfers` on every open. The set is on the `Box` companion, so it is per *process*, not per
document: open a document whose header lists a property name, and every other document open in that
Field will persist that name too, until the process exits. Nothing removes a name.

That is why the list in a saved header is usually longer than the one declared in
`src/jvmMain/kotlin/afield/document/graph/Boxes.kt:428#var ioPersistentProperties = mutableSetOf(`. Most of the
extra names are language-root markers registered by the first mechanism above (the `is…Root` flags,
`mainLanguage`, Nano's configuration properties); the rest entered the set in some earlier session
and have been carried in the header ever since. A saved header records what that process had
learned, not a fixed declaration.

## What `metadata.edn` is mostly made of

Only properties whose names match one of the patterns in
`src/jvmMain/kotlin/afield/document/graph/Boxes.kt:428#var ioPersistentProperties = mutableSetOf(` are written at
all; everything else on a box is dropped at save. One of those patterns is a regex, `.*_editorState`,
and it is why a `metadata.edn` is larger than its boxes suggest: every editor keeps cursor and fold
state per box per property, and that state is saved.

That filter runs *before* the sidecar branch
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:153#fun toTransfer(boxes: Set<Box>, directory: String): List<BoxTransferEdn>`),
so a property listed in `ioSeparateProperties` alone is written nowhere: the sidecar branch is never
reached, and nothing lands inline in `metadata.edn` either. The two lists have to agree: every key in
`src/jvmMain/kotlin/afield/document/graph/Boxes.kt:498#var ioSeparateProperties = mutableMapOf(` is also matched by
a persistent pattern.
`src/jvmMain/kotlin/afield/examples/SidecarPropertyRegistrationSmokeTest.kt:50#object SidecarPropertyRegistrationSmokeTest`
(`examples.saving.sidecar-property-registration`) checks that across the whole registry, then drives a save
through `toTransfer` and asserts that a property in neither list leaves no sidecar and no EDN key.

`remote_js` looks like a sidecar-backed property but is not one: it is the name of the RemoteJS/WebXR
language root box in `templates/remote/metadata.json`. The Javascript in that template lives in the
root's children, under `code` and `customization`, and no `*_remote_js_.js` sidecar is ever written.

The root box is excluded from the sidecar branch by id, so the root's `metadata` property stays
inline in `metadata.edn` — that is the property
`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1056#fun updateRootMetadata(directory: String, metadata: String): Boolean`
edits — while a non-root box's `metadata` becomes a `<box>_metadata_.yaml` sidecar. Sidecar filenames
are `id + "_" + name + "_." + extension` and nothing else
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1651#return id + "_" + name + "_." + extension`), which is why
they read as UUIDs: the id is the box's uid, not its name, and renaming a box does not rename its
sidecars.

## Backup filenames

`<stem>_backup_00000.<ext>`, zero-padded to five digits, allocated as one past the highest index
present rather than by rotation
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1906#private fun File.nextSafeSaveBackup(backupDir: File): File`),
so index order is save order and the numbers do not shift under pruning. `metadata.edn` becomes
`metadata_backup_00000.edn`; `alpha_code_.kt` becomes `alpha_code__backup_00000.kt`, with the double
underscore, because the stem already ends in one. A backup is skipped when the previous contents are
already the newest backup, so a save that reverts a file and saves again does not duplicate it.

Pruning drops the oldest. In the thirteen-save run above the surviving
`alpha_code__backup_*.kt` are indices 00002 to 00011 — the first two were deleted, and no index was
reused — against `metadata_backup_00000.edn` through `metadata_backup_00011.edn` still all present
(`src/jvmMain/kotlin/afield/document/persistence/saving.kt:1920#?.drop(maxBackups)`).

The history panel reads exactly these files:
the `backups/metadata_backup_*.edn` set sorted lexically — so index order is version order — with
the live `metadata.edn` appended as the newest version
(`src/jvmMain/kotlin/afield/document/history/history.kt:16#A version list is the lexically sorted`).
[Document History](document_history.md) covers using it. On disk, history costs one unpruned copy
of `metadata.edn` per changed save, and that is the only artifact in the directory with no bound on
how much it can accumulate.
