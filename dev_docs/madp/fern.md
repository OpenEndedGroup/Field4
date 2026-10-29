# Fern → Field·4 (Vulkan)

Port of `~/temp/aaaframe/madp/fern.html`: a `system` object (`initParticle`, `step`, `project`,
`shouldDraw`, `color`, `pointSize`, plus `viewport`, `background`, `suggested{fps, particles,
steps, fade}`, `dt`) whose particles are stepped and plotted every frame into a canvas that fades
toward the background. Presets: Barnsley fern (chaos-game IFS), Lorenz attractor (ODE with a 3D→2D
projection), flame-style IFS (affine map then a nonlinear variation).

## Shape in Field

One renderer box, then one box per system. Each system box is the demo's `system` object split in
two, the way a Field graphics box is always split:

- **`compute` property (GLSL)** — the per-particle functions, written with the same names and in the
  same order as the JS: `initParticle`, `step`, `project`, `shouldDraw`, `color`, `pointSize`. The
  first line `#include "points/system"` supplies the `State` struct, the seeded `random()`, the
  CSS-style `rgba(...)` / `hsla(...)` colour helpers (sRGB in, linear out), `clamp`/`mix` (GLSL's
  own, standing in for `lerp`), the flame `variations` (`linear`, `swirl`, `spherical`,
  `handkerchief`) and the `main()` that steps each particle `steps` times, plots every step, and
  respawns non-finite particles. The harness also advances `age`, so `step` need not.
- **`code` property (Kotlin)** — the rest of the object: `name`, `viewport(xMin, xMax, yMin, yMax)`,
  `background = "#010301"`, `particles`, `steps`, `fade`, `fps`, `dt`, as settings of an
  `IteratedSystem`, plus sliders for fps / steps / fade / exposure (the demo's toolbar).

`State` is fixed at two `vec4`s: `vec3 pos`, `float age`, `vec4 data` (the preset's own fields —
the fern's `branch`, the flame's `mix` — live in `data`, named in a comment). The JS object could
carry any fields; a GPU buffer needs a stride known on the Kotlin side, and two vec4s cover all
three presets.

Launching a system box replaces whichever system was showing (all of them use the same keyed
module names), so the boxes play the role of the demo's preset menu, and editing a box's `compute`
tab hot-reloads the shader as Ctrl+Enter recompiles the JS.

## Shared code (owned here)

1. **`PointAccumulation`** (`afield.graphics.vulkan.targets`) — "these GPU points accumulate into
   this target with this fade". A float16 `PingPongRenderToTexture`; each frame a fullscreen pass
   carries the previous frame forward multiplied by `1 - fade`, then point modules draw on top with
   **additive** or **alpha (premultiplied over)** blending; the display pass composites onto
   `background` with a tone map (`LINEAR`, `EXPONENTIAL` = `1 - exp(-x)`, `REINHARD`, `LOG` for
   flame-style log density) and `exposure`. It owns a generic point splatter that reads a buffer of
   `AccumulatedPoint { vec4 position /* world xy, size px, weight */; vec4 color }` records and maps
   a world `viewport` to the target (optionally keeping aspect). Anything that can write that
   buffer — Fern's compute pass now, Particles' trails in wave 2 — gets accumulation for free;
   modules that want their own shaders get `pointParameters()` (the right target + blend state).
   It can be paused frame by frame (`advance`), which is how the `fps` control is honoured without
   slowing the renderer.
2. **`IteratedSystem`** (`afield.simulation.particles`) — the Fern host: allocates the particle and
   point buffers, adds a `ComputeMesh` to the box's `computeModuleHere {}` module with the push
   constants the include declares, gates stepping by `fps`, publishes the compute writes to
   graphics, and feeds `PointAccumulation`.
3. **`points/system` built-in shader include** (beside the `flow/` one), so the harness is packaged
   source with KDoc'd GLSL rather than text copied into every box.

## Kept, changed, dropped

- **Kept**: the six function names, the viewport, background, the four controls, the presets'
  coefficients and palettes verbatim (CSS colour strings become `rgba(140, 255, 190, 0.35)` calls),
  respawn of non-finite particles, plotting every step.
- **Changed**: particle counts go from 7 000 to hundreds of thousands — the reason to be on the
  GPU — so per-point alpha is lower and an `exposure` setting scales the dust. Blending is additive
  in linear float with a tone map rather than 8-bit source-over; the look (saturating glow on a dark
  ground) is the same, and density reads further before it clips. The viewport keeps its aspect
  (the canvas version stretches to the pane).
- **Dropped**: the Monaco editor, AI ghost completion, local saves, fullscreen button, the metrics
  card (Field's box output shows the counts instead).
- **Added preset**: the Sierpinski triangle, the smallest chaos game (one line of `step`),
  coloured by the corner of the last step so each third reads as a half-size copy of the whole.

## As built (2026-10-20)

- Particle counts: fern 200 000, flame 250 000, Sierpinski 150 000, but Lorenz **12 000**. At
  60 000 its trajectories put hundreds of hits on every pixel and the picture became a smooth
  pastel sheet that no exposure could recover. The original's look comes from the spirals of
  individual trajectories, and 12 000 keeps them visible. "More particles" is still the slider's
  lesson, but the demo's 1 600 were closer to right than the GPU's millions.
- Tone maps: `EXPONENTIAL` for the fern, Lorenz and Sierpinski; `LOG` for the flame, as fractal
  flames are drawn.
- `IteratedSystem` takes back the buffers and accumulation target of an earlier run of the same
  shape (name, particles, maxSteps, size, format, blend). Nothing in Field frees a
  `RenderToTexture` or a `FrameBufferGroup`, so without that every re-run left ~50 MB of GPU
  memory behind. The general fix (GPU resources as lifecycle claims) is still open.
- Capturing: `native-screenshot --wait-frames N` counts Runloop frames, which run well ahead of
  rendered frames on a loaded machine, and a system plots nothing until its particles are past
  `shouldDraw`'s age. A capture taken 60 frames after a launch was black and looked like a bug.
  Wait wall-clock time (`sleep 45`) before capturing.
