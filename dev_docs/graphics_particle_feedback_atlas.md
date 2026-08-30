# Particle feedback atlas: the `dust_four` experiment

`dust_four` closes a GPU-only loop between the existing particles and the fifteen projected scenes.
Particles already carry a stable source-cell assignment. Once per rendered frame, a compute pass
projects each particle into that cell, deposits density and projected velocity into a small UV tile,
and resolves the deposits into a persistent field. Splats, splat rectangles/outlines, particles,
edges, and MXG geometry can all sample that same field.

The experiment deliberately does not add another video, splat, or MXG decoder. It reuses the shared
particle state and matrix palette and adds two device-local buffers, about 2.4 MiB in total at the
default `96 x 54 x 15` layout:

- accumulation: scalar integer weight, weighted U velocity, weighted V velocity, and count;
- atlas: float density, U velocity, V velocity, and memory.

The scalar accumulation members are important on MoltenVK. Integer atomics on components of an
`ivec4` are valid Vulkan GLSL, but SPIRV-Cross can translate them into an address-of a Metal vector
component. Metal rejects that form; four named scalar members generate legal atomic pointers.

## Frame order

The `dust - particle feedback atlas` box installs three compute meshes with explicit barriers:

1. clear the integer accumulation buffer;
2. scatter the current particle positions and UV velocities into four bilinear neighbours;
3. resolve deposits plus previous atlas memory into the float atlas;
4. expose the atlas to graphics after a compute-to-graphics barrier.

Launch it after `dust - forces`, because its `state` binding reads the positions integrated on this
render frame. It moves `dust-history` back to the tail of the compute list so trail capture observes
the same state. The correct source projection flips Y: image coordinates increase downward while
camera/world Y increases upward.

The prerequisite `dust - surface interaction` pass must itself be launched after MXG: it inherits
the presented MXG snapshot and tree-warp state used for particle anchors. A missing dependency can
look like a feedback-atlas fence stall because both occur before the next completed frame. Verify a
native frame after the surface pass before attributing a later failure to feedback.

Retired score channels are cheap. The scatter checks the palette's particle-influence lane before
performing any atomics, so `score.only(...)` reduces useful feedback work alongside decoder load.

## Live-coded controls

The Field box owns all artistic values and registers them with the cue keyframe scope:

- `deposit`: density contributed by each particle;
- `velocity`: gain applied to per-frame projected UV motion;
- `radius`: how broadly the fixed four bilinear taps share each deposit (it never increases the
  atomic count);
- `memory`: previous-frame retention;
- `density response`: compression from accumulated weight to density;
- `velocity response`: how rapidly new motion replaces remembered motion;
- `max UV step`: clamp against projection discontinuities;
- `light`: emitted light where particle density is present;
- `erase`: exponential removal of the consumer's ordinary appearance;
- `UV warp`: offset video sampling by the remembered UV velocity;
- `threshold`: density below which the feedback is ignored.

The helper classes in `src/personal/kotlin/drowning/DrowningFeedback.kt` describe the buffers and
carry the four mutable look values. The cue allocates that zeroed storage before MXG/splats launch,
which breaks the launch cycle without adding a decoder. The compute shader and every interpretation
remain in the document's GLSL, where they can be reloaded during rehearsal.

Do not leave `fieldProbe` in the full resolve loop. At the default resolution that adds 77,760
diagnostic writes every display frame to a pass whose output is already visible in six live-coded
consumers. Add a sparse probe temporarily when diagnosing the atlas, then remove it for performance.

## Durable automation edits

For a running saved document, use the backdoor's acknowledged setter:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --port 9092 --attach-existing \
  --workspace /Users/marc/Documents/AFieldWorkspace --document dust_four \
  --command 'set dust_four_feedback compute --file /path/to/feedback.glsl'
```

This is stronger than editing a sidecar behind a running Field instance: it updates Field's live
property and waits for its metadata/sidecar persistence path. A direct file edit can lose a race with
the value already held by the open document and appear to work only until reload.

## Consumer contract

Consumers receive the atlas buffer, `vec4(width, height, cellCount, 0)`, and
`vec4(light, erase, warp, threshold)`. Sampling uses the same assigned or projected cell UV as the
video. Edges perform their existing full world-space projection against all eligible cells, sample
feedback per valid projection, and discard projected-only fragments when no consensus projector
exists. This preserves the black-background projected-light mode rather than inventing a nearest
screen approximation.

Keep the atlas modest. A manually bilinear buffer lookup is four device reads at every sampling
site; increase resolution only after profiling the finished material. The current layout favors a
coarse, persistent, visibly malleable field over geometric precision—which is appropriate for the
piece's intended visual soup.
