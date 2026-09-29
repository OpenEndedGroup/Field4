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

- `afield.InterruptSignal` (`src/jvmMain/kotlin/afield/internal/InterruptSignal.kt`) runs first
  in `Server.main`. It reads the current disposition the only way the JVM allows, by registering a
  handler and looking at what comes back. If SIGINT was ignored, it clears that with libc
  `signal(SIGINT, SIG_DFL)` through FFM and registers a handler that exits with 130, as Terminator
  does, so shutdown hooks run. A second SIGINT during shutdown halts at once. If SIGINT was not
  ignored, Terminator's handler goes straight back. Startup logs `SIGINT: RESTORED_FROM_IGNORED`
  on the `startup` channel when the repair was needed, and nothing when it was not.
- `afield.DocumentShutdownFlush` adds an `afield-document-flush` shutdown hook. The autosave loop
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

`./gradlew jvmTest --tests afield.InterruptSignalTest --rerun` (about 5 s). It starts a child JVM
the way `field.sh` does (`java … &` from `/bin/sh`) and signals it. The premise case: without
`InterruptSignal.install()`, SIGINT is ignored. If that ever stops failing, the fix is no longer
what makes the difference. The fix case: the child exits 130 and its shutdown hook runs. The
foreground case: the JVM's own handler is kept, and the child still exits 130.

## Not verified

- The Electron branch of `field.sh` (a GUI window). It backgrounds the JVM the same way, so the
  same fix applies, but no trial ran it.
- Ctrl-C in a real terminal. There the JVM is in the foreground process group, receives SIGINT
  itself, and also gets SIGTERM from `field.sh`'s trap.
- Windows. `InterruptSignal` does nothing there.
