# GPU stigmergy field (`TrailField`) and GPU Physarum

Status: landed from nightly 2026-11-05-1. Sources:
`src/jvmMain/kotlin/afield/simulation/agents/alife_trail.kt` (`TrailField`, `TrailFieldSettings`,
`TrailShaders`), `alife_physarum_gpu.kt` (`ShaderPhysarumSystem`, `ShaderPhysarumSettings`), the smoke
`src/jvmMain/kotlin/afield/examples/four/PhysarumGpuSmoke.kt` (`alife.physarum-gpu.smoke`), the demo
transcript `dev_docs/agents/transcripts/gpu_physarum.yaml`, and the user page section
`public/docs/alife_examples.md` §7 "A million agents on the GPU".

## Why

Trail/pheromone fields kept being hand-built: the personal `DustPheromoneField`
(`src/personal/kotlin/drowning/DustPheromone.kt`, ping-pong cube-atlas shells with half-lives, wind,
diffusion, particle deposits and hill-climbing) and the CPU-only `PhysarumSwarm`. `TrailField` is the
general 2D part of that, public and reusable. The public code does not depend on `src/personal`.

## Design

- One RGBA16F `PingPongRenderToTexture` (`colorTransferSrc` on, for readback). Toroidal.
- Frame = the `evolve` pass (first module): sample previous frame at `p - windPx` (bilinear, in GLSL),
  `mix(centre, 3x3 mean, diffusion)`, times per-channel decay from `halfLifeFrames`
  (negative holds, zero clears, as in DustPheromone). Then deposit modules, additive, in insertion order,
  with the previous frame bound to `trailTex` (`depositParameters` / `addDeposit` / `removeDeposit`).
- `clear()` zeroes for `frameCount` frames so no history slot keeps old trail.
- `read()` -> `CompletableFuture<Img>` of raw floats (4 channels, row 0 = pixel row 0).
- `#include "trail/sample"` (registered in `builtInShaderInclude`, `shaders.kt`): `trailTexel`,
  `trailAt` (wrapping bilinear), `trailGradient`, `trailClip`.
- `previewInto` tone-maps `palette[c] * (1 - exp(-exposure * trail.c))`.

`ShaderPhysarumSystem` follows `ShaderDlaSystem`: agents (pos, heading, rng; 16 B) in an addressable
buffer, one vertex-shader invocation per agent = sense/turn/move/write-back/deposit, a buffer barrier
before the pass, `attachShader` into the host box. 1-4 species, one per channel; sensing weight is
`+1` own channel, `-repulsion` the others. `sensing = false` is the random-turn control arm. No
mutual exclusion (the CPU model's key rule); at ~1 agent/pixel density does that job.

## Gotcha found on the way

Field's render targets use a flipped viewport. A point placed with `clip = uv * 2 - 1` lands on row
`h - 1 - y`; `trailClip` negates clip y. Before the fix, sensing read a field mirrored against the one
being deposited and the network grew mirror-symmetric in both axes. The smoke's `placement` check
pins it (one still agent at (40, 100) must brighten pixel (40, 100) of the readback).

## Measurements (Apple-silicon Mac, KosmicKrisp default driver)

`alife.physarum-gpu.smoke`, 1024x1024, 1M agents, 600 frames, CPU-default parameters:

| run | topShare(0.05) | ms/frame |
| --- | --- | --- |
| sensing, 1 species | 0.752 | 8.4 |
| blind control | 0.065 | 8.3 |
| 2 species (per channel) | 0.718 / 0.711 | 8.3 |

Not directly comparable to the CPU page's 0.480 / 0.185 (6000 exclusive agents, disc start, step 400).
TrailField checks: hold keeps 1.0, a 10-frame half-life gives 0.465 after ~11 frames, zero clears,
`clear()` empties.

## Open / next

- A canonical_graphics lesson entry (needs a box in `public/docs/assets/canonical_graphics/metadata.edn`);
  not done.
- A compute-shader variant (true `computeModuleHere` pass) if agent counts beyond ~4M need it; the
  vertex-shader pass is fine at 1M.
- Non-toroidal edges, a gradient-following particle example (DustPheromone's hill-climbing) on
  `trailGradient`, 3D/shell fields.
