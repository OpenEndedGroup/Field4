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

### Still open

- Edits made while a document is `loading`, including the wait for box Kotlin runtimes after the
  metadata has been applied, are not marked, and the loop and the flush both skip a loading
  document. Loader writes and user writes are not told apart there.
- A persistent collection mutated in place rather than reassigned (for example a `layerFrames`
  map) goes past `setPropertyRaw` unseen. `children`/`parents` are covered through
  `connect`/`disconnect`.

## Not verified

- The Electron branch of `field.sh` (a GUI window). It backgrounds the JVM the same way, so the
  same fix applies, but no trial ran it.
- Ctrl-C in a real terminal. There the JVM is in the foreground process group, receives SIGINT
  itself, and also gets SIGTERM from `field.sh`'s trap.
- Windows. `InterruptSignal` does nothing there.
