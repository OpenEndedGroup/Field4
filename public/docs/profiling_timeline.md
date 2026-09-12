# Profiling Timeline

Run **Show Profiling Timeline** from the command palette while your boxes are running.
Choose a capture length, then **Record**. Field records each box-owned coroutine
resume scheduled through the Runloop: its elapsed execution time, thread CPU time,
and allocated heap bytes. Recording ends automatically, or you can press **Latch**.
Your boxes keep running while you explore the capture.

This page is the panel: what every control does, and what each number in it is a number
*of*. For the model underneath — which of Field's three profiling instruments to reach for,
what each one structurally cannot see, and the route from "it stutters" to a named line of
your own box source — see [Holding a Frame Budget](frame_budget.md).

Enable **JVM stacks** before recording to add sampled CPU and allocation stacks.
After latching, **Hot code** groups the selected time interval by class, method, or
line. This includes background workers and renderer CPU work, alongside the exact
Runloop measurements in **Coroutines**. The panel resizes from its edges and corners.

## Try the demo

The **profiling_timeline** starting point has four editable boxes. Find it on Field's
welcome screen, or [browse its code here](/offline.html?file=/field4/assets/agent_transcripts/profiling_timeline/metadata.edn).
Launch boxes 1–3 together, then run **4. Record and explore**. It opens the timeline
and starts a three-second capture with JVM stacks enabled. Stop the workload boxes
when you have your recording. Turn stacks off in the panel for counter-only comparisons.

For a complete replay, copy `dev_docs/agents/transcripts/profiling_timeline.yaml` into
your workspace's `macros` directory. Open an empty document, open **Macro Recorder**
from the command palette, select **Workspace**, and run `profiling_timeline` with
no boxes selected. The macro creates its boxes, waits for launch startup, warms the
workloads, records, and stops them. It leaves the timeline open for exploration.
Replay restores the demo's original code; use the boxes directly when keeping your edits.

The same replay through the backdoor, attached to an already running Field:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --attach-existing --port 9092 \
  --workspace /path/to/workspace --document my_demo \
  --command 'macro write profiling_timeline --file dev_docs/agents/transcripts/profiling_timeline.yaml' \
  --command 'macro run profiling_timeline'
```

Replace the workspace, document, and port with your running instance's values. This
attaches to that document and opens the timeline in its connected browser. For an
isolated automation check, omit `--attach-existing`, use a dedicated port such as
9477, and add `--with-playwright`; that check uses a disposable session and a headless browser.

| Box | What to look for | Try changing |
| --- | --- | --- |
| **1. Steady work** | Frequent small execution bars | Increase `iterations` to widen them |
| **2. Periodic bursts** | Longer bars separated by quiet gaps | Increase `iterations`, or shorten `quietMillis` |
| **3. Allocate or reuse** | Heap allocation despite relatively short execution | Set `reuse = true` and record again |
| **4. Record and explore** | Starts the capture and opens the viewer | Change the capture duration |

The third box updates an array of Field vectors. With `reuse = false`, it constructs
a fresh array and fresh `vec` values on every iteration. With `reuse = true`, it calls
`set` on the existing vectors. Select that lane and switch to **Allocated bytes** to
compare them. Record each version under the same conditions; the exact time and byte
counts depend on the JVM and machine. Initialization happens before the recording,
so the comparison measures the running loops.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#profile-timeline" screenshot="assets/profiling_timeline/brush.png" transcript="../../dev_docs/agents/transcripts/profiling_timeline.yaml" padding="8" wait="0" timeout="180" embed=true hidden=true name="profiling-timeline-brush" alt="A real three-second Field capture, brushed and zoomed, with separate lanes for steady arithmetic, periodic bursts, and vector allocation. The selected vector lane reports CPU time and allocated bytes."}
ui.eval("window.showProfilingTimeline(); return 'requested'")
var ready = false
repeat(40) {
    if (!ready) {
        ui.pause(150)
        ready = ui.eval("return document.getElementById('profile-status')?.textContent.startsWith('Latched') && Number(document.getElementById('profile-timeline')?.dataset.selectedCount) > 30")?.toString() == "true"
    }
}
check(ready) { "The real profiling demo did not produce a latched capture" }
// Frame the actual panel at a readable width; all content and plotted data come from the capture.
ui.eval("const p=document.getElementById('profile-timeline'); p.style.width='1180px'; p.style.right='auto'; p.style.height='500px'; return 'framed'")
ui.pause(300)
ui.eval("""
    const metric=document.getElementById('profile-metric');
    metric.value='bytes'; metric.dispatchEvent(new Event('change'));
    const overview=document.getElementById('profile-overview');
    for(let i=0;i<4;i++) overview.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
    document.getElementById('profile-zoom').click();
    const lane=[...document.querySelectorAll('.profile-labels button')].find(b=>b.textContent.includes('Allocate or reuse'));
    if(!lane) throw new Error('The vector workload has no lane');
    lane.click();
    const count=Number(document.getElementById('profile-timeline').dataset.selectedCount);
    if(count<=0) throw new Error('The brushed interval has no observed resumes');
    return count;
""")
ui.pause(300)
```

## Explore a capture

- Drag across the overview to select a time range. Left and right arrow keys move
  the selection; Escape clears it.
- Choose **Execution time**, **CPU time**, or **Allocated bytes** for the overview.
- Press **Zoom to selection** to spread that interval across the coroutine lanes.
- Select a lane to focus its totals, then click a span to inspect one resume.
  Each lane belongs to a coroutine, so one box can have several lanes.
- The detail panel lists the longest resumes in the selection. Click one to zoom
  around it, or use **Open current box code** to return to its owner in the editor.
- **Show all** resets the range and lane selection. The search field filters by box,
  strand, or coroutine number.
- **Save capture** downloads the completed capture as JSON. Closing and reopening
  the panel retains the latest capture; recording again replaces it.

The full width of a span is elapsed execution time. Its cyan lower strip shows
the portion accounted for by thread CPU time. Empty space between spans means that
coroutine was not executing on the Runloop: it might have yielded, suspended, or
moved to another dispatcher. A coroutine marked *active* is not necessarily using
CPU at that moment.

Selection totals include **whole resumes whose start lies inside the selection**.
An allocation counter cannot tell us where within a resume the allocations happened,
so brushing through part of a span does not divide its byte count. The displayed
p95 is for individual resumes, not whole frames. Each inspected span also carries
its Runloop tick number.

## Classes, methods, and box lines

Turn on **JVM stacks**, record while the work is running, then open **Hot code**.
Stack processing happens after the latch; the panel shows its progress. Brush the
same overview to restrict the samples, and search for a package, class, or method.
Choose **Classes**, **Methods**, or **Lines**, and switch between **CPU samples**
and **Allocation samples**.

**Whole JVM** includes other documents, compilation, renderer workers, and background
threads. Use the thread selector to isolate a worker. **Selected coroutine** keeps
only samples whose thread and timestamp fall inside that coroutine's measured
resumes. Worker-thread samples are not automatically attributed to the box that
started them.

**Including callees** counts a sample in each distinct stack row it contains;
these rows overlap and must not be added together. **Top frame only** attributes
each sample to its innermost recorded frame. CPU percentages are shares of sampled
events, not percentages of exact CPU milliseconds. Allocation weights estimate heap
bytes; use the coroutine counters for exact bytes within a resume. The first allocation
event per thread is omitted because its weight can include bytes from before recording.
Short work may
fall between samples, and JIT optimization can affect line attribution.

Select a hot row to read its source. Box source is copied from Field's compiled
snippet records. **Open box with gutter heatmap** takes you to the box in Monaco.
With **Box gutter heatmap** enabled, sampled lines carry a gutter stripe: cyan for CPU,
amber for estimated allocation, with brightness relative to the hottest mapped line
in that box. Hover a marked line for its sample count or byte estimate and the selected
interval. The heatmap follows the time brush, thread, coroutine scope, and self/inclusive
setting, including when you navigate to another sampled box. The class/method search
filters the table only. These stripes coexist with history churn and execution density.

Only directly mapped lines whose box, property, and current text match the capture are
marked. Editing the source clears its marks; restoring the captured text restores them.
Closing the profiling panel clears the heatmap. Fragments or generated
lines without a verified mapping remain available as compiled source in the panel.
Codebase source is read from the checkout after capture, when available; it may differ
from the loaded class after a source edit. Missing source does not hide a hot class.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#profile-timeline" screenshot="assets/profiling_timeline/hot-code.png" transcript="../../dev_docs/agents/transcripts/profiling_timeline.yaml" padding="8" wait="0" timeout="180" embed=true hidden=true name="profiling-timeline-hot-code" alt="Measured allocation stack samples in Field's Hot code view, with a vector workload's captured source lines."}
ui.eval("window.showProfilingTimeline(); document.getElementById('profile-tab-stacks').click(); return 'requested'")
var stacksReady = false
repeat(100) {
    if (!stacksReady) {
        ui.pause(150)
        stacksReady = ui.eval("return !document.getElementById('profile-save').disabled && Number(document.getElementById('profile-stacks').dataset.sampleCount)>0")?.toString() == "true"
    }
}
check(stacksReady) { "Real JVM stack capture did not finish" }
ui.eval("""
    const p=document.getElementById('profile-timeline');
    Object.assign(p.style,{width:'1180px',right:'auto',height:'560px'});
    const kind=document.getElementById('profile-stack-kind');kind.value='allocation';kind.dispatchEvent(new Event('change'));
    const group=document.getElementById('profile-stack-group');group.value='line';group.dispatchEvent(new Event('change'));
    const metric=document.getElementById('profile-metric');metric.value='sampleBytes';metric.dispatchEvent(new Event('change'));
    const search=document.getElementById('profile-stack-search');search.value='profile_vectors';search.dispatchEvent(new Event('input'));
    const row=[...document.querySelectorAll('.profile-hot-row')].find(b=>Number(b.dataset.line)>0);
    if(!row)throw new Error('No vector allocation stack was sampled');
    row.click();
    if(!document.getElementById('profile-hot-source').textContent.includes('captured compiled box source'))throw new Error('Box source did not resolve');
    return 'sampled source';
""")
ui.pause(300)
```

## What the numbers mean

Allocated bytes measure heap allocation on the executing thread, including coroutine
and library work inside that resume. They are not retained memory, bytes reclaimed
by GC, or native/GPU memory. CPU and allocation counters exclude suspended time;
unsupported JVM counters appear as **unavailable**. Elapsed execution time can include
blocking and pauses during the resume, but this view does not identify their causes.

This first view covers `loop`, `frames`, sequence launches, and child coroutines that
inherit a box's coroutine context and dispatch through the Runloop. Work executed
inline inside a parent's resume is included in that parent; a child's later scheduled
resumes have their own lane. Unowned Runloop tasks and work on other threads, including
background compilation and renderer execution, are outside the exact resume counters.
Optional JVM stacks cover their sampled CPU/allocation activity. GPU execution remains
in the existing renderer profiler; this panel does not yet align GPU timings or GC
pause events with its timeline.

The source revision is a fingerprint of the box's `code` property captured with its
launch context. The source button opens **current** code; it does not restore the
captured revision. Use **Hot code** for sampled source lines. Alternate-property launches are
identified by their strand, but the fingerprint still refers to the `code` property.

## Capture limits

Capture lengths are 3, 10, or 30 seconds. An already executing resume is allowed to
finish after the latch so a long stall is still included. Closing the panel does not
cancel a capture; its deadline still applies. Only one document can record at a time.

The backend keeps at most 65,536 resumes and 1,024 coroutine lanes per capture, and
the latest capture for up to four documents in memory. The panel reports omitted
resumes when those limits are exceeded. A restart clears captures; save JSON if you
want to keep one.

Optional JFR recording stops at the latch deadline, even if an already executing
coroutine resume continues beyond it, and is limited to 64 MiB. The viewer keeps up to 12,000 stack
events, 16,000 distinct frames, and 256 source texts (four million characters total),
with at most 64 frames per stack. It reports omitted events. Stack/source processing
runs off the Runloop. JFR startup can hitch before the measured interval; allow the
scene to warm up and use identical sampling settings for comparisons. Saved captures
include sampled source text and JVM-wide thread names.

Recording is opt-in. With no capture active, dispatch does not read timing or
allocation counters or create sample objects. Recording adds counter reads and
bounded bookkeeping; live viewing also requests and draws snapshots. For close
performance comparisons, use the same capture settings and workload in each run.

GPU execution remains outside all of this, and so does the renderer's own always-on span
timer — [Holding a Frame Budget](frame_budget.md) covers both, and says which of the three
instruments answers which question.

For document versions and restoration, see [Document History](document_history.md).
