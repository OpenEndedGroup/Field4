# GPU state — the demo behind `public/docs/gpu_state.md`

## Open it

`gpu_state.yaml`, in this directory, is an operation transcript: three boxes — a renderer, a compute
box, and a draw box. Replay it into a fresh document with

```bash
python3 tools/field_backdoor.py --port 9483 --graphics-context \
  --command "transcript write gpustate --file dev_docs/agents/transcripts/gpu_state.yaml" \
  --command "transcript run gpustate"
```

or, in a running Field, paste the file into a macro and run it. It opens a window and draws 40,000
particles held on a noise field.

## What to move

**`simModule.force`**, in the compute box. It is a `stack.auto` slot — a StackSL program lowered to
GLSL and patched into the running pipeline on a background thread, so changing the string and hitting
run costs no shader reload and no dropped frame, and a program that does not compile leaves the
previous one in place.

- `input xyz 0.9 *` — a plain outward push. The disc inflates and stops.
- change `input xyz 7.0 *` to `3.0` or `14.0` — the same field at a coarser or finer scale. This is
  the knob that decides whether the picture reads as blobs or as filaments.
- `input xyz 2.0 * fbm x 0.5 - 8.0 *` — the mistake the page describes. StackSL's `fbm` is 2D, so one
  call over a 3D position gives a field in (x, y) that pushes every particle along the same diagonal,
  and the result is visibly *striped*.
- open `fbm3` with `=c` instead of `>c` and the box's `stack.auto` output channel opens with
  `problem: definition \`fbm3\` ends with 2 values on the stack, not 1`.

The other knobs are the two numbers inside `sim.knobs`: `.x` is the clock the noise field walks
through and `.y` is the force gain. Both are lambdas, so they are live too.

## Where the files are

Promoted into the guarded corpus on 2026-08-29, at the human's instruction, once the docs runner had
demonstrably replayed it: `gpu_state.yaml` is now `dev_docs/agents/transcripts/gpu_state.yaml`, entry
83, classified `COMPILE_ONLY` in `DemoTranscriptExecSmokeTest` beside `threebox` because it wants a
GLFW window and a Vulkan renderer that the lightweight build does not own. It carries an entry and an
`Open ↗` link in `public/docs/agent_transcripts.md`, a materialized bundle under
`public/docs/assets/agent_transcripts/gpu_state/`, and a welcome-screen starting point.

The one-line counterfactual variant is capture-only, so it went to
`dev_docs/agents/transcripts/docs_capture/gpu_state_flat_fbm.yaml` instead, which is outside the
counted 83 by design.

Only this README stayed behind, because it is prose about the demo rather than part of it.

## Provenance

The model is the human's `dust_two` document, read and rebuilt out of public `afield.four` API only.
`dust_two` imports `drowning.*` — `DustField`, `DustBuffers`, `DustLayout`, `dustGains(...)` — which
is a personal jar and not in this repository, so nothing here shares a line with it. What it does
share is the shape: four buffers with four different residencies, both halves of the ping-pong
seeded, bindings as lambdas outside any `loop {}`, one frame-parity source, and the force as an
editable string rather than a recompile.
