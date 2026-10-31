# Field and SIGINT

Every agent brief, the smoke runner and the docs runner stop Field with `kill -INT` on the PID
listening on its port. Backlog item N0928-11 recorded that Field launched through `field.sh`
ignored SIGINT and needed SIGTERM. This page records why, what changed on 2026-09-29, and the
measurements.

## Why SIGINT was ignored

`field.sh` starts the backend as `"$JAVA_BIN" @build/field-launch/args "$@" &` from `#!/bin/sh`,
which has no job control. POSIX gives an asynchronous command in such a shell SIGINT and SIGQUIT
set to *ignored*. The shell cannot take that back: `( trap - INT; exec java … ) &` still starts
java with SIGINT ignored, under both `/bin/sh` and `bash` ("signals that were ignored on entry
cannot be trapped or reset").

HotSpot then declines to install its own handler. `java.lang.Terminator` asks for INT, and
`JVM_RegisterSignal` returns "ignored" without installing anything. So the JVM, which *is* the
listener PID (`java` ← `sh field.sh` ← `sh -c`), silently drops `kill -INT`. SIGTERM still worked,
because `&` does not touch it.

The same thing happens to any JVM whose ancestry passes through a backgrounded non-interactive
shell, such as an agent's `./field.sh &` or a harness started that way. The backdoor-managed path
(`gradlew runJvm`, the JVM forked by the Gradle daemon) was **not** affected in these trials: the
daemon handed its child a default disposition, and Field's startup log said so (no `SIGINT:` line;
see below).

A minimal repro, independent of Field: `sh -c 'java Sig &'`, then `kill -INT` leaves it running.

## What changed

- `afield.app.server.InterruptSignal` (`src/jvmMain/kotlin/afield/app/server/InterruptSignal.kt`) runs first
  in `Server.main`. It reads the current disposition the only way the JVM allows, by registering a
  handler and looking at what comes back. If SIGINT was ignored, it clears that with libc
  `signal(SIGINT, SIG_DFL)` through FFM and registers a handler that exits with 130, as Terminator
  does, so shutdown hooks run. A second SIGINT during shutdown halts at once. If SIGINT was not
  ignored, Terminator's handler goes straight back. Startup logs `SIGINT: RESTORED_FROM_IGNORED`
  on the `startup` channel when the repair was needed, and nothing when it was not.
- `afield.document.persistence.DocumentShutdownFlush` adds an `afield-document-flush` shutdown hook. The autosave loop
  saves every ~5 s, so an edit made in the last few seconds before *any* signal was lost. The hook
  makes the loop's own save (same predicate) inline, with a 4 s budget. `SavingContext.save(…,
  inline = true)` runs the existing background body on the caller, because the misc pool it
  normally uses is being shut down by its own hook at the same time.

The stop contract is unchanged. SIGINT on the listener PID was already the documented stop. It
now works on every launch path measured.

## Measurements (2026-09-29, macOS, port 9622)

The machine was heavily loaded by other sessions throughout. The load average is given per group.
"Exit" is the time from `kill -INT <listener>` until the PID is gone. The port was always free
before that.

| Launch path | Build | Trials | Result | Load |
|---|---|---|---|---|
| `field.sh` server-only, backgrounded, idle | before | 3 | SIGINT ignored for 30 s in 3/3; SIGTERM then 1.46–1.67 s | 31–32 |
| `field.sh` server-only, backgrounded, idle | after | 3 | exit 1.52–1.53 s, port free 0.19–0.39 s | 51–81 |
| backdoor-managed (`gradlew runJvm`, JDWP on), idle | after | 3 | exit 1.71–1.76 s, port free 0.16–0.79 s | 100–113 |
| backdoor-managed, launched `loop {}` box running | after | 3 | exit 1.68–2.02 s, port free 0.16–0.73 s | 80–220 |

The third backdoor trial in each row ran with the flush hook as well. The backdoor rows have no "before" build. The disposition there was *not* ignored: the startup log
had no `SIGINT:` line, which means `ALREADY_HANDLED`, so the old code already exited on SIGINT on
that path. Three further backdoor attempts never booted, which has nothing to do with SIGINT. Two
of them died on `JDWP … bind failed: Address already in use`, and one sat at "booting Field…" for
more than 400 s at load ~100.

Save on SIGINT: `field.sh` launch, then through the backdoor a new document, `new-box`, and
`set @last code "// marker-…"`, with SIGINT the moment the `set` was acknowledged:

| Build | Trials | Marker on disk |
|---|---|---|
| before flush hook | 3 | 0/3 (folder had only `README.md` and `AGENTS.md`) |
| before, control with SIGINT 12 s after the set | 1 | 1/1 |
| after flush hook | 3 | 3/3, flush 68–85 ms, exit 1.83–2.11 s |

## Regression test

`./gradlew jvmTest --tests afield.app.server.InterruptSignalTest --rerun` (about 5 s). It starts a child JVM
the way `field.sh` does (`java … &` from `/bin/sh`) and signals it. The premise case: without
`InterruptSignal.install()`, SIGINT is ignored. If that ever stops failing, the fix is no longer
what makes the difference. The fix case: the child exits 130 and its shutdown hook runs. The
foreground case: the JVM's own handler is kept, and the child still exits 130.

## Which edits reached disk (2026-10-20)

Backlog N1019-1: in one of two nightly runs, a box with no code created about 7 s before the
session ended never reached `metadata.edn`. The cause was not a race. The autosave loop and the
flush hook both save a document only when `SessionInformation.pendingSidecarSaves` is non-empty,
and only *sidecar* properties (`code`, shaders, `notes`, ...) were ever marked, by explicit
`markSidecarPending` calls in a few handlers. A new box without code, a move, a rename, a
connection, or a plain property such as `autorun` marked nothing. Each reached disk only if some
sidecar edit, or an open (which marks the root's `fileManagerAccessHistory`), came after it and
before Field stopped. The "1 of 2" was whether something else happened to be pending.

The fix: `Box.setPropertyRaw`, `removePropertyRaw`, `connect` and `disconnect` call
`Box.notePersistentChange`. It marks the document when the property is in
`ioPersistentProperties`, is not never-persisted (`*_editorState`), the document writes to disk,
and it is not loading. The pending key is still `(uid, property)`, so `FileWatchManager`'s
"unsaved local edit" test, which asks about one sidecar, sees nothing new. A document whose last
client detaches (no `--keep-running`) now saves its pending edits at once
(`DocumentShutdownFlush.saveIfPending`, shared with the shutdown hook) instead of waiting for the
loop.

Repro harness (not tracked; the method): Field started with `./gradlew runJvm` on its own port and
workspace, driven by `field_backdoor.py --attach-existing`. Per edit kind, one document: a seed
box with code and a second box, saved with `agent-save-document`. Then a second invocation
opens it, waits 7 s so the loop has saved and cleared everything the open marked, makes one edit,
and Field is signalled. Then `metadata.edn` is read. "SIGINT" means about 0 s after the last edit,
which tests the flush hook. "SIGKILL" means 8 s after it, with no hook, which tests the loop alone.

| Edit | before, SIGINT | before, SIGKILL +8 s | after, SIGINT | after, SIGKILL +8 s |
|---|---|---|---|---|
| new box, name only | lost | lost | saved | saved |
| new box at x=333 | lost | lost | saved | saved |
| move (`set-rect` frame, the canvas drag path) | lost | lost | saved | saved |
| rename | lost | lost | saved | saved |
| delete (`delete-box`) | saved | saved | saved | saved |
| code edit | saved | saved | saved | saved |
| connect | lost | lost | saved | saved |
| `autorun true` | lost | lost | saved | saved |

There was one trial per cell, with load average about 30–40. Delete already saved on the old build. Something on the delete path marks a sidecar as pending;
which call it is was not traced. Document
close was tested on the new build: new box, the invocation ends without `--keep-running`, and
`metadata.edn` is read 0.3 s later. 3/3 trials had it on disk, with the log line `saved … on
detach (1–6 ms)`.

`AutosaveDirtyTrackingTest` (jvmTest) pins each kind: six cases failed on the old build ("the edit
left nothing pending") and all nine pass on the new one, including the two that check nothing
is marked for an editor-state write or a write during load.

`backups/<sidecar>.external-<ms>` copies (FileWatchManager, an external edit beaten by an unsaved
Field edit) are now capped at the newest 10 per sidecar, the same cap as sidecar backups
(N1019-3). They are pruned rather than shown in history. Each copy is a whole-file recovery copy
announced by a notice when it is made. It is not a document state, so a timeline entry would be
a version that cannot be restored.

### Edits that bypass marking (2026-10-22)

The two gaps the 10-20 pass left open (N1020-6) are closed by a content check rather than by more
marking calls:

- **In place.** A persistent collection mutated rather than reassigned
  (`box.layerFrames[layer] = rect`) never passes `setPropertyRaw`.
- **During load.** Box code that runs before the loader clears `SessionInformation.loading` (the
  wait for box Kotlin runtimes) writes with marking off, since loader writes and user writes cannot
  be told apart there.

`afield.document.persistence.PersistedFingerprint` hashes every property the document would write
(the `snapshotBoxes` filter; box references as ids; null and empty collections as absent).
`SessionInformation.savedFingerprint` records it when the loader has read the document and at
every save. `DocumentShutdownFlush.saveIfPending`, which the 5 s loop now calls as well, saves when
something is marked **or** the fingerprint differs, so the loop, the detach save and the shutdown
flush share one predicate. `set-active-layer` also marks `layerFrames` explicitly.

The tests turned up a third problem: a `Delegation` default (`layerFrames`, ...) materialises an
empty collection through `setPropertyRaw` on first read, and that marked the document, so just
reading `box.layerFrames` re-saved it. That first-read write no longer marks.

`AutosaveDirtyTrackingTest` gained three cases, all red at `bf8e431dc` and green after (12/12): an
in-place `layerFrames` write is saved by the shutdown flush; a write made while loading is saved
once loading ends; an untouched document whose `layerFrames` were read is not saved.

End to end through the backdoor (port 9611). A box materialises and assigns `layerFrames`, then a
thread writes `layerFrames["e2e"]` in place 20 s later, and SIGINT goes to the listener a fixed
delay after that write. The column is whether `"e2e"` was in `metadata.edn` after the JVM exited:

| Build | SIGINT after the write | Trials | `"e2e"` on disk |
|---|---|---|---|
| base `bf8e431dc` | 150 ms | 3 | 0/3 |
| base | 1.5 s | 1 | 0/1 |
| fixed | 150 ms | 3 | 3/3 (none on disk before the SIGINT, so the shutdown flush wrote it) |
| fixed | 1.5 s, 7 s | 2 | 2/2 (already on disk before the SIGINT: the 5 s loop) |

A first version of this trial read `layerFrames` for the first time inside the thread, and base
saved it 2/2: the first-read marking above masked the gap. Script:
`.nightly-tmp/e2e_trial.sh` in the 2026-10-22 nightly worktree (not tracked).

### Still open

- A value of a class with an identity `hashCode` that is mutated in place is invisible to the
  fingerprint, as it was to marking. Every persisted type today (`Rect`, strings, numbers,
  collections of those, box refs) hashes by content.
- A brand-new document has no fingerprint until its first save, so an unmarked edit before then is
  caught only by the next marked one.

## Tripwire, no-op saves, macro replay (2026-10-23)

**Tripwire (N1022-2).** `PersistedFingerprint.entries` keeps one hash per `(box id, property)`;
`SessionInformation.savedFingerprintEntries` holds the set that matched disk. When
`DocumentShutdownFlush.saveIfPending` saves, every key whose hash moved without being in
`pendingSidecarSaves` gets one line on the `warning,persistence` channel:

```
[warning,persistence] unmarked edit: box 7cb72a9c-... (Mut) property 'layerFrames' changed without notePersistentChange; saved on autosave
```

The save still happens; the line names a mutator to fix. It is always on (one hash map per 5 s
tick per open document). In a live backdoor session on port 9611 it fired for box code writing
`thisBox.layerFrames["start"] = rect`, and for nothing else exercised: new box, `set-rect`
moves, `set-active-layer` in and out of a layer, `set-visible-layers`, `set-string`, and a macro
replay (create, connect, edit). `Box.layerFrames` now returns `PersistentMapView`, which marks
on `put`/`putAll`/`remove`/`clear`; the same run afterwards saved with no tripwire line. The other
subsystems were driven on 2026-10-24 (next section).

**No-op saves (N1020-7).** `set-string` (and every explicit `markSidecarPending` caller) marks
even when the text is unchanged. `safeSave` already skips writing identical files, so this was
never a *disk* write: measured live, three identical `set-string`s 6 s apart left `metadata.edn`'s
mtime and the backup count (15) unchanged on both builds. What it cost was a full save pass per
tick (snapshot, EDN serialisation, read-and-compare of every file): `save_reload.log` showed 3
`[save]` passes with `changed=0` with the skip disabled, and 0 with it. The skip: when the
fingerprint equals the saved one and every marked value hashes by content
(`PersistedFingerprint.hashesByContent`), the marks are cleared and nothing is saved. A marked
value hashed by identity still saves. A box that writes a *different* value every frame still
saves once per tick; that was not throttled.

**Macro replay, then SIGINT (N1020-1).** `tools/sigint_macro_persistence_smoke.py --port <p>
--workspace <ws>` replays a create/create/connect/edit macro into a fresh document of a Field you
started, signals it as soon as `macro run` returns, waits for exit and checks `metadata.edn` and
the sidecar. Run 2026-10-23 on port 9611: SIGINT 0 ms after the macro, Field exited, 7/7 checks
held. It is a Python script, not a `FIELD_TEST` jvm-main smoke, because it needs a real server it
may stop.

## Subsystems through the tripwire (2026-10-24)

`AutosaveSubsystemMarkingTest` drives each older subsystem that persists into a root or box
property through its own write path, in a saved document, and checks three things: the write is
*marked* (`(box, property)` lands in `pendingSidecarSaves`), the save that follows leaves the
tripwire *quiet* (`DocumentShutdownFlush.lastUnmarked` empty), and the value is *on disk* as the
loader reads it back (`SavingContext.readEdnTransfers`). A future write path in any of these that
mutates a persisted value without marking fails the first or second check.

| Subsystem | Property | Write path driven | Marked at base | Now | Test |
| --- | --- | --- | --- | --- | --- |
| Bookmarks | root `bookmarks` | `set-string` handler's `setPropertyRaw(textPropertyValue(...))` | yes | yes | `bookmarks` |
| Split panes | root `panes` | same | yes | yes | `splitPanes` |
| Drawing layer (string) | root `drawing` | same | yes | yes | `drawingLayerSentAsAString` |
| Drawing layer (merge) | root `drawing` | the real `notation-scene` handler (`installNotationHandlers`) → `NotationStore.update` | yes | yes | `drawingLayerMergedThroughNotationScene` |
| Notation from box code | root `drawing` | `box.notation { rect(...) }`, then an edit of the same mark | yes | yes | `notationWrittenByBoxCode` |
| Keyframe editor keys | `keyframeData` | `saveKeyframeData` (what the `__keyframes__` handler calls) | yes | yes | `keyframeEditorKeys` |
| Keyframe interpolants | `keyframeInterpolants` | `registerInterpolant` with source | yes | yes | `keyframeInterpolantSource` |
| Layer frames, map views | `layerFrames` | `entries...setValue`, `keys.removeIf`, `values.iterator().remove()` | **no** (N1023-5) | yes | `layerFramesThroughEveryMapView` |
| Controls, gesture | `_controlGroupValues` | `controlGroup { slider }`, `updateControllerControlValue` | yes | yes | `controlGestureIsMarked` |
| Controls, keyframe-driven | `_controlGroupValues` | `updateControllerControlValueProgrammatically` (key recall, a playing curve) | **no** | yes | `controlDrivenByKeyframesIsMarked` |
| Slideshow | `slideShow` | `box.slideShow { step(...) }` | yes | yes | `slideshowDefinitionIsMarkedButNotWrittenToDisk` |

"Marked at base" was observed by running the test with this branch's `src/jvmMain` changes
stashed: the two **no** rows failed ("written without marking"), the other nine passed.

- **Layer-frame views (N1023-5).** `PersistentMapView` marked `put`/`putAll`/`remove`/`clear`
  only. `entries`, `keys` and `values` now return views (`MarkingSet`, `MarkingCollection`,
  `MarkingEntry` in `Boxes.kt`) that mark on an iterator `remove()`, `remove`, `removeAll`,
  `retainAll`, `removeIf`, `clear` and `entry.setValue`, and only when something changed.
- **Keyframe-driven controls.** `updateCachedControlGroupValueInMemory` rewrote
  `_controlGroupValues` with `properties[...] = values`, past marking, on purpose ("without
  recording one document edit for every animation frame"). Since the 10-22 fingerprint that
  write was saved anyway on the next tick, with a tripwire line each time a curve played. It
  now calls `notePersistentChange`; still no resync and no flight-recorder entry per frame. The
  persisted value is kept because `KeyframerTest` pins it (a rerun restores a recalled key). A
  playing curve therefore saves once per 5 s tick, as before, now for a named reason.
- **Slideshow.** `slideShow` is in the persistent list, but its value is a live
  `SlideShowInstance` (lambdas). It is marked and a save runs, and nothing reaches
  `metadata.edn`: the box's code rebuilds it on the next run. The test pins both halves.
- The `set-string` rows call the handler's own statement, not the handler: `Server.server()`
  installs it with the rest of the server. The notation row runs the real handler.
- The persistence of each row was checked as the value in `metadata.edn`, not by reopening a
  document in a live Field. Manual check: add a bookmark (Alt+B) or split the canvas, `kill -INT`
  Field within 1 s, restart, and the bookmark or split is there.

Also in this pass (N1023-2): the missing-inherited-property error counted the document root and
metaroot as parent boxes, so a box with one parent read "any of its 3 parent boxes".
`ContextResolution.Search.boxAncestorsExamined` leaves them out, the message reads "in 'Flower'
or the 1 box above it (the document root was searched too)", and FieldML's `prop` message counts
the same way (`MissingInheritedPropertyCountTest`).

## Not verified

- The Electron branch of `field.sh` (a GUI window). It backgrounds the JVM the same way, so the
  same fix applies, but no trial ran it.
- Ctrl-C in a real terminal. There the JVM is in the foreground process group, receives SIGINT
  itself, and also gets SIGTERM from `field.sh`'s trap.
- Windows. `InterruptSignal` does nothing there.

## Template inserts (backlog N1030-4 d, 2026-10-31)

The templates guide recorded a template insert on disk in only 2 of 4 runs. Re-measured on port
9540 (backdoor-managed): a fresh document, a warm `Killer` box, `raw-send insert-template` of a
personal template, then `Killer` sending `kill -INT` to its own JVM 4 ms after the insert was
dispatched (plus one compile). The inserted box was in `metadata.edn` in 4 of 4 trials. Insertion
marks the document pending like any box creation, so the shutdown flush saves it:
`AutosaveDirtyTrackingTest.aTemplateInsertIsSaved` inserts through `insertTemplateInto` and runs
only the flush. Verdict: no loss on this path. The earlier 2 of 4 was not reproduced; its runs
were not recorded closely enough to say which document they read.
