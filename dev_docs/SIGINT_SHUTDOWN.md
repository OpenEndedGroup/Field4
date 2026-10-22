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

- Churn (N1020-7): a box that rewrites a persisted property every frame causes one full save per
  5 s tick (snapshot, write, backup rotation capped at 10). Not measured or throttled.
- A value of a class with an identity `hashCode` that is mutated in place is invisible to the
  fingerprint, as it was to marking. Every persisted type today (`Rect`, strings, numbers,
  collections of those, box refs) hashes by content.
- A brand-new document has no fingerprint until its first save, so an unmarked edit before then is
  caught only by the next marked one.
- The macro-replay/`--as-copy` SIGINT smoke test (N1020-1) was not written.

## Not verified

- The Electron branch of `field.sh` (a GUI window). It backgrounds the JVM the same way, so the
  same fix applies, but no trial ran it.
- Ctrl-C in a real terminal. There the JVM is in the foreground process group, receives SIGINT
  itself, and also gets SIGTERM from `field.sh`'s trap.
- Windows. `InterruptSignal` does nothing there.
