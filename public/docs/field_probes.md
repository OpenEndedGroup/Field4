# Field Probes — instrumenting shaders

When work moves onto the GPU it also moves out of sight. A compute shader that
updates a million particles produces no `println`, no stack trace, no obvious
way to answer the first question you always end up asking: *what do the numbers
actually look like?* Are the speeds finite? Is anything NaN? What is the spread,
the median, the tail?

Field answers that with two **probes** — small, drop-in instruments you attach to
a shader and a buffer to watch a distribution of values as it is computed. They
share a philosophy (add one line to a shader, read a live summary back in the
front end) but sit at opposite ends of a trade-off:

| | **`fieldProbe`** — CPU read-back probe | **`fieldStats`** — GPU stats probe |
|---|---|---|
| Where stats are computed | exact aggregates on the GPU; histogram / mean / percentiles on the **CPU** from a sample | **entirely on the GPU** (parallel reduction) |
| What comes back to the CPU | a reservoir sample of raw values + exact min/max/counts | a handful of scalars per channel (optional) |
| Front-end output | a live histogram + summary **card** | numbers (you render them however you like) |
| Best for | *seeing* a distribution while you develop | *feeding stats back into the shader*, or many channels cheaply |
| Cost | a read-back per snapshot (throttled, off-thread) | one extra compute dispatch; read-back optional |

Both are wired the same way. Call one setup function on the box, add
`{% include "…" %}` to the shader, and call a one-line
recording function wherever the value you care about is in scope. Field resolves
the include, allocates the backing buffers, and attaches the read-back lifecycle
automatically — there is no descriptor-set or SSBO bookkeeping to do by hand.

The running example throughout is a compute-shader particle system: a swirl of
particles advected by a simple force field, drawn as soft quads. We instrument
the one quantity that is otherwise invisible — each particle's **speed**.

---

## 1. `fieldProbe` — the CPU read-back probe

`fieldProbe` is the one you reach for while developing. It samples values on the
GPU, pulls a representative slice back to the CPU, and renders a live
**histogram + summary card**. The card is the "splendid-looking" object in the
video below: signed, delta-coloured metric chips over an SVG histogram. First,
the running example it instruments.

### The particle system we instrument

The compute shader reads each particle from one `buffer_reference` buffer,
rotates and mixes its position by a slowly-advancing angle (`something`, driven
from Kotlin), and writes the result — plus its carried size in `.w` — into a
second buffer. The vertex shader expands six vertices per particle into a
camera-facing quad; the fragment shader draws a soft dot. This is the ordinary
two-module (compute + draw) pattern from
[afield.four Graphics](afield_four_graphics.md) — the only probe-specific lines
are the include and the single `fieldProbe(length(delta))` call, which records
how far each particle moved this step (its per-step **speed**).

```diffstatic {data-lang=glsl}
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer inData  { vec4 a[]; };
layout(std430, buffer_reference) buffer outData { vec4 a[]; };

layout(push_constant) uniform dat {
    inData  inData;
    outData outData;
    float   something;      // a slowly-advancing angle, set from Kotlin
} pc;

{% include "fieldProbe" %}

void main() {
    uint idx = gl_GlobalInvocationID.x;

    // rotate / mix each particle's position by the current angle
    pc.outData.a[idx].x =  pc.inData.a[idx].x*cos(pc.something*pc.inData.a[idx].z) + pc.inData.a[idx].y*sin(pc.something*pc.inData.a[idx].w);
    pc.outData.a[idx].y = -pc.inData.a[idx].x*sin(pc.something*pc.inData.a[idx].w) + pc.inData.a[idx].y*cos(pc.something*pc.inData.a[idx].z*0.03);
    pc.outData.a[idx].zw = pc.inData.a[idx].zw;      // carry seed + size

    // the one quantity we cannot otherwise see: how far each particle moved
    vec2 delta = pc.outData.a[idx].xy - pc.inData.a[idx].xy;
    fieldProbe(length(delta));   // <-- the only instrumentation
}
```

The Kotlin that drives it lives in two boxes — a **compute** box and a **draw**
box, both children of a renderer box that owns the `Window`. The compute box
allocates the two GPU buffers, seeds one with random particles, publishes the
output buffer as `outputPositions`, and turns the probe on. `fieldProbeHere(...)`
plus `.display("probe")` is all it takes:

```diffstatic {data-lang=kotlin}
// --- compute box -------------------------------------------------
var module = computeModuleHere {}
var m = ComputeMesh()
module += "step" to m
renderer += "compute" to module

val N = 100000
var inData  = defaultBuffer { size = N.vec4s }.buffer
var outData = defaultBuffer { size = N.vec4s }.buffer
m.inData  = { inData }
m.outData = { outData }
var x = 0.0
m.something = { x += 0.01; x.f }         // the advancing angle

// Turn the CPU probe on: keep 4096 live samples and show the card.
fieldProbeHere(samples = 4096).display("probe")
displayOpen("probe")                     // start the card expanded, not collapsed

prop<FrameBufferGroup>(outputPositions) = outData   // publish for the draw box
m.x = N / 64; m.y = 1; m.z = 1
inData.write { for (i in 0 until N) put(vec(Math.random(), Math.random(), Math.random(), Math.random())) }
```

```diffstatic {data-lang=kotlin}
// --- draw box (child of the compute box) -------------------------
var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "mesh" to m
renderer += "draw" to module

val N = 100000
m.inData      = { outputPositions }      // read the published buffer
m.aspectRatio = { window.width / window.height.toFloat() }
m.set(N * 2 * 3, 0) {}
```

Launched together, the three boxes draw a slowly-turning galaxy of 100 000 soft
quads:

```diffstatic_runner {data-lang=kotlin screenshot="assets/field_probes/particles-galaxy.png" capture="native-window" native-wait-frames="150" embed=true hidden=true force=true timeout="120" duration="0" name="field-probes-galaxy" alt="100k soft-quad particles drawn as a glowing galaxy" macro-file="../../dev_docs/agents/transcripts/threebox.yaml" capture-box="tb_renderer"}
// Built and launched from dev_docs/agents/transcripts/threebox.yaml
// (renderer + compute + draw). See the code blocks above.
```

Nothing on that screen tells you what the particle *speeds* look like. The probe
does. `.display("probe")` renders a live **histogram + summary card** on the
compute box, refreshing about five times a second as the simulation runs — the
card is deliberately rate-limited by `ShaderFieldProbe.maxDisplayHz` (5 Hz by
default), which gates both the buffer readback and the display dispatch so a
100 000-sample probe cannot flood the output pane at renderer frame rate:

```diffstatic_runner {data-lang=kotlin video="assets/field_probes/fieldprobe-card.mp4" capture="box" open-outputs=true wait-output=true wait-output-kind=display wait-output-channel=probe embed=true hidden=true force=true timeout="180" duration="6" fps="10" restart-target=false name="field-probes-card" alt="live fieldProbe histogram and summary card" macro-file="../../dev_docs/agents/transcripts/threebox.yaml" capture-box="tb_compute"}
// The "probe" display channel on the compute box, recorded live while the
// galaxy above animates. Same transcript: threebox.yaml.
```

Those per-step speeds are what the rest of this section records and displays;
the GPU-only sibling probe in §2 reads the same simulation a different way.

### GLSL side

Add the include once, then call `fieldProbe(...)` wherever the value is in scope.
The include defines overloads for every common type — scalars, vectors, and
their integer forms:

```diffstatic {data-lang=glsl}
{% include "fieldProbe" %}

void fieldProbe(float value);   void fieldProbe(int  value);   void fieldProbe(uint value);
void fieldProbe(vec2  value);   void fieldProbe(vec3 value);   void fieldProbe(vec4 value);
void fieldProbe(ivec2 value);   void fieldProbe(ivec3 value);  void fieldProbe(ivec4 value);
void fieldProbe(uvec2 value);   void fieldProbe(uvec3 value);  void fieldProbe(uvec4 value);
```

Under the hood each call does exact, lock-free bookkeeping into one std430
buffer: it bumps an invocation counter, tracks accepted / finite / NaN / Inf /
non-zero counts, and folds per-component min and max with `atomicMin` / `atomicMax`
over an order-preserving `uint` encoding of the float. Raw values are captured by
**reservoir sampling** (a hash of the invocation ordinal decides which slot to
overwrite once the reservoir is full), so the CPU receives an unbiased sample of
the whole population rather than just the first *N* invocations. The first probed
type wins for the frame; mixing `fieldProbe(float)` and `fieldProbe(vec3)` in the
same pass is counted as a type mismatch rather than silently averaged.

### Kotlin side

`Box.fieldProbeHere(...)` creates (or reuses) the probe on the box and returns it
for further configuration:

```diffstatic {data-lang=kotlin}
fun Box.fieldProbeHere(
    samples: Int = 1024,          // reservoir size pulled back to the CPU
    binding: Int = 63,            // SSBO binding for FieldProbeBuffer
    descriptorSet: Int = 0,
    enabled: Boolean = true,
    ordinalRange: UIntRange? = null   // probe only a slice of invocations
): ShaderFieldProbe
```

The returned `ShaderFieldProbe` is a small fluent object:

```diffstatic {data-lang=kotlin}
fieldProbeHere(samples = 4096)
    .display("probe")                 // render the card on this display channel
    .onSnapshot { snap -> ... }        // or receive each snapshot yourself

// tuning knobs
probe.maxDisplayHz = 12.0             // throttle card refreshes (default 5 Hz)
probe.ordinalRange(0u until 1000u)    // restrict which invocations are sampled
probe.configure(samples = 256)        // reconfigure live
probe.deregister()                    // stop and close the display channel

// pull the latest result without a display channel
val snap: FieldProbeSnapshot? = probe.readLatest()
```

`display(channel)` pushes the rendered HTML card to that output channel on the
box; `onSnapshot { }` hands you the raw `FieldProbeSnapshot` on the read-back
thread if you would rather build your own UI or drive control logic. Snapshots
are read back **off the render thread** and are throttled to `maxDisplayHz`, so
an idle or slow front end never stalls the GPU.

### The snapshot

Every read-back produces an immutable `FieldProbeSnapshot`. The counts and
`exactMin` / `exactMax` are exact GPU aggregates over *all* invocations; the
percentiles, histogram, and summary are computed on the CPU from the reservoir
sample:

```diffstatic {data-lang=kotlin}
data class FieldProbeSnapshot(
    val dimensions: Int,          // 1=scalar, 2=vec2, 3=vec3, 4=vec4
    val invocationCount: Long,    // total fieldProbe(...) calls
    val acceptedCount: Long,      // within ordinalRange and matching type
    val finiteCount: Long,        // non-NaN, non-Inf
    val sampleCount: Int,         // reservoir samples actually returned
    val nanCount: Long, val infCount: Long,
    val nonZeroCount: Long, val typeMismatchCount: Long,
    val exactMin: DoubleArray,    // per component, exact over all invocations
    val exactMax: DoubleArray,
    val samples: List<Vec4>
)

// derived, on demand:
snap.anyNaN; snap.anyInf; snap.anyNonZero
snap.percentile(0.95)             // from the sample
snap.scalarValues(); snap.scalarSummary(); snap.vec3Summary()
snap.summaryHtml(previous = last) // the card (histogram + chips)
```

`summaryHtml(previous)` is what `display(...)` calls internally — passing the
previous snapshot is what produces the delta colouring (rising values tint one
way, falling the other), which is why the live card looks alive.

### The live card

`display(...)` renders `FieldProbeSnapshot.summaryHtml(previous)`, with `previous`
supplying the delta colouring. This page intentionally does not embed a card capture
yet: the docs hook currently frames the editor rather than the output card when a
GPU-backed graphics context is active. The former video used a CPU-created snapshot
and therefore did not test the shader, buffer, read-back, or probe lifecycle described
here. A capture belongs here only after the hook can record the real GPU-driven card.

---

## 2. `fieldStats` — the GPU stats probe

`fieldStats` never needs to bring raw samples back to see a distribution. Each
recording call reduces values across the subgroup, a follow-up compute pass
reduces those partials to exact per-channel statistics (count, mean, variance,
min, max, sum) plus a t-digest-style **quantile sketch** — all on the GPU. The
results live in a buffer that *other shaders can read the same frame*, which is
the feature the CPU probe cannot offer: closing the loop on the GPU.

### Kotlin side

```diffstatic {data-lang=kotlin}
fun Box.fieldStatsHere(
    channels: Int = 1,        // independent distributions (1..64)
    sketchSize: Int = 256,    // quantile-sketch resolution (pow2, 16..256)
    maxPartials: Int = 65_536,
    descriptorSet: Int = 0,
    partialBinding: Int = 61,
    resultBinding: Int = 62,
    stateSlots: Int = 8       // GPU-side smoothed/EMA slots
): ShaderFieldStats
```

Read results back on the CPU per channel — or don't, if only the shader consumes
them (set `stats.cpuSnapshotsEnabled = false` to skip the read-back entirely):

```diffstatic {data-lang=kotlin}
val stats = fieldStatsHere(channels = 1)
// ... run the dispatch ...

val s = stats.read(0)          // blocking exact read of channel 0
val s = stats.latest(0)        // most recent async read-back (or null)

s.mean; s.standardDeviation; s.min; s.max; s.finiteCount
s.quantile(0.5)                // median from the GPU sketch
s.cdf(1.0)                     // fraction of samples <= 1.0
```

```diffstatic {data-lang=kotlin}
data class FieldScalarStatsSnapshot(
    val calls: Long, val finiteCount: Long,
    val invalidCount: Long, val nonZeroCount: Long,
    val min: Double, val max: Double,
    val mean: Double, val variance: Double,
    val sum: Double, val sumSquares: Double,
    val standardDeviation: Double,
    val droppedPartials: Long,                 // >0 means raise maxPartials
    val centroids: List<Pair<Double, Double>>  // (value, weight) sketch
) { fun quantile(q: Double): Double; fun cdf(value: Double): Double }
```

### GLSL side — recording

The producer include adds a subgroup-cooperative recorder. One shader invocation
per particle calls it; the helper reduces across the subgroup and writes a single
partial record per subgroup, keeping atomic traffic low:

```diffstatic {data-lang=glsl}
{% include "fieldStats" %}

void fieldStatsRecord(uint channel, float value);
void fieldStatsRecord(int  channel, float value);
void fieldStatsRecordVec4(uint firstChannel, vec4 value);   // 4 consecutive channels
```

```diffstatic {data-lang=glsl}
// in main(), after computing the new velocity:
fieldStatsRecord(0u, length(vel));
```

### GLSL side — reading back on the GPU

The same include (in *any* shader, producer or not) exposes query helpers that
read the reduced results of the **previous** frame. This is how you feed a
statistic back into rendering — for example, normalising each particle's speed
against the live median to drive its colour, entirely without a CPU round-trip:

```diffstatic {data-lang=glsl}
FieldScalarStats fieldStatsRead(uint channel);            // .mean .min .max .finiteCount ...
float fieldStatsQuantile(uint channel, float q);          // e.g. median = q 0.5
float fieldStatsCdf(uint channel, float value);
float fieldStatsSmoothedMean(uint slot, uint channel, float halfLife);
float fieldStatsSmoothedQuantile(uint slot, uint channel, float q, float halfLife);
```

`fieldStatsSmoothedMean` / `fieldStatsSmoothedQuantile` use the GPU-side
**state slots** to keep an exponentially-smoothed value across frames (a
`halfLife` in frames), so a statistic that feeds back into the sim does not
flicker frame to frame.

A shader that only *reads* the stats (a different box from the one recording
them) binds them as a consumer with `useFieldStats`:

```diffstatic {data-lang=kotlin}
val stats = producerBox.fieldStatsHere(channels = 1)
consumerBox.useFieldStats(stats)     // include "fieldStats" now resolves as a reader
```

### Reading it back on the CPU

If you do want the numbers in Kotlin — for logging, tests, or a custom readout —
the read-back is a few scalars per channel, not a sample array:

```diffstatic {data-lang=kotlin}
import afield.four.Renderer
import afield.four.modules.fromContext
val renderer: Renderer = fromContext()

val N = 16384

val stats = fieldStatsHere(channels = 1)

val positions  = defaultBuffer { size = N.vec4s }.buffer
val velocities = defaultBuffer { size = N.vec4s }.buffer
positions.write {
    for (i in 0 until N) {
        val a = Math.random() * Math.PI * 2.0
        val r = 0.15 + 0.75 * Math.random()
        put(vec(Math.cos(a) * r, Math.sin(a) * r, 0.0, 1.0))
    }
}
velocities.write { for (i in 0 until N) put(vec(0.0, 0.0, 0.0, 0.0)) }

val compute = computeModuleHere { }
val step = ComputeMesh()
compute += "step" to step
renderer += "compute" to compute
// Reflected push-constant members resolve once the mesh is attached to its module.
step.positions  = { positions }
step.velocities = { velocities }
step.dt = { 0.016f }
step.x = N / 64; step.y = 1; step.z = 1

// show the GPU-computed aggregates as a small table
var t = 0.0
frames {
    while (true) {
        stats.latest(0)?.let { s ->
            :::fold render the aggregates as a small table
            markdown("stats", """
                #### speed — channel 0 (GPU)

                | n | mean | σ | min | median | max |
                |---|------|---|-----|--------|-----|
                | ${s.finiteCount} | ${"%.4f".format(s.mean)} | ${"%.4f".format(s.standardDeviation)} | ${"%.4f".format(s.min)} | ${"%.4f".format(s.quantile(0.5))} | ${"%.4f".format(s.max)} |
            """)
            :::endfold
        }
        t += 0.016
        frame()
    }
}
```

---

## Choosing between them

- Reach for **`fieldProbe`** when you want to *see* what a shader is producing —
  the histogram and the delta-coloured card make an unfamiliar distribution
  legible in seconds, and the reservoir sample means you get shape (skew, modes,
  outliers), not just moments.
- Reach for **`fieldStats`** when the statistic has a job to do: feeding back into
  the shader the same frame, driving adaptive control, or monitoring many
  channels where pulling samples back for each would be wasteful. Its read-back is
  cheap enough to leave on, and can be turned off entirely.

They compose. A common pattern is to keep `fieldStats` on permanently for the
control loop and switch on `fieldProbe` only while you are looking.

## Performance notes

- **Read-back memory.** Both probes' read-back buffers are allocated as
  **host-cached** (`streamReads()`), which is the memory type CPU reads want on a
  discrete GPU. A host-visible-but-uncached (write-combined) buffer maps fine but
  its CPU reads are pathologically slow — the classic slow-read-back trap. If you
  build your own read-back buffer, ask for the same: `storage(); streamReads()`.
- **Throttling.** `fieldProbe` reads back off the render thread and only as often
  as `maxDisplayHz`; raising it makes the card livelier at the cost of more
  read-backs. `fieldStats` reads back once per frame when
  `cpuSnapshotsEnabled` is true — set it to `false` when only the GPU consumes the
  results.
- **Reservoir size.** `fieldProbe`'s `samples` is the number of `vec4`s copied
  back per snapshot. A few thousand is plenty for a histogram; there is no benefit
  to sampling the whole population.
- **Dropped partials.** If `fieldStats` reports `droppedPartials > 0`, its partial
  buffer overflowed — raise `maxPartials`.
