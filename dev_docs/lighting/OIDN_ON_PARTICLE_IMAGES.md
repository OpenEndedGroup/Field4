# The GPU denoiser on particle renders, and tiling (2026-11-21)

Question: what does `OidnComputeDenoiser` (OIDN's RT network as Vulkan compute,
[`OIDN_COMPUTE_PORT.md`](OIDN_COMPUTE_PORT.md)) do to the abstract images the `ps` document's
animated particle systems draw, and does it break tiling? The `ps` kaleidoscope pass samples its
particle texture with `fract()`, so that texture is used as a torus.

**Answer, from 4 of the 10 planned frames:**

- **It mostly leaves them alone.** These images carry no Monte Carlo grain, and the network finds
  little to remove. Mean display brightness is unchanged (within 0.3 %). The mean display change
  per channel is 0.0009–0.0029, under one 8-bit level (0.0039).
- **It softens the finest strokes a little.** It removes 7–8 % of the fine-detail energy (RMS of
  the luminance Laplacian) with flat guides, and 11–14 % with the image itself as albedo. Using
  the image as a guide therefore smooths *more*, not less.
- **It does break the wrap, but well under one 8-bit level on average.** The network sees zeros
  past every edge, so opposite edges are denoised without each other. Mean error at the edge
  pixel is 1.7e‑4 to 8.8e‑4 in display units (0.04–0.22 of a level), and it falls to the
  background level within about 8 px. Per pixel it is larger: where a bright stroke crosses the
  edge, the ×8 difference maps show spots of roughly one to two levels, a few pixels wide.
- **A 64 px wrap-around pad fixes it.** Denoise the padded image and crop. The edge error then
  drops 6–8× to the background level (2.4e‑5 to 1.4e‑4), the same as rolling the padded image
  first. The cost is a slightly larger image: 3968 × 2288 instead of 3840 × 2160.
- **Cost at 3840 × 2160 on the M1 Max (KosmicKrisp, portable kernels):** 2.7 s per denoise (3.0 s
  padded) as a sum of one-pass submits, which keeps every submit under the Metal watchdog. Scratch
  memory is 3.46 GB (3.79 GB padded).

## Numbers

Display units: sRGB in [0, 1], averaged over RGB. "Edge" is the mean |difference| at the
outermost pixel between the plain denoise and the wrap-aware one. The wrap-aware result is the
image rolled by half its size, denoised, and rolled back, so the original edges sit in the
interior with their wrapped neighbours as context.

| Frame | Detail kept, flat / self | Mean change, flat | Edge, plain (flat / self) | Edge, 64 px wrap pad (flat / self) |
| --- | --- | --- | --- | --- |
| v00067 | 0.922 / 0.873 | 0.0015 | 1.7e‑4 / 2.0e‑4 | 2.7e‑5 / 2.4e‑5 |
| v00037 | 0.935 / 0.893 | 0.0009 | 3.2e‑4 / 2.8e‑4 | 3.9e‑5 / 4.5e‑5 |
| v00036 | 0.927 / 0.857 | 0.0016 | 8.8e‑4 / 9.0e‑4 | 1.3e‑4 / 1.4e‑4 |
| v00011 | 0.919 / 0.874 | 0.0015 | 3.3e‑4 / 3.3e‑4 | 4.3e‑5 / 6.9e‑5 |

The remaining six frames (v00007, v02948, v02944, v02884, v02860, v02859) did not run: the
disk filled while v00007's PNGs were being written.

## How it was run

`afield.media.reconstruction.OidnImageSpike <out dir> <image>...` through
`field_backdoor.py run-jvm-main`. It hosts a denoiser on an idle `PathTracingRenderer` of the
image's size, writes each image into the accumulation buffer, sets the guides, and runs
`denoiseNow(perPass = true)`.

- **Guides:** `flat` is albedo 1 and normal +z; `self` is the image itself as albedo, normal +z.
- **Per image and guide set:** four denoises, plain and rolled, each with and without the
  wrap-around pad.
- **Output:** `results.json`, which includes the full edge profile to 256 px, the denoised PNGs,
  and ×8 difference maps.

Caveats:

- **The inputs are the history screenshots.** They are 8-bit sRGB JPEGs of the four-window, not
  the fp16 ping-pong target the particles are drawn into, so JPEG blocking is part of what the
  network sees.
- **No frame was tileable to begin with.** That is why the test compares rolled and unrolled
  denoises, not the seam itself.
- **The faint even speckle in the difference maps** comes from autoexposure: rolling by half the
  height (1080) shifts the 16 × 16 bin grid. It sits at about 3e‑4, the floor in every edge
  profile.

## Where to go next

- **Wrap mode:** if `ps` ever denoises its particle texture, give `OidnComputeDenoiser` an image
  input and a wrap mode. The mode is the pad above, or `load()` wrapping at each level.
  Wrapping exactly needs width and height to be multiples of 16; 1920 × 1080 is not.
- **The remaining six frames:** run them once the disk has room. The harness writes about 40 MB
  of PNGs per frame.
