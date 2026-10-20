# Splat shader → Field·4: port design

Source: `~/temp/aaaframe/madp/splat.html` (served at `:9982/splat.html`). A Spark (three.js)
Gaussian-splat playground: a Monaco editor holds a GLSL *insert* that Spark pastes into a `dyno`
object modifier, so it runs once per splat per frame and may rewrite the splat's centre, radii,
colour and rotation. A dropdown picks one of six procedural clouds (or a dropped `.ply` / `.glb`),
another picks one of eleven example inserts. The viewer auto-fits the cloud (median centre, 90th
percentile radius scaled to ~1.08), auto-rotates it, and offers a "trail" mode that composites each
frame over a fading copy of the last. The look: bright translucent splats on near-black.

## The Field idea

Field already draws Gaussian splats on Vulkan (`GaussianSplatRenderer`: a `Cloud` sized by
capacity, a GPU depth sort, a quad-per-splat vertex shader). What it lacks is the demo's seam: a
place where a student writes *only statements* against `pos`, `scales`, `rgba`, `quat`, and where
those edits are seen by everything downstream, the sort included.

**The insert runs in a compute pass, not in the vertex shader.** The box's compute tab is the
student's insert bracketed by two includes:

```glsl
#version 450
#include "splat/insert"

float r = length(pos.xz);
pos.y += sin(r * 18.0 - t * 3.5) * 0.045;

#include "splat/end"
```

`splat/insert` declares the buffers and push constants, the helpers (`sat`, `rot2`, `hash31`,
`noise31`, `pi`) and opens `main()` with the locals the demo declares, read from a *source* buffer
of parametric splats (centre, opacity, colour, scales, quaternion). `splat/end` applies the demo's
post-clamp (`rgba` to 0..1, `scales = max(scales, 1e-4)`), turns `scales`/`quat` into the
covariance the renderer's vertex shader reads, writes the *evaluated* splat into the cloud's buffer
and closes `main()`. Errors in the student's lines report against the box's own line numbers,
because the includes are real `#include`s, not text pasted around the insert.

A box is then:

```kotlin
var module = computeModuleHere {}
SplatShader(module, ProceduralSplats.torusBraid()) {
    autoRotate = 0.32
}
```

`SplatShader` owns the source buffer, runs the box's compute module every frame ahead of the
cloud's sort, and installs one cloud under a fixed name, so launching another preset box replaces
the one showing (the demo's dropdown).

### Why compute and not the vertex shader

1. **The sort.** `GaussianSplatRenderer`'s depth-key pass reads each splat's centre straight from
   the packed buffer. An edit made in the vertex shader (the seam `gaussianSplatModuleHere` already
   offers) moves the quad but not its key, so a dissolve that throws splats outward is drawn in the
   order of where they *were*. Running the insert first and sorting the evaluated buffer makes the
   sort see post-insert positions by construction.
2. **Cost.** The vertex shader runs six times per splat (two triangles); the insert, noise and all,
   should run once.
3. **The layout.** The renderer's 16-float layout stores a covariance, not scales and a quaternion,
   so `scales *= …` and `quat` edits have nothing to act on there. The source buffer keeps the
   parametric form; the covariance is built after the insert, as Spark does.

### Fixes to `GaussianSplatRenderer` this needs (measured, then fixed at the source)

- **Sort keys from CPU bounds.** The depth key is the view depth normalised into the range of the
  cloud's CPU-side bounding box, clamped to 0..1. Splats an insert moves outside those bounds all
  collapse onto key 0 or 1. Add a `DepthKey.ViewDepth` mode keyed on the float bits of the view
  depth itself (monotone for non-negative floats), which needs no range at all.
- **Sorting only when the view moves.** `sortOnlyWhenViewChanges` defaults to true, so an animated
  insert under a still camera is never re-sorted. The insert path turns it off.
- **No hook ahead of the sort.** `Cloud` gains `beforeSort`, work recorded on the same command
  buffer before the depth keys, followed by a compute→compute and compute→graphics barrier on the
  splat buffer.
- A GPU smoke (`four.splat-shader.smoke`) measures the sort's agreement with post-insert depth on a
  cloud whose insert mirrors `z`: the old path should come out fully inverted, the new path exact.

## API mapping

| splat.html | Field |
| --- | --- |
| Monaco GLSL insert | the box's compute tab between `#include "splat/insert"` and `#include "splat/end"` |
| `pos scales rgba quat` (mutable), `index t` (read-only), `pi` | same names, same types (`index` a float, `t` seconds since the box ran) |
| `sat rot2 hash31 noise31` | same GLSL, verbatim from the demo |
| post-clamp | same, in `splat/end` |
| six packaged clouds | `ProceduralSplats.icosphereShell()`, `fernPlume()`, `nautilusShell()`, `coralBranches()`, `phyllotaxisBloom()`, `torusBraid()` → `SplatSet` |
| drop a `.ply` | `SplatSet.fromPly(path)` (via `readPlyAsFloatTable`, the same decode `GaussianSplatRenderer.pack` uses) |
| drop a `.glb` → disc per vertex | `SplatSet.fromGltf(path)` if the glTF loader makes it cheap; otherwise a gap |
| auto-fit (median centre, p90 radius → 1.08) | `SplatSet.fit()`, applied as the cloud's model matrix |
| auto-rotate + speed slider | `autoRotate` (same units as the demo's slider: ×0.01 rad per 60 Hz frame) |
| size slider | `size` (the root scale multiplier) |
| camera (fov 54°, at (0, 0.38, 2.65)) | the same, on the cloud's `Camera` |
| trail / fade | optional; see gaps |
| eleven example inserts | preset boxes (lead box = passthrough; 4–6 presets, dissolve and gravity well among them) |
| shader error panel | Field's own shader error reporting on the compute tab |

## Shared code (with KDoc and tests)

1. `afield.graphics.scene.fabricated.SplatSet` — CPU parametric splats, `push(...)`, `fit()`,
   `fromPly`.
2. `afield.graphics.scene.fabricated.ProceduralSplats` — the six builders, ported line for line
   (Spark's `constructSpherePoints` included, since the icosphere is built with it).
3. `afield.graphics.scene.fabricated.SplatShader` — the insert host; the two includes as packaged
   resources under `afield/four/splat/`, resolved like `flow/` and `points/`.
4. `GaussianSplatRenderer`: `DepthKey.ViewDepth`, `Cloud.beforeSort`.

Tests: a JVM test for the builders (counts, determinism, finite values) and the fit; the include
pair compiling with a sample of the presets; and the GPU smoke for the sort.

## Starting point

`madp_splat_shader`: a renderer box, a lead box (passthrough on the icosphere shell), and preset
boxes across the clouds — pulse rings, dissolve, gravity well, fern veins, lichen tangle, xray shell
— each launching into the same window.

## Dropped, and why

- Monaco AI completion, agent chat, local saves, "Copy LLM Intro": Field's own completion, AI4,
  documents and history, and the KDoc.
- SH coefficients: the demo does not expose them either.
- Spark's `SparkControls` orbit: Field's window camera; auto-rotate covers the demo's default.
