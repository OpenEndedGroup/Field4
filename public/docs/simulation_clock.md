# Simulation time

A behaviour can wait, turn, fire, wait again, and cancel as ordinary Kotlin coroutine
code. `SimulationClock` lets those delays follow time you advance explicitly. It creates
no thread and no new kind of box launcher.

Open **simulation_clock** from the welcome screen's starting points, or
[browse the document](/offline.html?file=/field4/assets/agent_transcripts/simulation_clock/metadata.edn).
Select **1. Simulation — launch / stop** and use **Launch Box** (Alt–Up) in the editor.
**Stop Box** (Alt–Down) stops it and its two children; launching again starts fresh.
Run **2. Record and explore** to inspect the same work in [Profiling Timeline](profiling_timeline.md).

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#profile-timeline" screenshot="assets/simulation_clock/frames.png" transcript="../../dev_docs/agents/transcripts/simulation_clock.yaml" padding="8" wait="0" timeout="180" embed=true hidden=true name="simulation-frame-budgets" alt="Field's existing profiling panel displaying complete simulation frame intervals, allocation per frame, budget misses, and selectable slow intervals from a real capture."}
ui.eval("window.showProfilingTimeline(); document.getElementById('profile-tab-frames').click(); return 'requested'")
var ready = false
repeat(60) {
    if (!ready) {
        ui.pause(150)
        ready = ui.eval("return document.getElementById('profile-status')?.textContent.startsWith('Latched') && !!document.querySelector('.profile-frame-card') && !document.getElementById('profile-save').disabled")?.toString() == "true"
    }
}
check(ready) { "Frame capture did not finish" }
ui.eval("const p=document.getElementById('profile-timeline'); p.style.width='1180px'; p.style.right='auto'; p.style.height='520px'; return 'framed'")
ui.pause(250)
```

## One owner, two clocks

```diffstatic {data-lang=kotlin}
:::static
import kotlinx.coroutines.*

var phase = 0.0
frames {
    withSimulationClock {
        launch(CoroutineName("Orbit")) {
            try {
                while (isActive) {
                    phase += 0.04
                    sleep(stepSeconds)
                }
            } finally {
                println("Orbit stopped")
            }
        }
        launch(CoroutineName("Pulse")) {
            while (isActive) {
                println("pulse")
                delay(500) // simulation milliseconds
            }
        }
        runCurrent() // start children and establish their first waits
        var last = AnimationClock.nowSeconds()
        while (frame()) {
            val now = AnimationClock.nowSeconds()
            advance((now - last).coerceAtLeast(0.0))
            last = now
        }
    }
}
```

The outer `frame()` yields through Field's existing Runloop. Children run only when you
pump the simulation. `delay` and `withTimeout` inherit simulation time; `sleep` accepts
seconds. Change the elapsed time passed to `advance` for slow motion, pause by not
advancing, or pass a fixed duration for repeatable steps. `time` and `tick` describe the
simulation, not the wall clock. Choose another dispatcher explicitly only when you want
to leave that simulation and its timing semantics.

The default step is 1/60 second. Positive waits round up to ticks, and `sleep(0.0)` waits
one tick. Ready continuations run in queue order; timers due on the same tick preserve
registration order. `advance(dt) { step -> … }` runs the callback after each tick's ready
coroutines, which is a useful place for integration or collisions. Seed any random
choices yourself if repeatability matters.

When children need a shared sensor snapshot before they run, the overload
`advance(dt, beforeStep = { index, count -> … }, onStep = { seconds -> … })`
prepares each step before advancing its clock and resuming its children. The zero-based
index and total count let an application distribute observed input motion over catch-up
steps; integration still follows the children. Step callbacks cannot advance or close
the same clock recursively.

A clock accepts at most eight steps per `advance` and 1,024 resumes per tick by default.
`SimulationAdvance` reports performed steps, resumed and deferred continuations, and
elapsed seconds discarded by the catch-up limit. These bounds prevent an accumulating
catch-up queue; they cannot interrupt an infinite loop inside a coroutine. Keep individual
resumes short. `runCurrent()` pumps ready work without advancing time.

## Stop is still Stop

`withSimulationClock` inherits the current box's job, source identity, and document.
It closes in `finally`: cancel the child tree, drain cancellation continuations, and
remove pending timers. Put resource cleanup in ordinary non-suspending `finally` blocks.
Cleanup cannot wait for future simulation time after the clock has stopped. A failing
child cancels the enclosing box through normal structured concurrency.

Use the scope inside `frames`, as above. A raw `SimulationClock(context)` is available
for backend code, but its caller must close it on the thread that advances it. Returning
a bare clock from a box is not an execution convention. Avoid `GlobalScope` for owned work.

## Record, change, compare

The starting point marks complete frame intervals and two scopes, **Simulation** and
**Vectors**. Its named **Orbit** and **Pulse** child lanes appear inside the update work.
Change `reuse` to `false` in the first box, relaunch, and record again to compare allocation
per frame. The measurements come from the existing recorder; see
[Holding a Frame Budget](frame_budget.md) for their scope and limits.

For a repeatable demonstration, run the workspace macro
`dev_docs/agents/transcripts/simulation_clock.yaml` in an empty document. It creates both
boxes, starts them, records, stops, and verifies child cleanup. The resulting capture
remains available after the work stops. Replay restores the example code; use the box
controls while keeping your own edits.
