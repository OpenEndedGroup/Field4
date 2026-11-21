# The GPU denoiser on particle renders, and tiling (2026-11-21)

Question: what does `OidnComputeDenoiser` (OIDN's RT network as Vulkan compute,
[`OIDN_COMPUTE_PORT.md`](OIDN_COMPUTE_PORT.md)) do to the abstract images the `ps` document's
animated particle systems draw, and does it break tiling? The `ps` kaleidoscope pass samples its
particle texture with `fract()`, so that texture is used as a torus.

**Answer, from 10 frames of the `ps` render-window history:**

- **On nine of them it barely changes anything.** These images carry no Monte Carlo grain, and
  the network finds little to remove. Mean display brightness is unchanged (within 1 %), and the
  mean display change per channel is 0.0008–0.0016, under half an 8-bit level (0.0039).
- **It softens the finest strokes.** It removes 2–8 % of the fine-detail energy (RMS of the
  luminance Laplacian) with flat guides, and 11–14 % with the image itself as albedo, so a
  self-guide smooths *more*. The exception is v02859, a dense field of thin red trails: 24 %
  (flat) and 41 % (self) of its fine detail goes, because those trails are exactly the
  pixel-scale texture the network was trained to remove.
- **It breaks the wrap.** The network sees zeros past every edge, so opposite edges are
  denoised without each other. On the nine sparse frames the mean error at the edge pixel is
  3.5e‑5 to 8.8e‑4 (at most 0.22 of an 8-bit level), and it falls to the background level within
  about 8 px. Per pixel it is larger: where a bright stroke crosses the edge, the ×8 difference
  maps show spots of roughly one to two levels, a few pixels wide.
  - **On v02859 it is visible.** The mean error at the edge is 1.4–1.6 levels. It stays above
    half a level for 4 px (flat guides) to 9 px (self-guided) and fades over about 20 px, which
    shows up as a seam line in a tiled view.
- **A 64 px wrap-around pad fixes it.** Denoise the padded image and crop. The edge error then
  drops to each frame's own background (5e‑6 to 1.4e‑4; 4–6e‑4 on v02859), and matches the
  result of rolling the padded image first. The cost is a slightly larger image: 3968 × 2288
  instead of 3840 × 2160.
- **Cost at 3840 × 2160 on the M1 Max (KosmicKrisp, portable kernels):** 2.4–2.7 s per denoise
  (about 10 % more padded) as a sum of one-pass submits, which keeps every submit under the Metal
  watchdog. Scratch memory is 3.46 GB (3.79 GB padded).

## Numbers

Display units: sRGB in [0, 1], averaged over RGB.

- **Edge:** the mean |difference| at the outermost pixel between the plain denoise and the
  wrap-aware one. The wrap-aware result is the image rolled by half its size, denoised, and
  rolled back, so the original edges sit in the interior with their wrapped neighbours as context.
- **Band:** how many pixels from the edge that difference stays above half an 8-bit level.

| Frame | Mean level | Detail kept, flat / self | Mean change, flat | Edge, plain (flat / self) | Band px | Edge, 64 px wrap pad (flat / self) |
| --- | --- | --- | --- | --- | --- | --- |
| v00067 | 0.059 | 0.922 / 0.873 | 0.0015 | 1.7e‑4 / 2.0e‑4 | 0 / 0 | 2.7e‑5 / 2.4e‑5 |
| v00037 | 0.023 | 0.935 / 0.893 | 0.0009 | 3.2e‑4 / 2.8e‑4 | 0 / 0 | 3.9e‑5 / 4.5e‑5 |
| v00036 | 0.066 | 0.927 / 0.857 | 0.0016 | 8.8e‑4 / 9.0e‑4 | 0 / 0 | 1.3e‑4 / 1.4e‑4 |
| v00011 | 0.061 | 0.919 / 0.874 | 0.0015 | 3.3e‑4 / 3.3e‑4 | 0 / 0 | 4.3e‑5 / 6.9e‑5 |
| v00007 | 0.060 | 0.916 / 0.867 | 0.0016 | 1.9e‑4 / 2.1e‑4 | 0 / 0 | 2.7e‑5 / 2.3e‑5 |
| v02948 | 0.041 | 0.943 / 0.867 | 0.0009 | 1.6e‑4 / 1.8e‑4 | 0 / 0 | 2.7e‑5 / 3.3e‑5 |
| v02944 | 0.043 | 0.956 / 0.886 | 0.0010 | 3.5e‑4 / 3.7e‑4 | 0 / 0 | 4.4e‑5 / 4.6e‑5 |
| v02884 | 0.060 | 0.929 / 0.885 | 0.0015 | 1.9e‑4 / 2.3e‑4 | 0 / 0 | 4.3e‑5 / 3.7e‑5 |
| v02860 | 0.015 | 0.979 / 0.889 | 0.0008 | 3.5e‑5 / 3.6e‑5 | 0 / 0 | 6.5e‑6 / 5.0e‑6 |
| **v02859** | 0.049 | **0.756 / 0.591** | 0.0049 | **5.5e‑3 / 6.1e‑3** | **4 / 9** | 4.0e‑4 / 6.4e‑4 |

## How it was run

`afield.media.reconstruction.OidnImageSpike <out dir> <image>... [--save-images]` through
`field_backdoor.py run-jvm-main`. It hosts a denoiser on an idle `PathTracingRenderer` of the
image's size, writes each image into the accumulation buffer, sets the guides, and runs
`denoiseNow(perPass = true)`.

- **Guides:** `flat` is albedo 1 and normal +z; `self` is the image itself as albedo, normal +z.
- **Per image and guide set:** four denoises, plain and rolled, each with and without the
  wrap-around pad.
- **Output:** `results.json`, which includes the full edge profile to 256 px, and ×8
  difference maps. Full-size denoised PNGs (about 10 MB each) are written only with
  `--save-images`.

Caveats:

- **The inputs are the history screenshots.** They are 8-bit sRGB JPEGs of the four-window, not
  the fp16 ping-pong target the particles are drawn into, so JPEG blocking is part of what the
  network sees.
- **No frame was tileable to begin with.** That is why the test compares rolled and unrolled
  denoises, not the seam itself.
- **The faint even speckle in the difference maps** comes from autoexposure: rolling by half the
  height (1080) shifts the 16 × 16 bin grid. It is the floor under every edge profile.

## Where to go next

- **Wrap mode:** if `ps` ever denoises its particle texture, give `OidnComputeDenoiser` an image
  input and a wrap mode. The mode is the pad above, or `load()` wrapping at each level.
  Wrapping exactly needs width and height to be multiples of 16; 1920 × 1080 is not.
- **Keeping fine strokes:** for textures like v02859 the denoiser removes the look, not noise.
  Try a guide that carries the strokes, such as a separately drawn line-only pass as albedo, or
  leave such textures undenoised.
