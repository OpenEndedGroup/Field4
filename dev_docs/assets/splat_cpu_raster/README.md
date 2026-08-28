# CPU splat raster — the demo, and render proof

## The demo

`splat_cpu_raster.yaml`, in this directory, is an operation transcript: one box, no
`agent_graphics_context` parent, no window, no Vulkan. Replay it into a fresh document with

```bash
python3 tools/field_backdoor.py --port 9481 \
  --command "transcript write splatdemo --file dev_docs/assets/splat_cpu_raster/splat_cpu_raster.yaml" \
  --command "transcript run splatdemo"
```

**Move `cropFar`** — marked `>>> MOVE THIS ONE FIRST <<<` in the box. It starts at `1.60f`, which
keeps all 20,112 splats of frame 60; `1.20f` prints `20112 splats -> 12001 (59.7% survived)` and
redraws the lower panel with the back of the subject sliced away; `1.05f` leaves 3,949. With no
capture on disk the box builds a synthetic spiral in the same depth range instead, so `cropFar` means
the same thing and the demo still runs.

### Why this lives here and not in the transcript corpus

`dev_docs/agents/transcripts/` is policed by `AgentTranscriptIndexGuard`, which requires every yaml in
it to have a hand-written entry in `public/docs/agent_transcripts.md`, to be classified in one of
`DemoTranscriptExecSmokeTest`'s three sets, and — unless it is in
`AgentTranscriptMaterializer.EXCLUDE` — to carry an `Open ↗` link to a *committed* materialized bundle.
That bundle is a generated artefact, and regenerating generated artefacts is a post-merge step rather
than something a feature branch does.

Putting it in the corpus would therefore have meant either shipping a dead link, or adding the demo to
`EXCLUDE` — which means "not self-contained", the opposite of what this one is, and which would stop
the welcome screen offering it. So the yaml lives here, where `transcript write --file` reaches it just
as well (the path is arbitrary; that is how it was tested). Promoting it into the corpus later is a
small, deliberate piece of work: an index paragraph, an entry in the exec set, and one bundle
regeneration.


## Render proof

Seven PNGs produced by the CPU splat rasterizer and **opened and looked at** by the agent that wrote
it, committed so the claims in [`../../COMPRESSED_SPLAT_PLY.md`](../../COMPRESSED_SPLAT_PLY.md) can be
checked by eye rather than taken on trust.

Every one of these comes from a **committed fixture**, not from any capture on disk, so they
regenerate byte-identically anywhere:

```bash
AFIELD_SPLAT_RASTER_PNG_DIR=dev_docs/assets/splat_cpu_raster \
  python3 tools/run_field_tests.py --id four.splat-raster.smoke --id four.splat-cloud-edit.smoke
```

(The tests themselves write to a temp directory and inspect there — the "tests: no committed writes"
invariant. `AFIELD_SPLAT_RASTER_PNG_DIR` is the opt-in that also drops a copy here, for a human.)

| File | What it proves |
| --- | --- |
| `orientation.png` | The X and Y conventions. Four corner-marked splats: red at world (−x, +y), green (+x, +y), blue (−x, −y), yellow (+x, −y), seen from +z with `up = +y`. Red **must** be top-left. The octagonal footprint is the 3σ quad with its corners cut by the alpha cutoff, exactly as on the GPU. |
| `known_pixel.png` | One splat at world (0.5, 0.25, 0), 128×128, 50° fovY. Its energy centroid lands at column 80.657, row 54.921 against 80.656 / 54.922 derived on paper from the perspective matrix, and its colour encodes to sRGB 255, 61, 128. |
| `agreement_fused.png` | The 1200-splat cloud on which `pack` and `packInto` are compared in pixels: 100.000% of 25,600 pixels bit-identical, worst channel delta 0. |
| `transform_bake.png`, `transform_edit.png` | The same cloud transformed two completely different ways — `packInto` folding the matrix into the covariance congruence, and `SplatCloud.transformed` decomposing to a rotation and three scales because that is all `.compressed.ply` holds. They differ by 0.120 code values per channel against an untransformed control of 63.774. They should be indistinguishable to you. |
| `endtoend_before.png`, `endtoend_after.png` | Read → query → edit → preview. `before` is 2000 splats; `after` is the same cloud cropped, filtered at 0.2 opacity, decimated to 0.7 and scaled 1.4×, leaving 363. |

## What is deliberately not here

Renders of the real 126-frame `circle-05` capture. They were made and inspected — a 58 KB single
frame and a 280 KB 24-tile contact sheet, both correct, both reported in the commit messages — but
they are derived from the human's own uncommitted material in `~/Downloads`, and this tree's standing
policy is not to commit that capture: "one is tens of megabytes and would be somebody's artwork rather
than a fixture". Regenerate them with:

```bash
run-jvm-main afield.four.modules.SplatToolsKt sheet ~/Downloads/circle-05-a120p0_00001_ out/
run-jvm-main afield.four.modules.SplatToolsKt frame  ~/Downloads/circle-05-a120p0_00001_/frame_000060.compressed.ply out/
run-jvm-main afield.four.modules.SplatToolsKt stats  ~/Downloads/circle-05-a120p0_00001_/frame_000060.compressed.ply
```

## Reading a splat PNG without being fooled

A 3DGS capture of a dim scene is *supposed* to come out dark, and `circle-05` averages a mean colour
of 0.157 with a mean saturation of 0.0013 — genuinely greyscale, genuinely dim. So "it looks nearly
black" is not a bug report on its own, and a failed capture on this machine writes a plausible file
rather than an empty one.

`SplatRaster.ImageStats` is the thing that separates the two, and `blank` is defined as
`coverage <= 0` rather than as a low mean luminance, on purpose. Check **coverage** and
**distinctColors**, not brightness. And in file sizes: a real render here is 8–280 KB; the known
signature of a failed capture on this machine is a 1–2 KB PNG.
