# `Box.stop` and the `Frames` end phase

**Status:** **FIXED TWICE. Contract B on 2026-08-19, then contract A on 2026-08-28.**

1. 2026-08-19 closed the leak without touching the end phase: `Box.stopHooks` (Boxes.kt) is a
   synchronous teardown list `stopTrackedJobs` drains after cancelling jobs, and `streamProcess`
   registers its `proc.destroy()` there. `.end { }` remained skipped by stop, deliberately.
2. 2026-08-28 took contract A as well, because the deliberate half turned out to be the half a user
   noticed: `loop { x++ }.end { x = 20 }` leaves `x` at `0` after the stop button, which reads as a
   plain bug from the box. See the **Contract A, 2026-08-28** section below.

Both fixes stand. `stopHooks` is not redundant — see the note in that section for the case the end
phase structurally cannot cover.

**Found:** 2026-08-19, while writing `public/docs/animation_time_model.md`. The page
first claimed the opposite; the demo document
(`dev_docs/agents/transcripts/docs_capture/frames_lifecycle.yaml`, box 4) pressed the
real stop button and the cleanup line never appeared.

**Introduced:** 2026-08-08, in `d17b20881` ("Pay down execution and generated-artifact debt"), the
commit that added `launchEpoch` in the first place. Before it, `onAtEndStart` did `box.jobs += endJob`
with no epoch check, so **stop ran the end phase and had always run it**. This was not a latent defect
on an undocumented API, as the severity note below concluded on 2026-08-19 — it was an eleven-day-old
behaviour regression on `.end { }` itself, and the sweep that found zero in-tree `.end { }` callers
was measuring Field's own source rather than anyone's boxes. That is what surfaced it on 2026-08-28:
a box that used to work.

## The mechanism

_Present tense throughout this section and the two after it describes the tree **as found on
2026-08-19**. `Box.registerEndPhaseJob` replaced `registerLaunchJob(endJob, launchEpoch)` at both
call sites on 2026-08-28; the epoch arithmetic below is otherwise unchanged._

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
  today. (This is the line that led the analysis astray — see **Introduced** at the top. Zero
  callers in `src/` says nothing about boxes, which are where `.end { }` is written.)
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

### What was still true, deliberately — until 2026-08-28

`.end { }` did not run on stop. `FramesSemanticsTest.boxStopCancelsTheEndBlockBeforeItCanRun` and
`reRunningTheBoxStillRunsTheEndBlock` pinned both halves, and box 4 of
`dev_docs/agents/transcripts/docs_capture/frames_lifecycle.yaml` showed the cleanup line not
appearing, because that was the contract. Only the leak was gone.

The gap that was left, stated plainly at the time: a box author's own `atEnd` callback still did not
run on stop. That is what got noticed, and what the next section closes.

### The tests

| test | asserts |
| --- | --- |
| `StreamProcessStopLeakTest.stopKillsTheProcess` | a real `/bin/sleep` is **dead** after `stop` (was: still alive) |
| `StreamProcessStopLeakTest.reRunningTheBoxKillsTheProcess` | the end-phase route still works |
| `StreamProcessStopLeakTest.theStopHookIsUnregisteredWhenTheProcessEndsOnItsOwn` | no stale hook after a natural exit — the case in point 3 (renamed `theOwnershipIsDroppedWhenTheProcessEndsOnItsOwn` on 2026-09-29, when the process moved from a stop hook to the run's `RunScope`) |
| `BoxStopHookTest` (5) | hooks run, run **once**, are drained, unregister, one throwing does not strand the others, and the drain happens **after** job cancellation |

39 tests green across `BoxStopHookTest`, `StreamProcessStopLeakTest`, `FramesSemanticsTest` and
`PhasedPageCoverageTest`.

## Contract A, 2026-08-28

**Reported from a box**, not from an audit:

```kotlin
var x = 0
loop {
    x++
}.end {
    x = 20
}
```

Launch, press stop, and `x` is whatever the loop counted to — not `20`. The expectation that `.end { }` is where the wind-down goes
is the obvious reading of the API, and "best-effort, except from the button a person actually
presses" is not a contract anyone infers. So the fence moved rather than the documentation.

### The change

`Box.registerEndPhaseJob` (Boxes.kt) — one line, plus the reasoning:

```kotlin
internal fun Box.registerEndPhaseJob(job: Job): Boolean = registerLaunchJob(job, captureLaunchEpoch())
```

Both `onAtEndStart` installers call it instead of `registerLaunchJob(endJob, launchEpoch)`:
`Box.launchFrames` (Phased.kt) and `registerFramesReturnValue` (ReturnValueRegistry.kt).

The end phase is the one piece of work that legitimately belongs to a launch stop has already
invalidated, because it is stop's own cancellation that *creates* it. Registering it against the
launch epoch was therefore always wrong; the epoch check is asking "did a stop land between
preparing this launch and dispatching it?", and for the end job the answer is "yes, by construction".

### What the cost analysis got right, and what it got wrong

The 2026-08-19 write-up rejected contract A on the grounds that "stop stops being a synchronous
barrier" and that draining end phases "means pumping the Runloop inside stop". The first half is
true and is the price paid. The second half was wrong, and is why this turned out to be a one-line
change rather than a design change:

**stop does not have to drain the end phase to run it.** The body is suspended in `yield()`; stop
cancels its job, and that resumption is *dispatched*, so the throw — and therefore the whole end
phase — was always going to happen on a later Runloop tick, after `stop` had returned. The Runloop
keeps pumping. Nothing needs to be drained from inside stop; the end phase simply had to be allowed
to register.

So what is given up is precisely that `stop` is not a synchronous barrier *for end-phase code*. It
remains one for everything else. What is kept:

* The end job goes into `box.jobs`, so a **second** stop cancels it. Pinned by
  `FramesSemanticsTest.aSecondStopCutsTheEndBlockShort`.
* `Frames.shouldCancelAtEnd` still re-fences it against the strand generation as it stood when the
  end phase began, so the generation route agrees with the job route.
* `registerLaunchJob`'s double-check around `jobs.add` still closes a stop racing the registration.

### Why `stopHooks` stays

It covers what the end phase structurally cannot: **a body that already returned never re-enters
`frame()`, so it never reaches the end phase at all.** A `streamProcess` whose child exited on its
own is exactly that case, and it is why the unregister lives in `reportEnd` rather than in `atEnd`
(point 3 above). The hook is also synchronous, which an OS resource wants and the end phase cannot
offer. `terminateProcess` is idempotent because after this change both routes can fire for one stop.

### One consequence, guarded

Stop followed immediately by a relaunch — the docs capture's `restartTarget` (screenshot.kt) — now
runs the *old* launch's end phase while the new launch is already on record. `execution.kt` writes
that record synchronously in `prepareBatchLaunchRequests`, before it compiles anything, so
`onAtEndStart` writing the older generation into `currentRunningStates` would make the strand read
as the previous launch winding down for as long as the compile takes, and `FrameCaptureGate`'s
`watermark > stoppedGeneration` predicate would wait with it.

Both `onAtEndStart` installers therefore claim the running state only if no newer watermark holds
the strand. This is a **reporting** guard, and the honest framing: no failing case was constructed
for anything worse. The removal at the end of the end phase always keyed on its own generation, so
it has never deleted a newer entry. Pinned by
`FramesSemanticsTest.theEndPhaseDoesNotReportOverANewerLaunch`, which fails with the guard removed.

### The tests for contract A

| test | asserts |
| --- | --- |
| `FramesSemanticsTest.boxStopStillRunsTheEndBlock` | after `KotlinExecution().stop(box)`, `.end { }` runs (was: never runs) |
| `FramesSemanticsTest.theEndBlockKeepsItsFramesAfterAStop` | the reported case, ten `frame()` calls deep: `x == 39`, and `x == 3` immediately after `stop` returns — stop is not synchronous for the end phase |
| `FramesSemanticsTest.theRealLaunchPathRunsAMultiFrameEndBlockOnStop` | the same through `ReturnValueRegistry.launch` and a hand-pumped `Runloop.runLoop.main`, plus the `"ending"` running-state the front end reads |
| `FramesSemanticsTest.aSecondStopCutsTheEndBlockShort` | a 100-frame end block does not survive a second stop |
| `FramesSemanticsTest.theEndPhaseDoesNotReportOverANewerLaunch` | the end phase leaves a newer launch's running-state record alone |
| `FramesSemanticsTest.reRunningTheBoxStillRunsTheEndBlock` | the route that always worked, unchanged |
| `StreamProcessStopLeakTest` (4) | unchanged and still green: the hook still kills the child synchronously, ahead of the end phase |

The third row is the one worth keeping honest about. Every other case in `FramesSemanticsTest` wires
the launcher's hooks by hand, and that is how this defect survived a whole page of tests: the
hand-wiring faithfully copied the code it was mirroring. That row returns a `Frames` to the real
registry handler instead.

`public/docs/animation_time_model.md` documents the new contract under
[`Box.stop` and the end block](../public/docs/animation_time_model.md), with both of the reported
snippets. Box 4 of `frames_lifecycle.yaml` now says case 1 prints both lines.

## The adjacent sweep, 2026-08-28

Asked whether anything else was broken the same way. Measured, not read: each case launched through
`ReturnValueRegistry.launch` on a hand-pumped `Runloop.runLoop.main`, then stopped through
`KotlinExecution.stop`.

**Hit by the same defect, all fixed by `registerEndPhaseJob`:** `eachFrame`/`animate`, `loop`,
`frames { while (frame()) … }`, `forever`, `frames { wait() }`, the combined end of `a + b`, a
returned `List<Frames>` (the composite path in `launchCompositeIfPresent`), and **`Box.launchFrames`**
— the second launcher, no in-tree callers, identical defect.

**Never exposed:** the other nine launch handlers the same commit touched — `AudioReturnValue`,
`BeatLaunch`, `BooleanNetworkSequencing`, `PatPatternReturnValue`, `JSONLineServer`, `JSONProcess`,
`SonicPiLaunch`, `FaustBox`, `FaustPattern`. Every one cleans up in an **ungated `finally` inside the
cancelled coroutine**, with `withContext(NonCancellable)` where the cleanup suspends
(`SonicPiLaunch`). None launches a new box-registered job during teardown, so the epoch fence cannot
reach them, and none of the `finally` bodies is gated on the epoch or the generation. That is the
structurally correct pattern and the reason `Frames` was the only casualty.

### `Frames.plus` — a separate defect, pre-existing

Found by the sweep, **not** caused by the epoch change; verified identical on clean `HEAD` by
stashing. A launcher installs its four fences on the `Frames` it is handed, and `plus` reaches its
children only through `body()`, so both children kept the defaults:

| child field | default | consequence |
| --- | --- | --- |
| `onAtEndStart` | `{ }` | **the child's end job was never registered with the box — nothing could cancel it** |
| `shouldend` | `{ false }` | no generation fence of its own; stopped only via the parent's `finally` |
| `shouldCancelAtEnd` | `{ false }` | the end-phase fence was dead too |

Measured, a child `.end { while (true) { frame() } }`:

```
before:  ticking after first stop: 18  jobs=0
         after second stop: 38 -> 58 (stopped=false)
         still ticking 40 pumps later: 98
after:   ticking after first stop: 18  jobs=1
         after second stop: 18 -> 18 (stopped=true)  jobs=0
```

Fixed by forwarding all four hooks, **lazily** — a launcher installs them after `plus` has returned,
so a copy would capture the defaults forever. A re-run now also fences each child directly: body
calls go `(5,5) -> (6,6)`, the one-further-body-call cost the fence always has, and both children run
their own end block.

Left deliberately, two things. All three end phases of `(a.end + b.end).end` route through one hook
pair, so the box can report `"ending"` up to three times for one stop, and the first end phase to
finish restores the parent's `shouldCancelAtEnd` while the others wind down; neither weakens the stop,
because every end job is in `Box.jobs`, which is the route `stopTrackedJobs` uses. And `+` now
**writes to its operands**, so `val c1 = a + b; val c2 = a + d` leaves `a` fenced to `c2`. Composing
twice was already broken — both parents drive the same `a.body()` and share its `frameNumber` — so
this narrows nothing, but it is a second reason not to.

`plus` is box-agnostic by design (no `Box` parameter), so the parent's hooks are its *only* route to
the box. That is what rules out the tidier fix of registering child end jobs directly.

Pinned by `FramesSemanticsTest.aRunawayChildEndBlockIsKillable` and
`plusForwardsTheParentsFencesToBothChildren`; **those two are the only ones that fail when the
forwarding is removed**, which is why the flat assertion test exists at all. The behavioural cases
cannot tell the versions apart, because children were already being cancelled indirectly.

### `frame()`'s `Boolean` is never `false`

Fell out of the same sweep. All three stop routes — `shouldend` flipping, `end()`/`stillborn`, a
direct job cancel — cancel the job before the `yield()` that `frame()` is suspended on, so the yield
throws and `frame()` never returns. `eachFrame`'s `while (frame())` condition is therefore decorative
and `loop`'s `while (true)` gives up nothing.

Worth pinning (`FramesSemanticsTest.frameNeverReturnsFalse`) because the end phase lives in that
`catch`: a `frame()` that started returning `false` would exit `eachFrame` through its loop condition
and silently stop running `.end { }` — this defect again, by a different route.
