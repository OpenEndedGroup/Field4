# Holding a frame budget

Sixty frames a second is 16.67<!--derive: 1000/60--> milliseconds a frame, and a real-time piece either fits in
that or it does not. When it stops fitting, Field will show you three different sets of
numbers, on three different clocks, over three different populations. They do not agree,
they are not supposed to agree, and the most expensive mistake available here is to read
one of them as if it were the others.

This page is the measurement model. [Profiling Timeline](profiling_timeline.md) tells you
what every control in the panel does; this one tells you what the numbers under those
controls *mean*, and — the part that matters when you are chasing a stutter — what each
instrument structurally **cannot see**. The second half is a route from "it stutters" to a
named line of your own box source.

## The three instruments

| | `CPUSpan` | one coroutine resume | a JFR sample |
|---|---|---|---|
| what it measures | wall time of a named renderer span | wall time, thread CPU time and thread allocated bytes for one scheduled continuation | that a thread was here, or allocated here |
| clock | `System.nanoTime()` — wall only | wall, plus `ThreadMXBean` CPU and allocation counters | the JFR event clock, realigned to the capture |
| population | the last 500 samples per label, whole JVM | every Runloop or simulation-clock resume of every box in **one** document | every thread in the JVM, sampled |
| opt-in | **no** — always on | yes, 1–30 seconds | yes, with the capture |
| where you read it | the `perf` log line, the Inspector | the **Coroutines & scopes** tab | the **Hot code** tab |
| exact or estimated | exact per sample, but the window forgets | exact | estimated |

They are three answers to three different questions. `CPUSpan` answers *is the renderer
keeping up*. A resume answers *what did this box's code cost, exactly, this time*. A sample
answers *where is the time going, roughly, across everything*. Reach for the one whose
question you are actually asking.

## `CPUSpan`: always on, and eight seconds long

`CPUSpan` (`Renderer.kt:54#object CPUSpan {`) is the one you did not turn on. Every named
renderer span goes through `Renderer.kt:80#inline fun time(label: String, block: () -> Unit) {`,
which brackets the block with `nanoTime` and pushes the difference into a per-label deque.
Twenty-three label literals exist in the tree today — six in the renderer proper
(`acquire`, `record_cs`, `record_gfx`, `submit_cs`, `submit_gfx`, `present`, each suffixed
with a window or context index, as at `Renderer.kt:1248#CPUSpan.time("present[$ctxIndex]") {`),
fourteen under OpenXR and three in the virtual-texture system.

Two things about it are not obvious from the name.

**It is wall time, not CPU time.** `time` reads `System.nanoTime()` and nothing else. A
`present` span that spends four milliseconds waiting on a fence reports four milliseconds,
and that is the right answer to *did the frame land on time* while being the wrong answer to
*how much CPU did we burn*. Nothing in `CPUSpan` reads a CPU counter.

**Its window is 500 samples per label** (`Renderer.kt:55#private const val WINDOW = 500`),
enforced by dropping the oldest on overflow
(`Renderer.kt:61#if (samples.size > WINDOW) samples.removeFirst()`). For a label that fires
once a frame, 500 samples is the last **eight and a third seconds** at 60 Hz. Every
statistic it reports — `avg`, `min`, `max`, `p1` through `p99` — is a claim about that
window and about nothing before it.

That window has a consequence, and it is the easiest way to misread this instrument. The
percentiles index a sorted copy of the window
(`Renderer.kt:111#val idx = ((sorted.size - 1) * frac).toInt().coerceIn(0, sorted.size - 1)`),
so over a full window `p99` is element 494 of 500 — the **sixth worst** sample. One bad
frame does not move it:

<!--
FIELD_TEST
id: docs.frame_budget.cpuspan_window
runner: backdoor-box
tags: [docs, profiling, tohtml]
timeoutSec: 180
waitSec: 2.0
expect:
  outputContains:
    - "sixth worst sample"
    - "back among the short spans"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/frame_budget/cpuspan-window.png" capture="box" w="900" h="300" wait="2.0" timeout="180" embed=true name="frame-budget-cpuspan-window" alt="A Field box output panel labelled main, holding a five-column table titled 'one deliberate hitch, seen through a 500-sample window', headed when, n, p50 ms, p99 ms and max ms. Two rows. 'Just after the hitch (600 spans in)' reports n of 500 rather than 600, a p50 and a p99 both around a third of a millisecond, and a max of about fifty milliseconds highlighted as a warning. '500 spans later' reports n of 500 again, the same third-of-a-millisecond p50 and p99, and a max that has fallen back under one millisecond, shown as ok. A footnote states how little p99 moved against how far max fell, and that max is back among the short spans."}
:::static
import afield.four.CPUSpan

// Drive the real CPUSpan.time 600 times under a label of my own: 599 short spans and one
// deliberate long one. Nothing here simulates the window — WINDOW and the percentile
// function are the renderer's own.
val label = "docs_window_probe"
repeat(599) { CPUSpan.time(label) { Thread.sleep(0, 200_000) } }
CPUSpan.time(label) { Thread.sleep(40) }   // a sleep is a lower bound; the span reports what elapsed
val withHitch = CPUSpan.snapshot().first { it.label == label }

// 500 more spans is exactly one window. At 60Hz that is eight and a third seconds.
repeat(500) { CPUSpan.time(label) { Thread.sleep(0, 200_000) } }
val after = CPUSpan.snapshot().first { it.label == label }

val rows = listOf("just after the hitch (600 spans in)" to withHitch, "500 spans later" to after)
display("main", Table(
    listOf("when", "n", "p50 ms", "p99 ms", "max ms"),
    title = "one deliberate hitch, seen through a 500-sample window",
    note = "across those 500 spans p99 moved by " +
        "%.2f".format(after.p99Ms - withHitch.p99Ms) + "ms while max fell by " +
        "%.2f".format(withHitch.maxMs - after.maxMs) + "ms. Over a full window p99 is the sixth " +
        "worst sample and there was only one bad one, so it never saw the hitch at all; max is now " +
        (if (after.maxMs > 10) "somehow still holding it" else "back among the short spans") +
        ". A single stutter is visible in max, for eight seconds, and nowhere else."
)
    .rows(rows.map { (when_, s) -> listOf(when_, s.n,
        "%.2f".format(s.p50Ms), "%.2f".format(s.p99Ms), "%.2f".format(s.maxMs)) })
    .role("when", CellRole.ACCENT)
    .roleBy("max ms") { if ((it as String).toDouble() > 10) CellRole.WARN else CellRole.OK }
    .expect("n", because = "the window is 500, so 600 and 1100 spans both report 500") { it == 500 })
```

Six hundred spans in, `n` is 500 rather than 600 and the hitch is sitting in `max` — at
48.91 ms in the run above rather than the 40 asked for, because `Thread.sleep` is a lower
bound and the span reports the time that actually passed. Meanwhile `p99` reads
0.33 ms, because one bad sample out of 500 cannot reach element 494. Five hundred spans
later `max` has fallen to 0.42 ms — and `p99` is still 0.33, having moved by **0.00 ms**
across the entire episode. **A hitch that happens once a minute is invisible to every
`CPUSpan` percentile, and invisible to `max` for all but eight seconds of that minute.** If
that is the shape of your problem, this is the wrong instrument and no amount of staring at
the `perf` line will help.

What else it cannot see: your box code, at all — the labels are renderer internals, so a
slow `loop { }` never appears here. GPU execution, at all — `submit_gfx` times the
*submission*, not the work. And *when* anything happened: the deque holds durations, not
timestamps, so it can tell you how bad the tail is and never when the tail happened.

The rolling snapshot reaches the terminal every five seconds
(`Renderer.kt:51#private const val PERF_LOG_INTERVAL_NS = 5_000_000_000L`, driven from
`Renderer.kt:1281#CPUSpan.snapshotAndMaybePrint(everyNFrames = 120, frame = frameCounter)`)
on the `perf` channel, which the pinned console shows by default — see
[Where Field's Output Goes](logging.md). The Inspector's `Renderer` card reads the same
snapshot live, refreshed at most once a second
(`rendererBrowsing.kt:824#private fun maybePushCpuChanged(intervalMs: Long = 1000) {`,
registered at `rendererBrowsing.kt:32#CPUSpan.addListener { maybePushCpuChanged() }`).

## One coroutine resume: exact, and blind between them

This is the instrument for *your* code, and it is the only exact one. A capture wraps the
Runloop's dispatch site (`Phased.kt:360#CoroutineProfiles.resume(n.first, n.second)`)
and the simulation clock uses the same instrumentation. For each resume it owns, it reads a thread CPU counter and a thread allocated-bytes
counter either side of the call. Its unit is one scheduled continuation, which the type
says outright:

> One scheduled continuation, ending when it returns/yields/suspends; gaps are not CPU work.
> — `profiling.kt:27#/** One scheduled continuation, ending when it returns/yields/suspends; gaps are not CPU work. */`

Read that sentence twice, because almost everything people get wrong here is in it.

**A resume is not a frame.** It ends at the suspension point, not at the end of your
iteration, so `loop { work; delay(16) }` produces *two* resumes per turn round the loop:
one that does the work and suspends, and one that wakes up afterwards and immediately
loops. The second one costs nothing. That makes a median-over-resumes a median over a
population that is half empty, and it is why the panel warns that its percentiles are per
resume rather than per frame. In the three-second recording measured for this page (the
replay command is at the bottom), the box that allocates an array of 8,192 vectors every
turn showed **314 resumes: 157 that allocated a mean of 229,712 bytes and 157 that
allocated a mean of 208**. Its median resume allocated 208 bytes. Its mean allocated
114,966. Neither number is wrong; only one of them is about your work.

**A gap is not CPU time, and not proof of idleness either.** Empty space between spans
means that coroutine was not being resumed by the Runloop. It might be suspended in
`delay`, it might be blocked in a syscall, it might have moved to another dispatcher.
The instrument cannot distinguish those, because it only ever ran at the dispatch site.

**A thread blocked in a syscall looks cheap, not slow.** The three numbers are independent:
`wallMs` encloses everything that happened during the resume, `cpuMs` counts only scheduled
CPU, and the two are bracketed so that the wall interval contains the counter reads and
cannot come out narrower than them
(`CoroutineProfiling.kt:50#// Wall encloses the CPU/heap counter reads. Self totals subtract nested measured children.`).
So a resume that blocks for eight milliseconds on a file read reports `wallMs` ≈ 8 and
`cpuMs` ≈ 0. That gap between the two numbers *is* the diagnosis, and it is the reason the
panel draws CPU time as a strip inside the full-width wall bar rather than as a second bar.
In the measured recording the three workload lanes ran at CPU/wall ratios of 0.952, 0.935
and 0.992 — genuinely CPU-bound work, and the absence of the signature.

**Allocated bytes is a delta over the whole resume**
(`profiling.kt:36#val allocatedBytes: Long,`), so no brush finer than a resume can divide
it, and it is bytes allocated rather than bytes retained. A box can allocate 11 MiB a
second and hold nothing at all, which is exactly what the demo's vector box does.

What it structurally cannot see:

- **Anything outside the instrumented dispatchers and scopes.** Runloop and
  `SimulationClock` continuations carry a `ProfileOrigin`. The origin is
  attached at launch (`Phased.kt:238#val launchJob = inMain(Box.boxContextElement(box) + ProfileOrigin(box, strand))`)
  and inherited by child coroutines. Work on a renderer thread, a background compile, a
  `DefaultDispatcher` worker — none of it reaches the counters.
- **Any other document.** A resume whose origin belongs elsewhere is run untouched
  (`CoroutineProfiling.kt:43#if (capture == null || origin == null || origin.document != capture.document) { task.run(); return }`),
  which is a privacy property as much as a scoping one, and only one capture may be live in
  the process at a time (`CoroutineProfiling.kt:70#check(active == null || active?.document == document)`).
- **Anything past its bounds.** Captures run 1 to 30 seconds
  (`CoroutineProfiling.kt:69#require(request.seconds.isFinite() && request.seconds in 1.0..30.0)`)
  and keep 65,536 resumes (`CoroutineProfiling.kt:104#private val capacity: Int = 65_536,`)
  in a ring that overwrites (`CoroutineProfiling.kt:158#val at = (sequence % capacity).toInt()`)
  across at most 1,024 lanes (`CoroutineProfiling.kt:105#private val maximumLanes: Int = 1024,`),
  refusing the 1,025th (`CoroutineProfiling.kt:146#if (lanes.size >= maximumLanes) { rejected++; return -1 }`).
  Both losses are reported rather than hidden, in one counter
  (`profiling.kt:63#val droppedSpans: Long = 0,`) — check it before trusting a total.

Arming a capture is not free: eleven primitive arrays sized
to the span ring (`CoroutineProfiling.kt:119#private val frames = LongArray(capacity)` and its
ten siblings) occupy 5 MiB, plus about 416 KiB for the independent frame ring,
allocated once at the start. Named scope tokens and snapshot exports also allocate during
a capture; compare like-for-like recordings. With no capture
active the dispatch path is one volatile read (`CoroutineProfiling.kt:40#val capture = active`)
and an origin lookup; no counters are read and no measurement token is allocated.
Note also that the latch drops its references to your live
objects (`CoroutineProfiling.kt:176#origins.clear() // do not retain live object graphs in a saved capture`),
and only four documents' captures are kept
(`CoroutineProfiling.kt:77#while (captures.size > 4) captures.remove(captures.keys.first())`).

## Explicit application frames and nested work

Add `val budget = frameProfile("Game", targetFps = 60.0, allocationKiB = 128.0)` inside
an ordinary `frames` body, call `budget.beginFrame()` once per iteration, and close it in
`finally`. These boundaries feed **Frame budgets** in the existing panel; they do not arm
another capture. See the complete [simulation example](simulation_clock.md).

A completed interval runs from one boundary to the next. Its wall time includes suspension,
waits, and scheduling gaps. Its CPU and allocated-byte deltas cover the same thread over
that interval, including other work sharing it. Keep the instrument on one thread. This
makes allocations between your boxes visible, but does not assign them to one stage.
An initial boundary has no preceding frame; a final partial interval is discarded.

The panel reports p50, p95, p99 **and maximum**, alongside counts exceeding your interval
budget and allocation guide. Selecting a worst interval keeps the same time brush when
you move to scopes or stacks. A single hitch therefore remains directly selectable even
when it cannot move p99. The independent frame ring retains 8,192 complete intervals and
reports overwritten history.

`budget.measure("Collision") { … }` and `profileScope("name") { … }` bracket synchronous
stages. Inclusive costs contain nested children; self costs subtract them. All-lane sums
use self costs, while selecting one lane shows its inclusive cost. Adding inclusive parent
and child values would double-count work. Do not suspend or change threads inside a scope.
Frame totals and scope totals are different populations and must not be added together.

GC collection counts and collector time cover the whole JVM over the capture. With stacks
enabled, JFR `jdk.GCPhasePause` adds up to 2,048 stop-the-world event intervals as amber ticks.
Collector time can include work outside those pauses; neither measure identifies the box
whose allocation caused the collection. An absence of recorded pauses does not prove
that heap allocation is free. Compare bytes per frame and allocation stacks alongside them.

## A JFR sample: 10 ms periods, 150 allocations a second

The third instrument is optional JFR recording
(`JvmProfileCapture.kt:16#/** Optional, bounded JFR sampling. Parsing and source lookup never run on the game thread. */`),
and its type carries the warning the other two do not need:

> Samples are statistical evidence, independent of the exact continuation counters.
> — `profiling.kt:40#/** Samples are statistical evidence, independent of the exact continuation counters. */`

Two stack event kinds are enabled, alongside GC pause events. CPU stacks come from `jdk.ExecutionSample` at a **10
millisecond** period
(`JvmProfileCapture.kt:22#enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace()`);
allocation stacks come from `jdk.ObjectAllocationSample` throttled to **150 a second**
(`JvmProfileCapture.kt:23#enable("jdk.ObjectAllocationSample").with("throttle", "150/s").withStackTrace()`).

A 10 millisecond period means a sampler that fires 100<!--derive: 1000/10--> times a second per running thread.
At 60 fps a frame is 16.7 milliseconds long, so about **1.7**<!--derive: 16.7/10--> firings land in each frame —
but a firing only records your work if your work is what is running when it arrives, so **a
2 millisecond hitch has about a one in five chance of appearing in any sample at all**, and
a single hitch that shows up in no sample is the ordinary case rather than a malfunction. More usefully, the sampler sees your
work in proportion to how much of *wall* time your work occupies, which sets what a
recording can give you. The three-second
recording measured below had 63.9 ms of resume time in 3,002.6 ms of wall clock — 2.13<!--derive: 63.9/3002.6*100-->%
busy. Three seconds at a 10 ms period is 300<!--derive: 3000/10--> possible firings, so about six CPU samples
should have landed in the workload, and **nine did** — out of 207 sampled events, the other
198 being allocation samples.

Nine CPU samples in three seconds, with three boxes running. If you want CPU stacks
worth grouping, the work has to be *happening* while you record: a piece genuinely spending
half its frame budget in box code will yield a couple of hundred samples in three seconds,
and a piece that is mostly waiting will yield a handful however long you record it.

So when the **Hot code** tab shows a percentage, it is a share of sampled events and not a
share of milliseconds. Two further rules follow from the same fact: an inclusive row counts a sample once per
distinct stack row it contains, so inclusive rows overlap and their weights must never be
added (`profileTimelineModel.js:65#// Inclusive rows overlap: their weights must never be added together.`);
and a gutter stripe is labelled in the panel's own hover text as
`lineHeatmap.js:345#"Sampled evidence, not an exact line timer."`.

### Where an allocation rate's estimate comes from

Allocation sampling has a second layer of estimation on top of the first. The events are
throttled, each carries the
JVM's own estimate of bytes allocated on that thread since the previous event, and the
first event on each thread is **discarded** because its weight can reach back before the
recording started (`JvmProfileCapture.kt:125#// recording. Establish its baseline instead of charging those bytes to the brush.`).

At 150 events a second, ten seconds of a 60 fps piece gives at most 1,500<!--derive: 150*10--> events spread
across the whole JVM — about two and a half per frame. A per-frame allocation budget read
off that is an estimate from a couple of samples. So if you are setting yourself a budget
in kilobytes a frame, it is a working guide rather than a number the JVM owes you, and the
way to *hold* yourself to it is the coroutine counters, which are exact, with the sampled
stacks used only to find the line. Field's own stack capture is bounded too: 64 MiB
(`JvmProfileCapture.kt:20#maxSize = 64L * 1024 * 1024`), a 35-second hard ceiling
(`JvmProfileCapture.kt:21#duration = Duration.ofSeconds(35) // safety ceiling even if the Runloop never returns`),
12,000 retained events (`JvmProfileCapture.kt:131#if (samples.size >= 12000) { dropped++; continue }`),
64 frames per stack (`JvmProfileCapture.kt:132#val stack = event.stackTrace?.frames.orEmpty().take(64).mapNotNull { frame ->`)
and 256 source texts (`JvmProfileCapture.kt:109#if (sources.size >= 256 || sourceBytes + size > 4_000_000) return@getOrPut -1`).

## Where the three disagree

They will. Some worked reasons, so that a disagreement reads as information rather than as
a bug:

- **`CPUSpan` says `present` costs 9 ms; no coroutine lane shows anything.** Correct and
  consistent. `CPUSpan` is wall time on a renderer thread and the coroutine counters never
  see renderer threads. The cost is not in your boxes.
- **A lane's `cpuMs` is far below its `wallMs`.** Correct and important. The resume was not
  on CPU for most of its life: blocked, descheduled, or waiting out a GC pause. The span
  cannot tell you which, and nothing on this page can.
- **Hot code says a method is 40% and the lane totals say it is 2 ms.** Both true. 40% is a
  share of *samples*, over the whole JVM in that brush; 2 ms is exact, over one document's
  resumes. A background thread can dominate the sample population while costing your frame
  nothing.
- **Summed lane wall time is nowhere near the elapsed capture.** Expected. The measured
  recording summed 63.9 ms of resumes over 3,002.6 ms — 2.13%. The rest of the wall clock
  is suspension, Runloop tasks nobody owns, and everything on other threads. The sum can
  also *exceed* elapsed time, because lanes overlap when resumes run concurrently.
- **The same box reads differently between two runs.** Also expected, and the panel says
  so. Use identical capture settings, let the scene warm up first, and compare shapes
  rather than digits.

## From "it stutters" to a line of your code

The route, in order, with the branch points named.

**1. Decide whose problem it is first.** Look at the `perf` line or the Inspector's
renderer card before you record anything. If `CPUSpan`'s per-frame labels are quiet and
the piece still stutters, the cost is in box code or outside every instrument here. If they
are loud, no coroutine capture will help you.

**2. Arm a bounded capture, with stacks on.** From the palette, **Show Profiling Timeline**
→ a length → **Record**; from a box, one call, which is what the demo's fourth box does:

```diffstatic {data-lang=kotlin}
CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("start", 3.0, stacks = true))
```

Three seconds is usually right. Thirty is the ceiling, and is for a fault you cannot
provoke on demand — remember that the ring holds 65,536 resumes, and that a 60 Hz piece
with a dozen live lanes can reach that inside twenty seconds.

**3. Read the lanes for shape, not for digits.** One box can own several lanes, because a
lane is a coroutine. What you are looking for is one of three shapes, and they want
different fixes:

| Shape in the lane | What it is | Where to go next |
|---|---|---|
| `wallMs` high, `cpuMs` ≈ `wallMs` | real CPU work in your code | **Hot code**, CPU samples |
| `wallMs` high, `cpuMs` ≈ 0 | blocked, descheduled, or a GC pause | not answerable here; move the blocking work off the Runloop |
| `allocatedBytes` high, `cpuMs` modest | per-frame construction | **Hot code**, allocation samples |
| no span at all, just a gap | suspended, elsewhere, or not Runloop-dispatched | check the box is launched and the work is not on its own thread |

Switch the overview to **Allocated bytes** for the third of those. Remember that the
brush includes whole resumes whose start falls inside it, and that bytes cannot be
subdivided within a resume.

**4. Follow a sample to a named line.** Open **Hot code**, brush the interval you care
about, choose **Lines**, and switch between CPU and allocation samples. Then select the row
and read its source. This is the step that actually works, and here is the real output of
it on the demo, from the recording measured for this page. Grouped by innermost frame, the
allocation samples ranked:

| sampled weight | events | innermost frame |
|---|---|---|
| 28.87 MiB | 136 | `afield.stdlib.LinalgKt.vec` |
| 1.98 MiB | 9 | the vector box's own line 12 |
| 1.92 MiB | 9 | `kotlinx.coroutines.EventLoopImplBase.scheduleResumeAfterDelay` |

and grouped *inclusively* by box line, the top row of the vector box was **line 12,
30.85 MiB across 146 samples**, whose text is:

```diffstatic {data-lang=kotlin}
positions = Array(count) { vec(it.toFloat(), phase, 0f) }
```

That is the whole workflow in one row: a per-frame allocation, named by line, in a box.
Notice that the two groupings are consistent rather than contradictory — `vec` is the
callee and line 12 is the caller, which is exactly the difference between **Top frame only**
and **Including callees**, and exactly why the two columns must not be added.

In the same capture the *periodic burst* box — the one costing
2.2 ms every 240 ms, which is by far the largest single cost in the recording — was
represented by **one** sample, on its line 7, with no measurable allocation. Sampling found
the allocation problem immediately and very nearly missed the CPU problem entirely. The
lane's exact `wallMs` found the CPU problem in the first second.

**5. Jump to the box with the gutter heatmap on.** **Open box with gutter heatmap** puts
you in Monaco with the sampled lines striped in the gutter, cyan for CPU and amber for
estimated allocation. The mechanism is worth a paragraph, because it explains the one way
this step disappoints people.

A capture's source records carry more than a path. `ProfileStackSource`
(`profiling.kt:50#data class ProfileStackSource(val id: Int, val path: String, val code: String,`)
also carries a `boxId`, a `property` and a copy of the box's source text as it was when the
capture was armed — enough to tie a sampled frame back to a box, and only strings, so a
latched capture retains no live box or classloader
(`JvmProfileCapture.kt:45#// Copy strings/identifiers only: no live box, classloader or coroutine retained by the latch.`).
The heatmap then requires all three to match what the editor currently has open
(`profileTimelineModel.js:55#if (source.boxId !== context.uid || source.property !== context.property || source.boxCode !== context.text) continue;`):
the same box, the same property, and **character-identical text**. Two boxes holding
identical code do not borrow each other's marks, and — the part that surprises people —
editing a single character clears the stripes, because the capture is now about code that
no longer exists. Undo the edit and they come back. That is the right trade, and it is
better than a stale stripe pointing at a line that has moved.

For frames outside any box, the resolver goes to the checkout instead, and checks the
package and `SourceFile` attribute rather than guessing from a simple class name
(`JvmProfileCapture.kt:154#/** Reuse the source index; verify package and SourceFile rather than guessing by simple class name. */`).
In the measured recording, 2 of the 13 resolved source texts were box code and the other 11
were codebase files — which is a fair picture of where a sampled stack actually spends its
frames.

## What none of this sees

If you have exhausted all three instruments and are still dropping frames, the cost is
somewhere in here.

- **GPU execution.** None of the three. `submit_gfx` is the cost of submitting. The
  renderer's own Vulkan timestamp profiler is a separate thing and is reachable through the
  Inspector on a window context.
- **GC pauses, as events.** They show up as wall time nobody can account for — a resume
  with `wallMs` high and `cpuMs` near zero — and the timeline does not yet align a pause
  event against its own axis.
- **Retained memory.** `allocatedBytes` is allocation, not occupancy; a box can churn
  tens of MiB a second and retain nothing.
- **Native and GPU memory.** Neither counter covers them.
- **Scanout.** `Renderer.frameCounter` counts renderer iterations, not frames the display
  actually presented. Dividing it by elapsed seconds gives you an iteration rate, which is
  a useful number and is not a measured frame rate.

??? note "Seeing the renderer's own spans"
    `present` and `record_gfx` are the renderer's labels, and you will see them only by
    running `./gradlew runJvm` with a window open and reading the `perf` line. A docs
    capture runs a box in a headless Field with no `Renderer`, where `CPUSpan.snapshot()`
    returns zero labels, so the figure above drives `CPUSpan.time` under a label of its own
    instead. The window arithmetic, the percentile indexing and the 500-sample truncation
    are the renderer's own code either way, and are exercised for real.

## Try it, then change one number

Every measurement on this page came from one replay of the `profiling_timeline` starting
point against a live Field, which needs no GPU and no private sources; the recording is
written up in `dev_docs/profiling_stack.md`. With Field already
running, and a workspace and document name of your own:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --attach-existing --port 9092 \
  --workspace /path/to/workspace --document frame_budget_demo \
  --command 'macro write profiling_timeline --file dev_docs/agents/transcripts/profiling_timeline.yaml' \
  --command 'macro run profiling_timeline'
```

Then change **one** thing, in box **2. Periodic bursts**: shorten `quietMillis` from 240 to
16, relaunch it, and record again. The lane's `wallMs` per resume should barely move — each
burst still costs about the same 2.2 ms — while the number of bursts in three seconds goes
from a dozen to something near two hundred. That is the distinction the whole page is
about: a per-resume percentile tells you how expensive one turn is, and only the count
of turns tells you whether it fits in a frame. The same box at the same `p99` can be free
or ruinous depending on how often it runs, and no percentile on any of these three
instruments will tell you which.

For the panel's controls, and for the brush, the lane list and the save format, see
[Profiling Timeline](profiling_timeline.md).
