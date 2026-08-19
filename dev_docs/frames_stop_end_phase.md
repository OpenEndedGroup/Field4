# `Box.stop` cancels the `Frames` end phase before it runs

**Status:** **FIXED 2026-08-19 — contract B.** The leak is closed; the end phase is unchanged.
`Box.stopHooks` (Boxes.kt) is a synchronous teardown list that `stopTrackedJobs` drains after
cancelling jobs, and `streamProcess` registers its `proc.destroy()` there instead of relying on
`Frames.atEnd`. **`.end { }` is still best-effort and is still skipped by stop** — that was the
deliberate half of the choice. See [What was done](#what-was-done).

**Found:** 2026-08-19, while writing `public/docs/animation_time_model.md`. The page
first claimed the opposite; the demo document
(`dev_docs/agents/transcripts/docs_capture/frames_lifecycle.yaml`, box 4) pressed the
real stop button and the cleanup line never appeared.

## The mechanism

`Frames.atEnd` — what `.end { }` appends to — is reached from exactly one place:
`Frames.frame()`'s `catch`, when the pending `yield()` throws a cancellation
(`src/jvmMain/kotlin/afield/Phased.kt:80-108`). It runs the block on a fresh job in a
`NonCancellable` scope, and brackets it with `onAtEndStart` / `onAtEndFinish`.

Both launchers install an `onAtEndStart` that registers that end job with the box:

* `Box.launchFrames` — `Phased.kt:213-224`
* `registerFramesReturnValue` — `ReturnValueRegistry.kt:207-215`

and registration goes through `Box.registerLaunchJob` (`Boxes.kt:1114-1127`), whose
first act on a stale epoch is:

```kotlin
if (!isLaunchEpochCurrent(epoch)) { job.cancel(); return false }
```

`Box.stop` is `KotlinExecution.stop` → `Box.stopTrackedJobs()` (`Boxes.kt:1129-1146`),
and its **first** statement is `launchEpoch.incrementAndGet()`.

So the ordering is: stop bumps the epoch → the body's `yield()` throws → `frame()`
launches the end job onto the `Framer` → `onAtEndStart` hands it to
`registerLaunchJob` → the epoch is stale → **the end job is cancelled while still
queued**, before the Framer ever dispatches it.

Re-running a box is different, and this is the contrast that makes it a defect rather
than a design: a re-run bumps the strand *generation* only and leaves `launchEpoch`
alone, so the end job registers and `atEnd` runs normally.

## The evidence

_As originally measured, before the fix in [What was done](#what-was-done)._
`src/jvmTest/kotlin/afield/StreamProcessStopLeakTest.kt` and the two lifecycle cases
in `FramesSemanticsTest`. All asserted **then-current** behaviour, so they would fail
if it was ever fixed, and name this file when they do — which is exactly what
happened to the third row.

| test | asserts |
| --- | --- |
| `FramesSemanticsTest.boxStopCancelsTheEndBlockBeforeItCanRun` | after `KotlinExecution().stop(box)`, the `.end { }` block never runs |
| `FramesSemanticsTest.reRunningTheBoxStillRunsTheEndBlock` | after a generation bump only, it does |
| `StreamProcessStopLeakTest.stopLeavesTheProcessRunning` | a real `/bin/sleep` launched through `Box.streamProcess` is **still alive** after `stop` — **inverted on 2026-08-19 and renamed `stopKillsTheProcess`** |
| `StreamProcessStopLeakTest.reRunningTheBoxKillsTheProcess` | the same process is destroyed by a re-run |

That third test is the one that made this concrete rather than
theoretical: it observes the child through `ProcessHandle.current().descendants()`,
and refuses to pass unless the child actually started first, so it cannot succeed
vacuously. Both tests reap whatever they spawn in an `@AfterTest`.

## The sweep — who actually depends on the end phase

Whole tree, excluding `build/`, `public/field4/` and the docs page's own examples.

| thing | count | where |
| --- | --- | --- |
| assignments to `Frames.atEnd` in `src/` | **1** | `outputs.kt:571` |
| `.end { }` call sites in `src/` | **0** | — |
| `.end { }` call sites in docs / templates / transcripts | 2 | both mine, both cosmetic (repaint the last frame red) |
| `Box.launchFrames` call sites in `src/` | **1** | `outputs.kt:627`, `streamProcessFireAndForget` |
| in-tree callers of `streamProcess` / `streamProcessFireAndForget` | **0** | public API for box authors; nothing in Field's own source calls it |

(`atEnd` also appears 13 times in `ml/lex/Lexer.kt` as an unrelated
parser-position predicate, and as a *parameter* name on `streamProcess` itself — a
`suspend Box.(StreamProcessEnd) -> Unit` user callback, distinct from `Frames.atEnd`.
Neither is a user of the end phase.)

### The one that matters

`outputs.kt:571` is the whole exposure, and it is the worst possible single case:

```kotlin
runner.atEnd = suspend {
    process?.let { proc ->
        if (proc.isAlive) {
            proc.destroy()
            if (!proc.waitFor(250, MILLISECONDS)) proc.destroyForcibly()
            …
        }
        reportEnd(exitCode, terminated = terminated)
    }
}
```

That is not cosmetic final state. It is **the code that kills the external process**,
and `streamProcess`'s own KDoc (`outputs.kt:417-420`) promises:

> The returned Frames ends when the process exits; ending the Frames will kill the
> process.

Pressing stop does end the Frames. The process is still there. The user callback
`atEnd(StreamProcessEnd(... terminated = true))` is skipped with it, so a box author's
own teardown does not run either.

### Severity, honestly

* **Reachable:** yes, from any box, by anyone who calls `streamProcess` and presses
  stop. It is public, it is in the default `import afield.*`, and it is the obvious
  way to run a subprocess from Field.
* **Currently triggered in-tree:** no. Zero callers. Nothing Field itself does leaks
  today.
* **Documented anywhere users would look:** no. `outputs.kt` is outside
  `StdlibReferenceGenerator.SOURCE_ROOTS`, so `streamProcess` is absent from the
  codebase reference, and no docs page describes it.

So: a latent defect on an undocumented-but-public API, with a KDoc that states the
guarantee it fails to keep. Not an emergency; not nothing.

### Corroboration: the workaround already in the tree

`Server.stop` (`Server.kt:4825-4832`) carries this comment:

> If this box owns an afield.four Renderer (e.g. the agent graphics-context box),
> cancelling its jobs above never disposes the native window/surface. Do that now.

— and calls `disposeBoxRenderer(box)` explicitly, *outside* the end phase. Somebody
has already met this class of problem for renderers and routed around it rather than
through `atEnd`. That is evidence the end phase is not currently treated as a
dependable stop-time hook, and a hint that the second contract below may be the one
the codebase already believes in.

## Two candidate contracts

**A — `stop` runs the end phase.** Make the end job exempt from the epoch fence: give
`registerLaunchJob` an "end phase" variant that registers against the epoch captured
*before* the stop, or have `stopTrackedJobs` drain end phases before bumping. Then
`.end { }` means "whenever this animation stops, for any reason", `streamProcess`
keeps its documented promise, and `Server.stop`'s renderer workaround could
eventually move into an `atEnd`.

*Cost:* stop stops being a synchronous barrier. Today `stopTrackedJobs` guarantees
that nothing belonging to the old launch can run afterwards, which is what makes the
launch/stop race in `registerLaunchJob` safe. Any exemption re-opens that race for
end-phase code, and end blocks can call `frame()` (mine does), so "drain before
returning" means pumping the Runloop inside stop. That is a real design change, not a
patch.

**B — `.end { }` is best-effort, and the docs and KDoc say so.** Keep the barrier.
Amend `streamProcess`'s KDoc to stop promising a kill on stop, and give it a teardown
that does not depend on the end phase — the `disposeBoxRenderer` pattern, i.e. an
explicit hook in `Server.stop`, or a `ProcessHandle` registry the stop path drains.

*Cost:* every future "release this on stop" need has to be wired by hand into the
stop path, and `.end { }` stays a footgun that reads like `finally`.

## What was done

**Contract B, on 2026-08-19**, for the reason the cost analysis above gives: contract A is a
design change to what stop *means*, and the barrier it would weaken is load-bearing. Draining
end phases inside stop means pumping the Runloop from stop, and end blocks can call `frame()`.
That is not a change to make in passing.

So the fence stayed and the resource moved off the end phase:

1. **`Box.stopHooks` + `Box.registerStopHook`** (`Boxes.kt`). A `CopyOnWriteArrayList<Runnable>`
   drained by `stopTrackedJobs` **after** `jobsToCancel.forEach { it.cancel() }` — the same order
   the end phase used, so a hook never races a reader coroutine that is still pumping output.
   Each hook is isolated with `runCatching`, because `streamProcess`'s blocks for up to 500 ms and
   a box may own several.
2. **`streamProcess` registers its kill there** (`outputs.kt`). The teardown is extracted into a
   non-suspending, idempotent `terminateProcess()` with two callers that can race — the stop hook
   and `atEnd` — and the hook is unregistered from inside `reportEnd`.
3. **The unregister lives in `reportEnd`, not in `atEnd`**, and that distinction was found by a
   test rather than by reading: a process that exits on its own **never reaches the end phase at
   all**. The body reports the exit and returns, so `frame()` is never called again and `atEnd`
   never runs. Unregistering only from `atEnd` left a stale hook on the box for every completed
   process. `reportEnd` is the single funnel both paths pass through.
4. **`streamProcess`'s KDoc** now states what actually happens, including that the suspending
   `atEnd` *user callback* is still skipped on stop.

### What is still true, deliberately

`.end { }` does not run on stop. `FramesSemanticsTest.boxStopCancelsTheEndBlockBeforeItCanRun` and
`reRunningTheBoxStillRunsTheEndBlock` are **unchanged and still pass**, and box 4 of
`dev_docs/agents/transcripts/docs_capture/frames_lifecycle.yaml` still shows the cleanup line not
appearing — because that is still the contract. Only the leak is gone.

The remaining gap, stated plainly: a box author's own `atEnd` callback still does not run on stop.
Closing that means running suspending user code from the stop path, which is contract A's problem
again. **Contract A remains open** if `.end { }` should ever mean "whenever this stops, for any
reason".

### The tests

| test | asserts |
| --- | --- |
| `StreamProcessStopLeakTest.stopKillsTheProcess` | a real `/bin/sleep` is **dead** after `stop` (was: still alive) |
| `StreamProcessStopLeakTest.reRunningTheBoxKillsTheProcess` | the end-phase route still works |
| `StreamProcessStopLeakTest.theStopHookIsUnregisteredWhenTheProcessEndsOnItsOwn` | no stale hook after a natural exit — the case in point 3 |
| `BoxStopHookTest` (5) | hooks run, run **once**, are drained, unregister, one throwing does not strand the others, and the drain happens **after** job cancellation |

39 tests green across `BoxStopHookTest`, `StreamProcessStopLeakTest`, `FramesSemanticsTest` and
`PhasedPageCoverageTest`.
