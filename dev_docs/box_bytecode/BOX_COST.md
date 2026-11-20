# Per-function cost

Self and total wall time of each function in a box that has been opted in, for the Profiling panel's
**Box functions** tab and the stall watchdog's report. The fourth pass on the shared rewrite
(`dev_docs/BOX_BYTECODE_PASSES.md`).

Source: `src/jvmMain/kotlin/afield/runtime/bytecode/BoxCost.kt` (`BoxCostPass`, `BoxCost`,
`installBoxCostHandlers`), the `box-cost` RPC in `rpc.kt` (`BoxCostRequest`, `BoxCostSnapshot`), the
tab in `src/commonMain/resources/profilingBoxCost.js`. Tests: jvmTest
`afield.runtime.bytecode.BoxCostPassTest`; smoke `runtime.box-cost.smoke`
(`BoxCostSmokeTest.kt`).

## Using it

Select one box and run **Measure Function Cost of Selected Box** from the palette, or open
**Show Profiling Timeline → Box functions** and press **Measure selected box**. Measuring starts at
the box's next run, because a module is rewritten (from its originals) only when it is evaluated.
The tab polls twice a second while it is shown: per box, its runs and their wall time; per function,
its calls, self time and total time, and its box line (a button that opens the editor there). A
function with a frame open now (a `while` in the box body, a function the Runloop is stuck in) is
marked running and its time so far is included. **Clear totals** starts every figure again from now.
Box code can also call `afield.runtime.bytecode.BoxCost.measure(id)` itself.

Measuring is per box and in memory: it is not saved with the document, and stopping it removes the
probes at the box's next run.

## The rewrite

`BoxCostPass` wants exactly the boxes in `BoxCost.measure`'s set. For each method it instruments it
allocates one site (detail: the function's display name; line: the method's first line inside the
snippet's own source, so a function's box line is its first statement's) and inserts:

- `LDC site; INVOKESTATIC BoxCost.enter(I)V` at `visitCode`;
- `LDC site; INVOKESTATIC BoxCost.exit(I)V` immediately before every `xRETURN`;
- `LDC site; INVOKESTATIC BoxCost.caught(I)V` immediately before the first instruction after each
  exception handler's label, which is after the handler's stack-map frame.

All three are a constant and a static `(I)V` call: the one rule, no `try/finally`, no frames. Chain
order puts the pass innermost, so its `enter` runs after the interrupt poll.

Not instrumented: `<init>`/`<clinit>`, bridges, `f$default` stubs (they call `f`, which is), `access$`
accessors, coroutine `create` factories, and leaf methods (`MethodFacts.isLeaf`: no calls, no
loops), whose time is their caller's self time. The measurement below is why leaves stay out: the
probe pair costs ~27 ns, and a leaf is usually a few instructions.

Names: `$$eval` is **box body**; a lambda body (`f$lambda$0`, or the `invoke` of a lambda class
`Snippet$f$1`) is **lambda in f**. Every compile of a box makes new sites, so the snapshot sums
sites by box, property, name and proven box line.

## The runtime

Only a measured box's own snippets are rewritten: a snippet none of whose lines is a line of the
box's code (the engine's prelude and warm-up snippets, evaluated for the box) is skipped. A
function's line is the first of its lines that is proven by text and is not blank or a comment (the
end of `$$eval` carries a `LINENUMBER` on whatever follows the last statement), else -1: a box body
whose statements are all `println`s, which the macro pass rewrites, has no line.

**A shadow stack per thread** (`BoxCost.CostStack`): open frames' site, start time and the summed
total of their instrumented callees. `exit` pops and charges the site its self time (total minus
callees) and, only when no other activation of the site is open, its total time, so a recursive
function's total is its outermost activation's. Everything a thread charges goes into arrays indexed
by site id that only that thread writes: no lock, no atomic. A reader on another thread (the snapshot,
the watchdog) reads them plainly; the outermost `exit` writes a volatile stamp the snapshot reads
first. The probe finds its stack through a plain field holding the last stack used, compared by
owner thread, before falling back to the `ThreadLocal`.

**Exceptional exits.** An exception skips the `exit` of each frame it leaves. They are reconciled,
charged up to now, at the first of:

1. the handler that catches it, if that is in measured code (`caught` pops the frames above the
   catching method's innermost open frame), so the time spent in the catch block is the catcher's;
2. the next `exit` of a frame below them (when uninstrumented code caught it);
3. the run boundary.

A recursive function that throws to an outer activation of itself is matched to the inner one,
because a frame carries nothing but its site; the time lands on the wrong activation of the right
function. A suspend function's state machine returns at each suspension and is re-entered on resume,
so each resumed segment is a call; only running time is counted.

**Run boundaries.** `BoxCost.begin()`/`end(mark, uid)` around `evaluator.eval` in both
`KotlinInterface2.evaluate` overloads and around every box coroutine resume
(`CoroutineProfiles.resume`), and `BoxCost.resetThread()` after each top-level Runloop tick. `end`
pops whatever was opened inside the boundary and left open, and adds the boundary's wall time to the
box's total when the box is measured: per-box totals with no rewrite at all, including library time.
The count is of boundaries, not runs: the engine's prelude and warm-up evaluations for the box, and
each coroutine resume, are one each (the smoke's two runs counted 7).
A boundary is nesting-safe (it unwinds to the depth it began at) and does nothing on a thread the
boundary did not begin on. All boundaries are one volatile read until some box has been measured
(`BoxCost.active`, which then stays set).

## The stall report

`Watchdog.report` (`BoxInterrupt.kt`) now builds the stall line, and asks
`BoxCost.stallReport(thread)` for one more: the hottest box function on the stalled thread, by self
time charged plus open frames' time so far, whether it is running now, and the open box frames
innermost first:

```
Runloop has not made a frame for 150 ms; box code from 'cost' (box-cost-test-…) is running
    hottest box function: 'cost' (box-cost-test-…) spin (box line 54), 156.4 ms self, running now; open: spin (box line 54) <- spinCaller (box line 59)
    at afield.runtime.bytecode.BoxCostShapes.spin(BoxCostPassTest.kt:55)
```

It is there only when the stalled box is measured. The example is `BoxCostPassTest`'s, whose box is
the test file itself. `BoxCostPassTest.aStallReportNamesTheRunningFunction`
pins it.

## Measured cost

Apple-silicon Mac, JDK in the Gradle toolchain, best of three (smoke) or five (jvmTest), three separate runs; `System.nanoTime()` from a plain `java` loop:

| case | off | measured |
|---|---|---|
| `System.nanoTime()` alone | 13.0 ns | |
| jvmTest `callHeavy(2e7)`: a loop calling a small non-leaf function | 23 ms | 565-566 ms, 27.1 ns per call |
| smoke D, real K2 box, tight loop of 2e8 with no calls (one frame a run) | 288-290 ms | 288-292 ms |
| smoke D, real K2 box, 2e7 calls of `fun step(i: Int) = Math.floorMod(i * 31, 1009)` | 32-36 ms | 600-607 ms, 28.3-28.6 ns per call |

Per call the cost is two `System.nanoTime()` reads (13 ns each here) and a few array stores; the
`ThreadLocal` was ~2.4 ns of it before the last-stack field. A loop with no calls pays nothing per
iteration. A function that does more than a few dozen nanoseconds of work per call is measured with
little distortion; a tiny function called millions of times a second is slowed by ~27 ns a call,
and its self time includes that. Not gated anywhere: it is opt-in per box.

## Not done

- Measuring is not persisted with the box, and there is no box property for it.
- The Box functions tab has no UI regression. It is exercised end to end (palette command, a run
  through **Run Box**, the tab) by the `profiling-timeline-box-functions` capture on
  `public/docs/profiling_timeline.md`, whose setup is
  `public/docs/assets/transcripts/box_cost_setup.yaml`. Opening it for that capture found the tab
  reading every reply as a failure (the default `Json` omits `ok: true`); `boxCostSnapshot` in
  `profileTimelineModel.js` now fills the defaults in, tested in `tools/test_profile_timeline.mjs`.
- The profile gutter (`LineHeatmap.setProfile`) does not paint function cost; the tab lists it.
- CPU time and allocation per function are not measured (wall time only).
- A box whose code runs on its own threads is reconciled only at that thread's next outer exit, since
  the boundaries cover evaluation, coroutine resumes and the Runloop tick.
