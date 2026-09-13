# The profiling stack

Three instruments, one wire type, one dispatch hook. This is the internals companion to
`public/docs/frame_budget.md`, which is the measurement model as a reader of the published
docs needs it. What is here is the part that is only interesting if you are changing this
code: where the three instruments meet, which of their properties are load-bearing for the
published page, and a record of what one real capture actually produced.

## Why this page exists

The published gap was measured rather than assumed, and the numbers are worth keeping
because they are the argument for the page that closed it. On 2026-09-12, over the 445
tracked `public/docs/*.md` and 174 tracked `dev_docs/**.md`, excluding `assets/`,
`transcripts/` and `reference/`:

| predicate | selects |
|---|---|
| `grep -lEi renderer` — control | 53 pages |
| `grep -lEi profiling` | 3 pages |
| `grep -lE 'CPUSpan\|CoroutineProfile\|profilingTimeline\|JvmProfileCapture\|ProfileStackSample'` | **0** pages |
| `grep -cE '\.(kt\|js\|css\|py):[0-9]+'` on `public/docs/profiling_timeline.md` | **0** citations |
| a `dev_docs` page about this stack | none |

The control matters. A first attempt at that census returned 0 for the word `renderer`
too, which is a broken instrument rather than an empty corpus, and the 53 is what makes
the 0 believable. The three pages that say "profiling" are `agent_transcripts.md`,
`building_and_testing.md` and `profiling_timeline.md`, and the first two mean something
else by it — `building_and_testing.md` meant DevTools attached to a docs-capture browser,
which is now said out loud on that page so the next person greppping does not stop there.

## The shape

```
Runloop  ──► CoroutineProfiles.resume         Phased.kt:360
                │  (one volatile read when off)
                ├─► ThreadMXBean CPU + allocated-bytes deltas   ─┐
                └─► JvmProfileCapture (optional)                 ├─► CoroutineProfileSnapshot
                       jdk.ExecutionSample @10ms                 │      (profiling.kt)
                       jdk.ObjectAllocationSample @150/s        ─┘
                                                                      │
Renderer ──► CPUSpan.time (always on, unrelated)  Renderer.kt:54      ▼
                └─► perf log line, Inspector card              profilingTimeline.js
                                                               profileTimelineModel.js
                                                               lineHeatmap.js (Monaco gutter)
```

`CPUSpan` is worth calling out as *not* part of the capture pipeline at all. It shares no
type, no clock and no lifecycle with the other two; it is a separate, always-on facility
that happens to answer an adjacent question. Nothing joins them, and the published page
says so rather than implying a unified profiler that does not exist.

### The two that can be confused

`CoroutineProfileSpan` and `CPUSpan.Snapshot` are both "a span with percentiles" and they
are different in every way that matters:

| | `CoroutineProfileSpan` | `CPUSpan.Stat` |
|---|---|---|
| unit | one scheduled continuation | one labelled block |
| clock | wall + thread CPU + thread allocation | wall only |
| retention | ring of 65,536, per capture | deque of 500, per label, forever |
| timestamps | yes, `startMs` relative to the capture | **none** — durations only |
| scope | one document | the process |
| lifecycle | armed and latched | there is none |
| percentiles computed | in the browser | in `buildSnapshot` |

The "no timestamps" row is the one that catches people. A `Stat` is a deque of durations
and nothing else (`Renderer.kt:58#data class Stat(val samples: ArrayDeque<Long> = ArrayDeque()) {`),
so `CPUSpan` can report that the tail is bad and can never report when the tail happened.
That is why it cannot be brushed, plotted against the coroutine timeline, or correlated
with anything — and why joining the two instruments would mean changing `Stat`, not
changing a viewer.

### The clock join, which is the subtle part

The capture measures in `System.nanoTime()`; JFR stamps events with a wall-clock `Instant`.
Neither can be converted to the other in general, so `align`
(`JvmProfileCapture.kt:38#fun align(nanos: Long) {`) reads both clocks once, at the moment
the capture is armed, and reconstructs the wall instant that corresponds to the capture's
nano origin: `JvmProfileCapture.kt:41#start = clock.minusNanos(mono - nanos)`. Every sample
timestamp is then a difference against that reconstructed origin.

This is the right shape and it is worth knowing its cost. The two clocks are read on
consecutive lines rather than atomically, so the join carries whatever gap separates them —
small, unbounded in principle, and the reason a sample's time should never be compared to a
resume boundary at sub-millisecond resolution. `profileHotspots` does exactly that
comparison when a coroutine scope is selected, which is a reasonable thing to do at the
10 ms sample period and would not be at a 100 µs one. If the sampling period is ever
shortened, that is the assumption to revisit first.

## Properties the published page depends on

If you change one of these, `public/docs/frame_budget.md` becomes wrong while every
citation on it still resolves. That is precisely why it was brought under
`CitationContractTest.GUARDED_PAGES` on its first day rather than later — see
`dev_docs/citation_contracts.md` and `dev_docs/guard_population_hygiene.md` for the general
shape of that argument.

1. **`WINDOW = 500`** (`Renderer.kt:55#private const val WINDOW = 500`). The page derives
   two claims from it: that a once-a-frame label's history is eight and a third seconds at
   60 Hz, and that `p99` over a full window is the sixth worst sample — from
   `Renderer.kt:111#val idx = ((sorted.size - 1) * frac).toInt().coerceIn(0, sorted.size - 1)`,
   where `(500-1)*0.99` truncates to index 494. Halve `WINDOW` and both sentences are
   wrong.
2. **The 10 ms `jdk.ExecutionSample` period**
   (`JvmProfileCapture.kt:22#enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace()`).
   The page derives "a 2 ms hitch has about a one in five chance of appearing in any
   sample" from it.
3. **The `150/s` allocation throttle**
   (`JvmProfileCapture.kt:23#enable("jdk.ObjectAllocationSample").with("throttle", "150/s").withStackTrace()`).
   The page derives from it why a per-frame byte budget off sampled allocation is a working
   guide rather than something the JVM owes you.
4. **The three-way source identity**
   (`profileTimelineModel.js:55#if (source.boxId !== context.uid || source.property !== context.property || source.boxCode !== context.text) continue;`).
   `boxId`, `property` and character-identical text. Relax any one of the three and boxes
   start borrowing each other's gutter stripes; the page explains the strictness as the
   reason a one-character edit clears the marks.

## A measured capture, 2026-09-12

One replay of `dev_docs/agents/transcripts/profiling_timeline.yaml` through the backdoor
against a Field launched from a nightly worktree, `applied=18 skipped=0 errors=0`. Kept
because the *ratios* are the interesting part and they are hard to guess.

```
elapsed 3002.6 ms of a 3000 ms capture      3 lanes, 664 resumes, 0 dropped
resume wall time, summed:      63.9 ms  =  2.13% of the capture
JFR events:  207  =  9 cpu  +  198 allocation        3 dropped (one per sampled thread)
sampled threads: field-runloop-main, DefaultDispatcher-worker-2, kotlinx.coroutines.DefaultExecutor
resolved sources: 13, of which 2 were box code
distinct threads carrying resumes: 1        runloop frames touched: 350
```

Four things in there are worth keeping.

**Nine CPU samples in three seconds.** Three boxes were deliberately burning CPU and the
sampler found nine. The arithmetic reconciles and is the whole point: 3 s at a 10 ms period
is 300 possible firings, the workload occupied 2.13% of wall time, so about six samples
were due. This is the number to quote at anyone who expects sampled stacks to behave like
an exact profiler.

**Three dropped events across exactly three sampled threads.** That is
`JvmProfileCapture.kt:125#// recording. Establish its baseline instead of charging those bytes to the brush.`
doing its job, one discard per thread, visible in `stackDropped` rather than silent.

**Resumes are bimodal within a lane, and the shape is structural.** The `loop { work;
delay(16) }` boxes each produced 314 resumes: 157 doing the work and 157 waking from the
delay and immediately looping. For the vector box the split was 157 resumes at a mean of
229,712 allocated bytes against 157 at a mean of 208. So its median resume allocated 208
bytes and its mean allocated 114,966, and a percentile over resumes is a percentile over a
population that is half empty. The burst box was the same shape in CPU rather than bytes —
36 resumes, 12 of them over 1 ms, `p50` 0.027 ms against a `max` of 2.224 ms.

**Sampling found the allocation problem and nearly missed the CPU problem.** Grouped
inclusively by box line, the vector box's line 12 carried 30.85 MiB across 146 samples —
which is the published page's worked example, a real per-frame allocation named by line.
The burst box, costing 2.2 ms every 240 ms and by far the largest single cost in the
recording, drew exactly **one** CPU sample. The exact per-resume counters had it in the
first second.

## Reproducing it

The transcript needs no GPU and no private sources, and is in the executable set
(`DemoTranscriptExecSmokeTest.kt:98#internal val EXECUTE = setOf(`). With a Field already
running from your own checkout, and after confirming the listener's working directory
really is that checkout:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --attach-existing --port <yours> \
  --workspace /a/scratch/workspace --document profiling_scratch \
  --command 'macro write profiling_timeline --file dev_docs/agents/transcripts/profiling_timeline.yaml' \
  --command 'macro run profiling_timeline'
```

Then read the latched capture back with a second invocation, `run <box> --file <script.kt>`
followed by `output <box>`. Two notes from doing this:

- The workspace and document must be **separate** flags. A single `--document dir/name`
  produces an attached session with no boxes.
- Box output is keyed by source line, so a script that prints from inside a loop over lanes
  loses all but one iteration's lines. Accumulate into one string and print once.

## Precise observations, not fixed here

Found while driving this code for the documentation branch. None is urgent; all four are
source-level readings with the site named, deliberately left alone rather than fixed on a
docs branch.

1. **`CPUSpan.notifyListeners` takes the lock a second time on every timed span.** `time`
   already holds `lock` to record the sample, then ends in `notifyListeners`, which takes it
   again to copy the listener list
   (`Renderer.kt:92#val targets = synchronized(lock) { listeners.toList() }`). With six
   labelled spans per window context per frame that is a second uncontended monitor
   acquisition roughly 360<!--derive: 6*60--> times a second on the render thread. The *push* it guards is
   throttled to once a second
   (`rendererBrowsing.kt:824#private fun maybePushCpuChanged(intervalMs: Long = 1000) {`);
   the lock acquisition is not.

   **What this is *not*, because the obvious reading is wrong:** it is not an allocation per
   span when nobody is listening. `Iterable<T>.toList()` is
   `if (this is Collection) return when (size) { 0 -> emptyList(); 1 -> listOf(…); else -> toMutableList() }`,
   and `emptyList()` returns the `EmptyList` singleton — so with no listeners attached the
   copy allocates **nothing**, and with one listener it allocates a `SingletonList` rather
   than an `ArrayList`. An "early return when the list is empty" would therefore save the
   monitor acquisition and no garbage, and it would still need the lock to read `listeners`
   safely. Left alone because this is a documentation branch, and worth a good deal less
   than the shape of the code suggests.
2. **Interpolated labels allocate a string per span.** Every renderer call site builds its
   label by interpolation, as at `Renderer.kt:1248#CPUSpan.time("present[$ctxIndex]") {`, so
   each timed span allocates a `String` and hashes it to find its deque
   (`Renderer.kt:85#stats.getOrPut(label) { Stat() }.add(dt)`). Same order of magnitude as
   the above, same thread, and the same "the instrument that measures allocation allocates"
   flavour. The capture pipeline has its own version of this, and it turned up in its own
   output: the sampler attributed 0.50 MiB apiece to five separate lines of
   `CoroutineCapture`'s constructor, which are the `LongArray(65_536)` ring buffers
   (`CoroutineProfiling.kt:120#private val starts = LongArray(capacity)` and its siblings)
   that arming allocates. That one is honest overhead rather than waste — it is bounded, it
   happens once, and the published page states its size.
3. **`CPUSpan` percentiles are computed the same way on a partly-filled window, and say
   nothing about the fill.** `Snapshot.n` reports it, but
   `Renderer.kt:107#private fun buildSnapshot(label: String, samples: List<Long>): Snapshot {`
   runs the same percentile function regardless, so a freshly started process reports
   `p99 == p50 == max` off a single sample with no indication that it is one sample. A
   consumer has to check `n` itself, and neither current consumer does: the log line and
   the Inspector card both print `p50`/`p90` without it.
4. **`CPUSpan.Stat.samples` is an `ArrayDeque<Long>`**, so each recorded span boxes a
   `Long`, and `snapshot()` copies every deque under the lock
   (`Renderer.kt:102#stats.mapValues { it.value.samples.toList() }`) — 23 labels at 500
   entries is about 11,500<!--derive: 23*500--> boxed elements per snapshot, taken up to once a second by the
   Inspector and every five seconds by the log line. Fine at this size; worth knowing
   before adding a consumer that polls faster.

## Where this lives

| | |
|---|---|
| the wire types | `src/commonMain/kotlin/profiling.kt` |
| the collector and the ring | `src/jvmMain/kotlin/afield/CoroutineProfiling.kt` |
| the only dispatch hook | `src/jvmMain/kotlin/afield/Phased.kt` — `CoroutineProfiles.resume` |
| JFR recording, parsing, source lookup | `src/jvmMain/kotlin/afield/JvmProfileCapture.kt` |
| the renderer's own span timer | `src/jvmMain/kotlin/afield/four/Renderer.kt` — `object CPUSpan` |
| the panel | `src/commonMain/resources/profilingTimeline.js` |
| brushing, hotspots and heat lines | `src/commonMain/resources/profileTimelineModel.js` |
| the Monaco gutter | `src/commonMain/resources/lineHeatmap.js` |
| the Inspector's renderer card | `src/jvmMain/kotlin/afield/server/rendererBrowsing.kt` |
| behaviour, as tests | `src/jvmTest/kotlin/afield/CoroutineProfilingTest.kt` |
| a browser regression over the gutter stripes | `tools/ui_regressions/profiling-stacks.js` |

Three of those tests are worth knowing by name, because between them they pin the three
properties the published page leans on hardest: that suspension is a gap rather than charged
time (`CoroutineProfilingTest.kt:48#@Test fun dispatcherRecordsResumesAndAllocationWithoutSuspensionGaps()`),
that a resume already running at the latch is still included
(`CoroutineProfilingTest.kt:93#@Test fun latchIncludesAResumeAlreadyInProgress()`), and that
JFR sampling reaches background threads and stops at the deadline regardless of the Runloop
(`CoroutineProfilingTest.kt:110#@Test fun jfrSamplesBackgroundWorkAndStopsAtDeadlineEvenDuringALongResume()`).
The gutter stripes themselves are covered only in a browser regression, which asserts a
painted decoration exists
(`profiling-stacks.js:73#await wait(()=>document.querySelector('.monaco-editor .afield-profile-heat'),'Painted gutter stripe');`)
and not the three-way identity rule above it.
| the published model | `public/docs/frame_budget.md` |
| the published panel reference | `public/docs/profiling_timeline.md` |
