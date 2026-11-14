# OIDN as Vulkan compute: port spike (2026-11-14)

Follows [`OIDN_SPIKE.md`](OIDN_SPIKE.md). The direction is now the compute port: OIDN's RT network runs as
Field's own Vulkan compute shaders, over the same weights `oidn-webgpu` uses. Native OIDN is kept only as a
yardstick.

**Result:**

- **Works:** the port runs on the path tracer and denoises inside the frame into a texture.
- **Matches native OIDN:** 76.6 dB PSNR against native OIDN on identical inputs; mean display-space difference
  1.0e‑4 (one 8-bit level is 3.9e‑3).
- **Speed:** about 0.71 s per 1080p denoise on the M1 Max with KosmicKrisp, which is 11.6× slower than native
  OIDN Metal (61 ms) and 1.4× slower than native OIDN CPU (~495 ms). That is fine for "denoise once when the
  trace settles", but not yet interactive.

Nothing is landed or committed. Files:

- `src/jvmMain/kotlin/afield/media/reconstruction/OidnCompute.kt`: `.tza` loader and `OidnComputeDenoiser`.
- `OidnComputeSpike.kt`: the harness.
- A one-line `internal val accumulationBuffer` added to `PathTracingRenderer`.
- Captures and JSON in [`oidn_compute/`](oidn_compute/): `quality.json` from the full run, `timing.json` from
  the timing run of the code as it now stands.

## What was built

`OidnComputeDenoiser(tracer, tracer.enableFrameOutput(), "rt_hdr_alb_nrm.tza")` is a `Target` installed after
the tracer. Everything runs on the GPU; nothing is read back to denoise.

| Pass | What it does |
| --- | --- |
| `aux` (every traced frame) | Running mean of primary albedo (diffuse + specular, clamped) and normal, read straight from `PathTraceFrameOutput`'s packed per-pixel buffer by buffer reference, in step with the tracer's sample count. So the guides average the same jittered samples as the colour. |
| `autoexposure-bins`, `-reduce` | OIDN's autoexposure: mean luminance per (up to) 16×16 bin, geometric mean of the bins above 1e‑8, key 0.18. It matches native OIDN exactly at 8 spp (identical output with the scale forced) and to 3e‑4 at 32/128 spp. |
| `input` | PU transfer of `color × scale`, albedo clamped to [0,1], normals mapped to [0,1]. Nine channels in three fp16 chunks, image padded to a multiple of 16 with zeros. |
| 16 × conv | The small OIDN UNet (`enc_conv0 … dec_conv0`, 32–112 channels). 3×3 conv + bias + ReLU. Nearest **upsample**, the **concat** with the skip connection, and 2×2 **max-pool** are folded into the conv that consumes or produces them, so they cost no extra passes or memory. |
| `output` | Inverse PU and `/ scale`, into a linear vec4 buffer. |
| present | Copies that buffer into a `RenderToTexture` (`den.output`), the same way `PathTraceFrameOutput` fills its images. A box can show it. Reading the texture back gives exactly the buffer (max difference 0). |

Choices:

- **Hand-written GLSL, not WGSL → SPIR-V.** oidn-webgpu does not ship fixed kernels. Its 3,700-line planner
  generates WGSL per layer, precision and feature path, so there was nothing stable to put through naga or tint.
  Writing the graph and the five kernel kinds directly in Field's `ComputeModule` style was shorter: about 400
  lines in total, with `.tza` parsing in about 25 lines of Kotlin.
- Each pass is a `ComputeModule` with one `ComputeMesh`, all buffers passed as buffer references in push
  constants, and a compute→compute barrier between passes.
- **Activations are fp16**, packed with `packHalf2x16`, because Field's device does not enable
  `storageBuffer16BitAccess`. Arithmetic and weights stay fp32. The weights are 1.8 MB of fp16 in the `.tza`.
- **Memory:** 871 MB of activations at 1080p, about 830 MB of it full-resolution tensors that are never reused.
  Aliasing dead tensors would roughly halve this. Construction, including compiling 21 modules, takes 0.4 s.
- **Attribution:** the network topology, `.tza` format and input/output conventions follow oidn-webgpu (MIT)
  and OIDN (Apache-2.0); the file header says so. The kernels are new.

## Quality

The scene, tracer and 1024 spp reference are the same as in `OIDN_SPIKE.md` (KosmicKrisp, `COMPUTE_BVH`,
1920×1080). PSNR is on display values (clamped, sRGB) against the reference.

| spp | noisy | **Vulkan compute** | native OIDN CPU, same inputs | Vulkan vs native |
| --- | --- | --- | --- | --- |
| 8 | 29.51 dB | **38.12 dB** | 38.13 dB | 76.6 dB, mean diff 1.0e‑4 |
| 32 | 30.76 dB | **38.50 dB** | 38.50 dB | 76.6 dB, mean diff 1.0e‑4 |
| 128 | 33.30 dB | **38.71 dB** | 38.71 dB | 76.6 dB, mean diff 1.0e‑4 |

![Rows 8/32/128 spp; columns noisy, Vulkan compute OIDN, native OIDN CPU, 1024 spp reference](oidn_compute/crops-montage.jpg)

Full frames: [noisy 8 spp](oidn_compute/noisy-8spp.jpg),
[Vulkan 8 spp](oidn_compute/vulkan-8spp.jpg) (read back from `den.output`),
[native CPU 8 spp](oidn_compute/native-cpu-8spp.jpg), [Vulkan 32 spp](oidn_compute/vulkan-32spp.jpg),
[reference](oidn_compute/reference-1024spp.jpg).

- **Largest difference:** 0.68 in linear HDR at 8 spp, on the directly visible emitter (radiance 40), where
  fp16 activations and the PU curve's steep top meet.
- **No port-specific artefacts:** a difference of 1e‑4 cannot be seen. The Vulkan and native columns show the
  same smoothing, fireflies removed and highlights kept.
- **oidn-webgpu itself was not run** on these inputs. Native OIDN is the stronger yardstick, since oidn-webgpu
  is validated against it.

## Speed, 1920×1080, M1 Max, KosmicKrisp

| Measure | ms |
| --- | --- |
| **Denoise, fence-timed submit (median of 15)** | **708** (min 672) |
| Same, steady-state frame with tracer paused: with denoise / without | 597 / 8.3 |
| Native OIDN Metal (`OIDN_SPIKE.md`) | 61 |
| Native OIDN CPU | ~495 |

GPU timestamps (`LightingGpuTimer`) read 0 ms around every pass and around the whole network. KosmicKrisp
evidently doesn't separate dispatches inside one encoder, so all figures are wall time around a fence
(an empty submit costs 0.04 ms).

Per pass, each submitted alone, in ms:

| Pass | ms |
| --- | --- |
| dec_conv1a | 226 |
| dec_conv2a | 97 |
| dec_conv1b | 93 |
| enc_conv1 + pool | 48 |
| dec_conv2b | 47 |
| dec_conv3a | 45 |
| dec_conv3b | 27 |
| enc_conv2 + pool | 19 |
| enc_conv0 | 18 |
| dec_conv4a | 15 |
| dec_conv0 | 12 |
| dec_conv4b | 10 |
| enc_conv3 + pool | 10 |
| everything else | under 5 each |
| input, output, autoexposure (together) | 1.6 |

The network is 262 GMAC at 1088 rows. 708 ms is 0.74 TFLOPS, about 7% of the M1 Max's fp32 peak. Native OIDN
Metal (MPSGraph) reaches the equivalent of 8.6 TFLOPS.

Two kernel revisions were measured and both were **slower** on KosmicKrisp, so neither was kept:

- 16 output channels per invocation: 1.51 s.
- Two input chunks staged per barrier, with array-indexed accumulators: 1.10 s.

The one kept stages one 4-channel chunk per barrier and computes 2×2 pixels × 8 channels per invocation in
explicitly named accumulators.

## What's left before productising

Roughly in order of value.

1. **Speed (the main gap).** The convs are compute-bound at about 7% of peak, so the kernel is where the gains
   are.
   - **Implicit-GEMM tiling:** a register-blocked GEMM over (pixels × output channels) × (input channels × 9),
     with weights staged once per tile rather than per chunk.
   - **fp16 arithmetic** (`shaderFloat16` is enabled), which halves register pressure.
   - **Winograd F(2×2,3×3)** for the full-resolution layers (2.25× fewer multiplies).
   - **Profiling per driver:** the measured revisions show KosmicKrisp's compiler is sensitive to indexing
     style. MoltenVK may differ and was not measured.

   Neither Mac driver exposes cooperative matrix, so reaching native's ~60 ms is unlikely. 150–250 ms looks
   reachable; that is an estimate.

   On NVIDIA, `VK_KHR_cooperative_matrix` and the device's existing cooperative-vector support give a separate
   fast path.
2. **Tiling** (oidn-webgpu's `maxTileSize`) to bound memory at 4K. The network's receptive field means tiles
   need about a 64-pixel apron. Alias dead activation buffers in the same change.
3. **API shape:**
   - **Trigger:** `tracer.denoise()` / `denoiseWhenSettled(spp)` on `PathTracingRenderer`, or a
     `PathTracedReconstruction`-style view enum with a "denoised" view, instead of the spike's `request()`.
   - **Guides:** should come from the tracer itself rather than through `enableFrameOutput`, which refuses glass
     and media. The guides are what limit this to opaque scenes, not the network.
   - **Accessor:** replace `accumulationBuffer` with a proper accessor.
4. **Weights:** ship `rt_hdr_alb_nrm.tza` (1.8 MB, Apache-2.0) in Field's own resources. It currently lives
   only on the unmerged `claude/three-webgpu-spike` branch.
   - **Variants:** `rt_hdr` (no guides) and `rt_ldr*` load through the same code. The large UNet (`rt_*_large`)
     needs only its graph list.
   - **`cleanAux`:** `cleanAux` prefiltering would reuse the same network.
5. **Tests:**
   - **Fixture:** a small-resolution native-OIDN-parity fixture, a jvmTest with committed inputs and native
     output, fed through the GPU path. This retires the native library entirely.
   - **Smoke:** a GPU smoke beside `PathTracedReconstructionSmoke`.
6. **Watchdog:** a 0.7 s submit is safe, but at 4K an untiled denoise would be about 2.8 s in one command
   buffer. Tile or split before the Metal command-queue timeout.

Native OIDN code (`OidnSpike.kt`, its FFM binding) stays as a test yardstick only. No download task was added.
