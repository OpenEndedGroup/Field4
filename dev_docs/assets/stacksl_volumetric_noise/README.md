# StackSL volumetric noise — the demo

`stacksl_volumetric_noise.yaml`, in this directory, is an operation transcript: one box, no
`agent_graphics_context` parent, no window, no Vulkan. The pictures arrive in the box's own output
panel as PNGs. Replay it into a fresh document with

```bash
python3 tools/field_backdoor.py --port 9481 --scratch \
  --command "transcript write volnoise --file dev_docs/assets/stacksl_volumetric_noise/stacksl_volumetric_noise.yaml" \
  --command "transcript run volnoise"
```

**Move `zStep`** — marked `>>> MOVE THIS ONE FIRST <<<` in the box. It is the distance between the
six z slices. The `fbm3` row decorrelates almost immediately whatever you set it to; the 2D `fbm`
row needs `zStep` up around 10 before it looks like it has changed at all, and even then it has only
slid sideways. That gap is the bug this branch is about.

## Why this lives here and not in the transcript corpus

`dev_docs/agents/transcripts/` is policed by `AgentTranscriptIndexGuard`, which requires a
hand-written index entry, a classification in `DemoTranscriptExecSmokeTest`, and — unless excluded —
an `Open ↗` link to a *committed* materialized bundle. That bundle is a generated artefact, and
regenerating generated artefacts is a post-merge step rather than something a feature branch does.
`transcript write --file` reaches this path just as well. `dev_docs/assets/splat_cpu_raster/` sets
the precedent and its README explains the trade-off at greater length.

## What keeps it honest

Nothing compiles a transcript at build time, so a demo can rot silently against the package it
demonstrates. `VolumetricNoiseDemoTest` (in `src/jvmTest/kotlin/afield/stacksl/`) is the guard:

- it compiles all five StackSL programs the yaml quotes, including the three `stack.auto` snippets
  in the prose that a reader is invited to paste;
- it checks that the GLSL lines the box fishes out with `.lines().first { … }` actually match
  something — that call throws rather than warns — and that `substringAfter("vec3 stacksl_curl3")`
  still has an anchor to find;
- it makes the same `VolumetricNoiseReference` and `PngEncoder` calls in the same shapes;
- it recomputes the divergence figure the description advertises instead of trusting it.

What it does **not** do is compile the box body itself. That is the residual risk, and it is why the
transcript carries an `execute` operation: replaying it runs the box, so a break shows up
immediately rather than the next time somebody opens the document.

## The measurement behind the pictures

`VolumetricNoiseFigureTest` renders the same comparison to a temp directory and then asserts what
the eye is supposed to find. Row-to-row self-similarity down the z axis, taking the best correlation
over all horizontal shifts so the comparison is generous to the 2D word:

| field | self-similarity across 40 rows of z |
| --- | --- |
| the shipped 2D `fbm` | **0.9999** |
| `fbm3` | 0.3374 |

0.9999 is not "similar". It is the identical row reproduced at every depth — a fixed pattern
imprinted through the whole volume, which is exactly what the person who hit this in `dust_two`
described before hand-writing a three-slice workaround.
