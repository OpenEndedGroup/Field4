# Thread-ownership guards

Field has more than one main loop (`AGENTS.md`, "Runloop vs audio loop"). A synth `Signal` graph
belongs to the audio thread that renders it, and the renderer's frame calls belong to the Runloop.
Calling either from the wrong thread does not fail: it races, and the symptom (a click, a glitch, a
frame drawn half-updated) appears far from the cause. The thread-ownership guards report such a call
once, naming the box line it came from.

It is one pass of the box-bytecode pipeline; the shared rules are in
[`../BOX_BYTECODE_PASSES.md`](../BOX_BYTECODE_PASSES.md).

Source: `src/jvmMain/kotlin/afield/runtime/bytecode/ThreadOwnership.kt` (annotation, pass and
runtime), the library check in `Signal.render` (`src/jvmMain/kotlin/afield/audio/synth/Signal.kt`),
and the context claim in `FieldAudioEngine.start`. Tests: jvmTest
`afield.runtime.bytecode.ThreadOwnershipPassTest` and `afield.audio.synth.ThreadOwnershipLibraryTest`;
smoke `runtime.thread-ownership-guards.smoke`
(`src/jvmMain/kotlin/afield/runtime/bytecode/ThreadOwnershipSmokeTest.kt`).

## How it works

- `@ThreadOwned(owner, checkLastArgument = false)` (`BINARY`, functions) marks owned API. `owner` is
  `ThreadOwner.RUNLOOP` (the thread in `Runloop.mainThread`) or `ThreadOwner.AUDIO` (the thread that
  owns the context the call is given).
- `ThreadOwnershipPass` (`id = "threads"`) asks `CallTargets` about every call in box code, from the
  scan before emitting anything, so a method with no guarded call gets no visitor. Overrides inherit
  the annotation from the declaration (`SineOsc.render` finds `Signal.render`'s; a `StereoNode`'s
  finds `StereoRenderable.render`'s). For each guarded call it allocates a site (detail: a
  `ThreadGuard`) and inserts, from `visitMethodInsn`, immediately before the call:
  - `LDC site; INVOKESTATIC ThreadOwnership.check(I)V`, or
  - with `checkLastArgument`, `DUP; LDC site; INVOKESTATIC ThreadOwnership.check(Ljava/lang/Object;I)V`,
    so the check sees the `SynthContext`.

  Both leave the stack as they found it; no jump, label, local or frame. In the chain the guard runs
  after the interrupt poll and before the call-site anchor, so a call that is both guarded and
  anchored runs check, then arm, then the call (`theGuardChecksBeforeTheAnchorArmsBeforeTheCallIsEntered`,
  with a stand-in anchor since the anchor's code is not in this tree).
- `DUP` is the one single-slot instruction the framework allows, so the pass refuses, at rewrite
  time, any last argument it cannot pass: a wide `long`/`double` (`wide`), any other primitive
  (`primitive`, it is not an `Object`), no arguments, and a call through a `$default` stub, whose
  top of stack is the stub's marker, not the function's last argument (`default-stub`). A refused
  guard is logged once per target (`warn,execution,bytecode`) and inserts the owner-only
  `check(I)V`. `ThreadGuard.refused` records why.
- `ThreadOwnership.check` decides everything: a `ThreadOwnedContext` argument with an owner names the
  thread; an unowned context (an offline render's own) passes an AUDIO call; otherwise a RUNLOOP call
  must be on the Runloop (nothing is checked while there is none, as in a jvm-main entry) and an AUDIO
  call on a thread that currently owns a context.

### The library check

The bug that motivated the guards was library code: the graph-binding runtime sampled a
`Param.modulate(SineOsc(...))` modulator on the Runloop with its own context, advancing the
oscillator the audio thread was rendering. No box-code rewrite sees that call, so `Signal.render`
checks too, in its existing cache-miss branch (`checkRenderOwner`, once per node per block):

- the owner is the context's (`SynthContext.ownerThread`, a plain field `FieldAudioEngine`'s thread
  claims at start and clears in its `finally`);
- or, rendering under a context nothing owns, the owner of the context this node was last rendered
  under, if that was within the last two blocks of that context. That second rule is the sampler
  case: the Runloop's context is unowned, but the node it renders was rendered by the audio thread
  a block ago. A node the engine stopped rendering (unregistered) more than two blocks ago is no
  longer the engine's; an owner thread that has died owns nothing.

`ThreadOwnershipLibraryTest` reproduces the case off the audio thread both ways: through
`Param.evaluateControl` (the historical path) and `GraphBindingContext.sample`.

### Reporting

Both report through `ThreadOwnership.report`: a `warning,execution` log line, `recentReports()` and
the `onReport` listeners. The box line comes from one place, `boxFrameOf`:

- a pass report names the guarded call's own line when `Site.boxLine()` proves it. When it does not
  (a line the macro pass rewrote, `println(` compiled as `_printlnHere(`; a box edited since), the
  report gives the compiled line rather than ask the stack, whose innermost *proven* frame would be
  a caller's line and read as the culprit (`anUnprovenLineIsReportedAsItsCompiledLineNotACallersLine`);
- a library report uses `SnippetClasses.boxFrame` of the stack it saw, and names the first frame
  outside the synth graph as the caller (`Param.evaluateControl`, `GraphBindingContext.sample`).

Warn once per site: the pass marks the site's guard and both paths share a key per compiled line, so
a render that both the pass and the library see (an owned context on the wrong thread) is one report.
The library path walks the stack at most four times a second; a violation that recurs every block
between walks costs a counter (`violations`, `libraryViolations`).

### Gates

`ThreadOwnership.enabled` (plain field) gates insertion (`wants`, read at evaluation) and every
report; `AFIELD_BOX_THREAD_GUARDS=off` or `-Dafield.boxBytecode.threadGuards=off` starts it off.
The checked path reads only plain fields: the gate, the guard table, the cached Runloop thread, a
context's owner. Only a mismatch re-reads `Runloop.mainThread` (volatile). So the JIT may hoist the
whole check out of a loop, as the measurement shows.

## What is owned

Annotated (deny list):

| API | owner | why |
|---|---|---|
| `Signal.render` | AUDIO, last argument | the per-cycle cache and every node's DSP state (phase, filter memory, envelopes) advance on render |
| `StereoRenderable.render` (and so `StereoNode.render`) | AUDIO, last argument | the stereo half of the same graph |
| `Renderer.render`, `Renderer.run`, `Renderer.runForever` | RUNLOOP | the frame loop: unsynchronised frame state (`pendingDesktopImages`, `frameCounter`, `renderStart`/`renderEnd` hooks), command buffers, GLFW polling |
| `Renderer.beginGeometryWrites`, `Renderer.endGeometryWrites` | RUNLOOP | documented as "from anywhere in the Runloop"; they acquire the frame image and run geometry producers |
| `Window.pollEvents` | RUNLOOP | `glfwPollEvents` and the box `onPoll` hooks |

Deliberately not annotated (builders stay callable from any thread): every graph constructor and
operator, `Param.base`/`supplier`/`mod`/`modulate` (they notify `GraphBindingRuntime`, built for
that), `SynthContext.warmUp` (an offline context), `AudioPatchSpec` and registration;
`Renderer.plusAssign` (`renderer += ...`), `renderTargetFor`, `windowContext`, `runOnQueueThread`
and `onQueueThread` (built to be called from anywhere), `whenCurrentGraphicsFrameComplete`,
`captureWindowToFile` (runs on `Runloop.miscPool` by design), `cleanup` (reached from stop paths
whose thread is not pinned).

Library calls of annotated API (`RectSL`, the jvm-main GPU smokes calling `renderer.render()`) are
not box code and are not checked by the pass; the jvm-main ones have no Runloop anyway.

## Measurements

- Guarded call on its owner thread, in a loop of 5,000,000 (`guardOverheadOnAnOnThreadCall`, best of
  5): 0.34 to 0.35 ns/call guarded, 0.34 to 0.35 ns/call unguarded. The check's plain reads are loop
  invariant, so the JIT hoists it; this is the loop the interrupt's volatile read slowed ~20x.
- The library check's cost on the audio path was not measured: it is one plain field read, at most
  one more, and a thread compare per node per block, on the cache-miss branch only.
- Smoke (`runtime.thread-ownership-guards.smoke`, real K2 under a live Runloop): G1 a `//%-` box's
  `sine.render(buf, 64, owned)` on the Runloop reported once at box line 5 (pass site; the library
  check saw it too and was deduplicated); G2 a Runloop-owned call from a thread the box started,
  line 6; G3 a second box rendering, with an offline context, an oscillator the stand-in audio
  thread had just rendered: reported by the library check alone, at its line 3; G4 nothing with the
  guards off.

## Limits

- A guarded call whose line is unproven gets the compiled line, not a box line. The library path
  has no site, so for it an unproven innermost frame yields the next proven frame out, which can be
  a caller's line.
- The library check is rate-limited globally: two different violating lines within 250 ms are
  reported at successive walks, not together. A violation that happens once, within 250 ms of
  another, is counted but not reported.
- AUDIO ownership is only declared by `FieldAudioEngine`. Other real-time hosts (the Dough and
  PatSpatial engines) render with contexts nothing owns, so they are not checked.
- The cached Runloop thread is refreshed only on a mismatch, which is right for a Runloop that
  starts once; tests that swap `Runloop.mainThread` reset it.
- Reports go to the log, `recentReports()` and `onReport`; nothing yet shows them in the box's own
  output.
