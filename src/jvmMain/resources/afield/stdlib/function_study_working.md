# Function conversation workbench

A function study is a box whose Kotlin returns a StudyComposition and whose `aiStudy`
property is `function` ("Make a Function Study" sets it). Open AI4 general chat on it
(command/ctrl-4) and these tools work on that box. Each successful tool edit records
the entire source and its parent in `studies/<box id>/`; failed compilation keeps the
working function. Restarting Field restores the last checked revision; manual source
changes are reported as dirty.

The box shows a Plotly `overview` viewer that follows the current revision.
Historical observations have a separate, explicitly labelled viewer. Wheel/pinch zooms around the
pointer; drag or shift-wheel pans; double-click or **all** restores the whole hour.
The strip below the ruler moves the current window across the full domain.
Hover reads sampled values. The viewer keeps its interval across accepted edits;
the previous revision can appear in dashed grey. Zooming in samples the actual
functions more finely. The footer gives the sampling interval; extrema between
samples are not guaranteed. AI4 still receives snapshot PNGs and numeric checks
from ObserveFunctionStudy, independently of the human viewer's current zoom.

The current selection is set with SelectFunctionStudy. To restore, inspect a prior
revision and submit its source as a new EditFunctionStudy transaction. To restore
only the background, copy its source passage and retain current event declarations.

## Fast edit and export loop

Inspect once to obtain the checked revision and `liveSourceHash`. If `dirty` is
true, `liveSource` contains the editable source; reconcile that with the checked
`source` rather than requesting another source-read tool.

For a small change use `PatchFunctionStudy(expectedRevision, expectedSourceHash,
edits, description, previewStartSeconds, previewEndSeconds)`. Each edit is an
exact `before`/`after` snippet which must match once in the original live source;
patches cannot overlap. Missing/ambiguous matches and stale guards change nothing.
Use full `EditFunctionStudy` only for initial construction or substantial rewrites.

Both operations compile/check before publishing, update the human viewer, and
return `validation` with the exact source hash and revision plus an actual image
preview. Choose a useful local interval around the change; by default the first
25 seconds are returned, clamped to the duration. The preview compares the parent
where its channel layout/domain permits. This fulfills the standalone study's
compilation requirement. Avoid a redundant CompileKotlin or observation round;
request additional comparison/observation only for missing evidence. A preview or
viewer error explicitly says the edit was already activated, so do not repeat it.

Use `ExportFunctionStudy` for exports and re-exports: revision, expectedSourceHash,
filenames (one per channel), sampleRateHz (default 100), scale (default 100),
header (default false), overwrite (default false). It uses Field's file resolver
for this document's files directory. Names are literal; `a` stays `a`. It samples
the cached checked revision, includes the exact endpoint, stages and verifies all
files, and returns paths, row counts, duration, scale, and SHA-256 checksums.
Re-export uses `overwrite=true`; dirty current source is refused for reconciliation.
It does not recompile the study or generate/execute another exporter.

## Actual Kotlin contract

```kotlin
import afield.stdlib.Functions
import afield.stdlib.StudyComposition
import afield.stdlib.StudyEvent
import afield.stdlib.StudyChoice
import kotlin.math.*

with(Functions) {
    val channels = listOf("x", "y", "z").mapIndexed { index, name ->
        fn(0.0..3600.0, name) { seconds ->
            0.5 + 0.25 * kotlin.math.sin(seconds * 2.0 * PI / 40.0 + index)
        }
    }
    StudyComposition(channels = channels,
        choices = listOf(StudyChoice("40 second carrier", "agent assumption")))
}
```

`StudyEvent(id, start, rise, hold=30.0, fall=60.0)` records seconds and exposes
`plateauStart`, `plateauEnd`, `end`. Declare only actual holds at the all-ones
target. `StudyComposition(channels, events, choices, duration=3600.0,
channelNames=listOf("x","y","z"), maxFrequencyHz=2.0, seed=0)` returns the
ordinary Fn values. Event metadata must follow the evaluator's actual timings.
It is not an alternate AST or executable source of truth.

Inside `with(Functions)`, qualify scalar trigonometry as `kotlin.math.sin/cos`:
the DSL's `sin/cos` construct Fn values. The first live conversation exposed and
repaired this name-resolution error. Empty revision fields are treated as current.

The current Wither compiler plugin has another verified pitfall: a local-function
call as the last statement of a brace block can fail with `has no callableId`.
For example, end the rise branch with `val result = shoulder(...); result` instead
of a bare `shoulder(...)` call. This source workaround was compiled in the real
Field runtime; the failed attempts are saved. No compiler plugin was replaced.

ObserveFunctionStudy also detects local peaks at <=2ms in <=25-second windows;
inter-peak spacings are estimates and background motion can move them.
CompareFunctionStudy tests the whole preserved region in one call: specify the
only interval/channels permitted to change; all other samples must agree. Use
the union of an event's old/new supports, or a pinned warp's interval.

Functions supports domains, arithmetic, envelopes, ramps, interpolation, timelines,
`stretchBy`, phase transforms and graph rewrites. Scalar Fn channels are collected
into a list here. `fn` lambdas are explicitly opaque to graph rewriting. Fn eval
is unchecked outside its domain; this harness rejects out-of-range observations.
The standard `Fn.plotHtml` remains available for interactive local plots; this
experiment adds aligned min/max images because a one-hour point plot aliases pulses.

## Time and observation policies

Use integrated frequency for chirps: if f(u)=f0+k*u then cycles=f0*u+k*u*u/2.
Increasing frequency times time is not the integral. Carrier period and relative-phase timescale are separate parameters; ask for or state each artistic assumption. Use an explicit
constant plateau; fade/widen oscillations into it, then return smoothly to the
continuing background. Smoothstep/quintic windows avoid abrupt value/velocity joins.

Compare old/new supports and all preserved intervals, not just edited lines.
Move changes start only; stretching a rise keeps hold seconds fixed and must
declare whether the plateau or start stays pinned. Other events do not ripple.
For a channel lag, recover the shared target before the hold. For a warp, pin
endpoints and endpoint derivatives, check monotonicity, and declare its scope.

Each observation has a revision, interval, sample count/step, image and measurements.
Wide plots are min/max bins (ordering inside bins is lost); <=25-second windows
show waveforms. Explicit joins are sampled. The max-frequency declaration controls
density but cannot guarantee a black-box lambda has no hidden narrow features.
The [0,1] admission check is an experimental assumed constraint. Holds are tested
at 61 points; source inspection is required for a structural constant-segment claim.

## Boundaries of this first slice

Kotlin runs in Field's existing local compiler/runtime, on a fresh engine per
candidate. It is trusted document code, not a security sandbox. Source size,
duration, channel counts and sample budgets are bounded; cancellation prevents
pending publication and interrupts cooperative sampling. It cannot forcibly stop
arbitrary non-cooperative Kotlin. AI4 progress distinguishes completion, failure and cancellation. No stateful audio graph is sampled. Never call it isolated execution.

Revision sources/plots persist, and selection/referents live in the active session.
Saved conversation evidence is recorded separately under `conversations/` by the
demo driver; that driver uses the actual AI4 chat RPC and captures its real output.
The 20-task context ablation from the brief is a follow-on experiment, not a result
claimed by this initial demo.

