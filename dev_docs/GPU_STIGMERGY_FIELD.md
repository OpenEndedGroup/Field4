# GPU stigmergy field (`TrailField`) and GPU Physarum

Status: landed from nightly 2026-11-05-1. Sources:
`src/jvmMain/kotlin/afield/simulation/agents/alife_trail.kt` (`TrailField`, `TrailFieldSettings`,
`TrailShaders`), `alife_physarum_gpu.kt` (`ShaderPhysarumSystem`, `ShaderPhysarumSettings`), the smoke
`src/jvmMain/kotlin/afield/examples/four/PhysarumGpuSmoke.kt` (`alife.physarum-gpu.smoke`), the demo
transcript `dev_docs/agents/transcripts/gpu_physarum.yaml`, and the user page section
`public/docs/alife_examples.md` §7 "A million agents on the GPU".

## Why

Trail/pheromone fields kept being hand-built: a private pheromone field
(ping-pong cube-atlas shells with half-lives, wind, diffusion, particle deposits and
hill-climbing) and the CPU-only `PhysarumSwarm`. `TrailField` is the general 2D part of that,
public and reusable, and the public code depends on nothing private.

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

## Multi-species, exclusion and followers (nightly 2026-11-06-1)

Sources added: `alife_trail_followers.kt` (`TrailFollowers`, `TrailFollowerSettings`); demo
`dev_docs/agents/transcripts/gpu_stigmergy.yaml` (+ `docs_capture/gpu_stigmergy_followers.yaml`);
user section `alife_examples.md` §7 "Several species on one field".

- `TrailFieldSettings.channelDiffusion: Vec4?` — per-channel diffusion, overrides `diffusion`.
  The evolve push block's `diffusion` is now a `vec4`.
- `ShaderPhysarumSettings.attraction: List<Vec4>?` — species x channel sensing matrix (row s = how
  species s weighs channels). `null` = old behaviour (+1 own, -repulsion others in use).
- `ShaderPhysarumSettings.depositInto: List<Vec4>?` — species x channel deposit matrix (times
  `deposit`). `null` = own channel only. Both are push `mat4`s (column s = species s), read every frame.
  Push block is now ~190 bytes: fine on Apple/NVIDIA/AMD (>=256), over the 128-byte Vulkan minimum.
- `ShaderPhysarumSettings.exclusive` — GPU Jones exclusion. `occupancyBuffer` holds one uint per
  pixel; each frame an agent `atomicMax`es the frame stamp into its own pixel, then into its target;
  if the target already had this stamp the move is refused (stay, random heading). Blocked agents
  still deposit, as the CPU model does (an earlier cut that withheld the deposit measured 0.337
  against the CPU's 0.484). Approximate: claims race within a frame, and a leaving agent holds its
  old pixel for that frame.
- `TrailFollowers(trail) { weights; steering; threshold; gradientPx; damping; maxSpeed; wander }` —
  particles (pos, vel; 16 B) stepped in a vertex-shader point draw that reads `trailGradient` of
  `dot(trail, weights)`; drawn alpha-blended over the preview (additive pale dots vanished on the
  bright roads they climb).
- `Box.attachShader` (shaders.kt) now seeds `vertex`/`fragment`/`compute` unless the box has its OWN
  source; an inherited tab no longer counts. Before, a TrailFollowers box beneath a Physarum box drove
  its module with the parent's agent shader (MissingRefException `agentBuffer`, renderer stall).

### Measurements (`alife.physarum-gpu.smoke`, Apple silicon, KosmicKrisp)

| run | result |
| --- | --- |
| segregation, 2 species, 250k on 512², 600 frames | 0.994 (area 0.52 / 0.48) |
| segregation, 3 species | 0.866 (0.32 / 0.31 / 0.37) |
| segregation, shared attraction control | 0.000 |
| CPU `PhysarumSwarm` 6000 on 256², disc 0.55, 400 steps: sensing / blind / no exclusion | 0.484 / 0.185 / 1.000 |
| GPU same setup, exclusive: sensing / blind / no exclusion | 0.546 / 0.222 / 0.998 |
| GPU exclusive, 150k on 1024² (15 %), 800 frames | topShare 0.296, cellular branching net |
| followers on a static ring (256², 4000) | mean distance 37.7 px -> 0.4 px |
| followers in the live demo (probe) | 19368 / 20000 on amber-dominant pixels (42 % of area) |

Segregation = share of pixels with total trail > 0.5 x mean whose top channel holds >= 80 %.
All runs ~8.3 ms/frame (the smoke pumps a shared window; per-run cost is dominated by present).

## Open / next

- A canonical_graphics lesson entry (needs a box in `public/docs/assets/canonical_graphics/metadata.edn`);
  not done.
- A compute-shader variant (true `computeModuleHere` pass) if agent counts beyond ~4M need it; the
  vertex-shader pass is fine at 1M.
- Non-toroidal edges, 3D/shell fields. (The gradient-following example is `TrailFollowers`.)
- Followers that also deposit (become a species of their own); a predator/prey measurement for the
  chase matrix (the segregation metric does not apply when one species writes another's channel).
- `attachShader` still lets an inherited `vertex_fragment` (shared) tab win; only per-stage tabs were fixed.
