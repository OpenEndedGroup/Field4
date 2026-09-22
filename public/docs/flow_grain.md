# Flow grain and motion

Open the **flow_grain** starting point and launch its numbered boxes in order. A small
fluid moves through a bounded pool of world tiles. The image combines a short integral
of random grain with finite particles drawn as thin velocity strokes. A rotating marker
also writes previous-frame coordinates into a second render-target attachment.

[Open the editable starting point](offline.html?file=assets/agent_transcripts/flow_grain/metadata.edn).
The installer is `dev_docs/agents/transcripts/flow_grain.yaml`; running that macro in an
empty document creates and launches the complete graph. It has no personal-source dependencies.

Each stage is an ordinary box: edit its shader, reload it, and inspect the result. The
fluid storage helper is another editable box. Small reusable functions are included
from `afield.four.flow.FlowShaders`; the render targets, dispatch order, barriers,
lighting and artistic parameters remain in the graph.

```diffstatic_runner {data-lang=kotlin screenshot="assets/flow_grain/flow.png" capture="native-window" native-wait-frames="24" embed=true hidden=true timeout="180" name="flow-grain-playground" alt="World-space grain curls around obstacles; thin velocity strokes cross the flow and a rotating white marker writes motion coordinates." macro-file="../../dev_docs/agents/transcripts/flow_grain.yaml" capture-box="flow_renderer"}
// The transcript launches the real fluid, editable grain shaders and motion RTT.
```

## Things to try

| Box | Experiment |
| --- | --- |
| Parameters | Set `travel.set(true)` and execute. The camera crosses tile boundaries and changes zoom. |
| Parameters | Change `grainCell.requested` for finer grain or `strokeTime.requested` for longer flow lines. |
| Parameters | Set `force.requested = 100`. Inspect the returned snapshot: requested is 100, effective is 12. |
| Reset | Execute repeatedly. Simulation contents restart at a render-frame boundary without replacing the renderer or buffers. |
| Present color or motion | Set `flowLook.w` to 3 for previous UV or 4 for amplified displacement. The moving marker differs from the stationary world. |
| Profile | Launch a repeatable reset, warm-up and 180-frame camera path, then inspect the existing profiling timeline. |

Use Field's normal **Stop Box** and **Launch Box** controls. Stop the renderer to stop
rendering; stop a stage to remove it from the pipeline. Relaunch stages in their numbered
order after rebuilding the resources box. Shader reloads preserve the resources.

Stopping a stage detaches its work so you can edit and relaunch it. Closing the document's
last session also closes its native renderer. Shutdown waits for the device, drains
pending releases and disposes registered buffers, textures and shader resources;
it does not need to wait for the JVM to collect the document's objects.

## Resident world tiles

`afield.stdlib.TileResidency` maps world tile coordinates onto a fixed number of slots.
It floors negative coordinates correctly, retains overlapping tiles during movement,
and increments a slot generation whenever the slot is recycled. A teleport costs the
same bounded scan as ordinary movement. It does not allocate a larger world.

```diffstatic {data-lang=kotlin}
val tiles = TileResidency(columns = 5, rows = 5, tileSize = 4f)
val metadata = IntArray(tiles.capacity * 4)
tiles.moveTo(cameraX, cameraY)
for (i in 0 until tiles.changedCount) {
    val slot = tiles.changed[i]
    // Clear or regenerate this slot's simulation contents.
}
tiles.pack(metadata)
```

The packed record is `(worldX, worldY, generation, occupied)`. A shader must check the
resident world coordinates before sampling a slot: modulo addressing alone accidentally
joins opposite edges of the pool. The demo also tags each cell's contents with its
generation, so a recycled tile cannot read fluid left by its previous occupant.

`afield.four.flow.TileAtlasLayout` adds atlas coordinates and optional halo storage for
texture-based solvers. A halo must be filled from a resident neighbour, or from your
chosen outside boundary condition. The helper does not decide what an unloaded world
means. The demo uses storage buffers and explicit neighbouring-cell lookup instead.

The arrays returned by these helpers are reusable, borrowed storage. Read/update them
on the owning simulation thread. This is residency and addressing infrastructure, not a
second fluid engine: it can also organize particles, density, masks or lighting caches.
For a ready-made fixed-domain solver, see [Fabricate Renderer](fabricate_renderer.md).

## Correspondence has an identity

Use the existing `afield.four.targets.FrameHistory` for camera history. Capture once per
**rendered frame**, using `Renderer.frameCounter`, target dimensions and a scene epoch.
A missed frame, resize, epoch change or explicit invalidation makes history unavailable.
The packed buffer contains four matrices and a viewport vector: 68 floats.

`MotionItems` adds object history keyed by stable identity. A draw-list index is not an
identity: sorting, culling or deleting another object must not transfer its history.

```diffstatic {data-lang=kotlin}
val history = FrameHistory()
val objects = MotionItems()
val pose = MotionPose()
val previous = FloatArray(8)

// Once per render, after updating pose in place:
objects.begin(renderer.frameCounter)
objects.write("emitter", pose, previous, allow = history.available, revision = geometryRevision)
```

Each object writes two `vec4`s: previous center, relative rotation/scale, validity,
previous energy, previous softness and a reserved scalar. Change `revision` when topology
or material correspondence changes. Disappearing for a frame, reappearing after a gap,
resetting or rewinding also invalidates history.

The demo RTT's second attachment is `(previousU, previousV, valid, coverage)`. These are
absolute texture coordinates with Field's top-left convention, **not** pixels per second.
The world has camera correspondence; the rotating marker has camera plus object
correspondence. Procedural grain is a changing signal carried by the world, so its
camera correspondence is not a claim that every stochastic detail existed last frame.
The overlaid finite strokes are intentionally outside this attachment; a production
particle pass should supply its own previous positions and coverage.

Use a floating-point attachment, disable mixing of unrelated motion samples, and keep
validity explicit. A transparent composite may contain several moving layers and has no
single exact answer. Choose a layer or retain separate correspondence buffers.

## Grain and strokes

The packaged helpers are available during editing and compilation, before any box runs.
Use Field's normal includes:

```diffstatic {data-lang=glsl}
#include "flow/grain.glsl"
#include "flow/strokes.glsl"
#include "flow/motion.glsl"
#include "flow/tiles.glsl"
```

The grain helper asks the box to provide `flowVelocity(world)` and `flowMask(world)`.
It integrates noise along short streamlines, stopping at a blocked sample. Lighting is
applied **after** integration at the current surface point, preserving geometric shadow
boundaries instead of gathering brightness through a wall. Finite sampling can miss an
obstacle thinner than a step; use a segment mask or smaller steps where that matters.

Noise lives on fixed world grids. Pixel footprint selects and blends adjacent fixed
scales; changing zoom never rescales noise around the world origin. If you mix motion
from an image into the carrier, `flowImageVelocity` unprojects current and previous
samples through their respective cameras. Pass the fetched motion texel's center,
not the requesting pass's differently sized pixel coordinate.

`flowSmoothVelocity` uses exponential time-based smoothing. `flowStrokeClip` projects
the stroke tangent before constructing its screen normal: falling particles become
vertical strokes, including under camera rotation. Length is in world units; width is
in pixels. The vertex and fragment shaders remain editable in the strokes box.

## Reset, parameters and measurement

`SimulationReset.request()` queues a ticket. The owner consumes it with
`atFrame(frameId) { ... }`. Requests coalesce; a request arriving during reset remains
pending for the next frame. The callback decides what state to clear. Successful resets
replace the epoch token and acknowledge the ticket. Failures are reported and consumed,
without an automatic retry or an implied rollback.

The demo resets tile generations, time and correspondence. GPU resources stay outside
the callback. Keep this boundary inside the normal box-owned loop or render hook: it
is not an alternative launch service.

`EffectiveParameter` makes a finite scalar's requested value, effective clamped value,
bounds, units and description inspectable. It is explicit: it cannot discover clamps
buried inside unrelated code or shaders. The parameters box returns these snapshots
through Field's existing object viewer, and shader push constants read the effective values.

The profile box uses `frameProfile`, named scopes and `CoroutineProfiles`. CPU time and
allocated bytes are exact counters on the measured thread; JFR stacks are statistical
attribution. GPU pass timings remain in the renderer profiler. Compare the same reset,
warm-up, camera path and parameters, and keep capture overhead consistent. See
[Profiling Timeline](profiling_timeline.md) and [Holding a Frame Budget](frame_budget.md).

To include GPU queries, set `Options.profiling = true` in the renderer box before
restarting the numbered graph. The existing renderer profiler reports frame and module
timings; support for pipeline statistics varies by device. Keep that setting the same
for both sides of a comparison. The Profile box captures CPU/allocations; it does not
silently recreate the renderer to switch GPU queries on.
